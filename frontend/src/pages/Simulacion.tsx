// src/pages/Simulacion.tsx
"use client";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, {
  type AirportPoint,
} from "@/components/common/map/AirportMarkers";
import FlightPath from "@/components/common/FlightPath";
import { useAirports } from "@/hooks/useAirports";
import { useRunSSE, type VueloDTO } from "@/hooks/useRunSSE";
import { useRunSession } from "@/lib/runSession";
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";
import SimulationFinishedOverlay from "@/components/common/SimulationFinishedOverlay";
import { downloadFile } from "@/services/api";

// Importamos las Cards
import AirportCard from "@/components/common/cards/AirportCard";
import FlightCard, {
  type FlightCardData,
} from "@/components/common/cards/FlightCard";
import OrderCard from "@/components/common/cards/OrderCard";
import { RunSessionProvider } from "@/lib/runSession";
import TopNav from "@/components/common/TopNav";
//import SimulationStatusWidget from "@/components/common/SimulationStatusWidget";

const COLOR_SEDE = "#005097";
const COLOR_NORMAL = "#38bdf8";
const HOVER_COLOR = "#ef4444";
const ACTIVE_COLOR = "#005097";

// DTO backend (zod)
const AirportDtoSchema = z.object({
  codigo: z.string(),
  ciudad: z.string().nullable().optional(),
  lon: z.number(),
  lat: z.number(),
  sede: z.boolean().optional(),
});
const AirportsDtoSchema = z.array(AirportDtoSchema);

type FlightForRender = FlightCardData & {
  origin: { lat: number; lon: number };
  dest: { lat: number; lon: number };
};

export function SimulacionContent() {
  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeFlight, setActiveFlight] = useState<FlightForRender | null>(
    null
  );
  const [hoveredFlight, setHoveredFlight] = useState<FlightForRender | null>(
    null
  );

  // Parsear aeropuertos
  const airports: AirportPoint[] = useMemo(() => {
    const parsed = AirportsDtoSchema.safeParse(airportsDtoRaw);
    if (!parsed.success) {
        console.error("[Simulacion] Error parseando aeropuertos:", parsed.error);
        return [];
    }
    return parsed.data.map((a) => ({
      id: a.codigo,
      name: `${a.ciudad ?? a.codigo} (${a.codigo})`,
      lon: a.lon,
      lat: a.lat,
      color: a.sede ? COLOR_SEDE : COLOR_NORMAL,
      isSede: !!a.sede,
    }));
  }, [airportsDtoRaw]);

  const airportsMap = useMemo(() => {
    const map = new Map<string, { lat: number; lon: number }>();
    airports.forEach((a) => map.set(a.id, { lat: a.lat, lon: a.lon }));
    return map;
  }, [airports]);

  const {
    runId,
    selectedAirportId,
    setSelectedAirport,
    reset,
    vuelosCancelados,
    windows,
    selectedPedido,
    setSelectedPedido,
    selectedVuelo,
    setSelectedVuelo,
    setToolsPanelOpen,
    //Para el overlay de LOADING:
    showLoadingOverlay,
    loadingMessage,
    loadingProgress,
  } = useRunSession();

  // [DEBUG] Diagnóstico de contexto
  // console.log(`[Simulacion RENDER] RunId: ${runId}, Windows: ${windows.length}, Overlay: ${showLoadingOverlay}`);

  const showOverlay = showLoadingOverlay && windows.length === 1;

  const {
    simNowUtc,
    airportOccupancy,
    finishedReason,
    simStartUtc,
    wallStartUtc,
    disconnect,
  } = useRunSSE(runId || undefined);

  const vuelosMap = useMemo(() => {
    const map = new Map<string, VueloDTO>();
    windows.forEach((w) => {
      w.vuelos.forEach((v) => {
        if (!map.has(v.id)) {
          map.set(v.id, v);
        }
      });
    });
    return map;
  }, [windows]);

  const airportsForRender = useMemo<AirportPoint[]>(() => {
    return airports.map((a) => {
      const occ = airportOccupancy[a.id];
      if (!occ || a.isSede) return a;
      let color = COLOR_NORMAL;
      if (occ.porcentaje > 0.8) color = "#f97316";
      else if (occ.porcentaje > 0.5) color = "#facc15";
      return { ...a, color };
    });
  }, [airports, airportOccupancy]);

  const flightFirstSeenRef = useRef<Map<string, number>>(new Map());

  const vuelosRelacionadosAlPedido = useMemo<Set<string>>(() => {
    if (!selectedPedido || !selectedPedido.rutas) return new Set();
    const vuelosIds = new Set<string>();
    selectedPedido.rutas.forEach((ruta) => {
      ruta.vuelos.forEach((vuelo) => {
        const salidaUtcSinColon = vuelo.salidaUtc.replace(/:/g, "");
        const vueloId = `${vuelo.origen}-${vuelo.destino}-${salidaUtcSinColon}`;
        vuelosIds.add(vueloId);
      });
    });
    return vuelosIds;
  }, [selectedPedido]);


  // Flights render logic
  const flightsToRender = useMemo<FlightForRender[]>(() => {
    if (!simNowUtc) return []; // Si no hay hora, no hay mapa
    if (windows.length === 0) return [];

    const now = new Date(simNowUtc).getTime();
    const allFlights: FlightForRender[] = [];
    const seenIds = new Set<string>();
    const vuelosUnicos = new Map<string, (typeof windows)[0]["vuelos"][0]>();

    windows.forEach((window) => {
      window.vuelos.forEach((vuelo) => {
        if (!vuelosUnicos.has(vuelo.id)) vuelosUnicos.set(vuelo.id, vuelo);
      });
    });

    const tienePedidoSeleccionado =
      selectedPedido !== null && vuelosRelacionadosAlPedido.size > 0;

    // Contadores para DEBUG
    let rejectedByCancel = 0;
    let rejectedByCoords = 0;
    let rejectedByTime = 0;

    vuelosUnicos.forEach((vuelo) => {
      if (vuelosCancelados.has(vuelo.id)) {
        rejectedByCancel++;
        return;
      }
      if (tienePedidoSeleccionado && !vuelosRelacionadosAlPedido.has(vuelo.id))
        return;

      const origen = airportsMap.get(vuelo.origen);
      const destino = airportsMap.get(vuelo.destino);
      
      if (!origen || !destino) {
        // console.warn(`[Simulacion] Coordenadas faltantes para vuelo ${vuelo.id}: ${vuelo.origen}->${vuelo.destino}`);
        rejectedByCoords++;
        return;
      }

      const salidaTime = new Date(vuelo.salidaUtc).getTime();
      const llegadaTime = new Date(vuelo.llegadaUtc).getTime();

      // [DEBUG LOGIC] ¿Está el vuelo en el aire AHORA?
      if (now < salidaTime || now > llegadaTime) {
        rejectedByTime++;
        return;
      }

      if (!flightFirstSeenRef.current.has(vuelo.id)) {
        flightFirstSeenRef.current.set(vuelo.id, Math.max(now, salidaTime));
      }
      const firstSeen =
        flightFirstSeenRef.current.get(vuelo.id) ?? Math.max(now, salidaTime);
      const duracionRestante = Math.max(1, llegadaTime - firstSeen);
      const elapsed = Math.max(0, Math.min(now, llegadaTime) - firstSeen);
      const progress = Math.min(1, elapsed / duracionRestante);

      const ocupacion = vuelo.cantidadAsignada / vuelo.capacidad;
      let pathColor = "#38bdf8";
      let planeColor = "#0284c7";
      if (ocupacion > 0.8) {
        pathColor = "#f97316";
        planeColor = "#ea580c";
      } else if (ocupacion > 0.5) {
        pathColor = "#facc15";
        planeColor = "#eab308";
      }

      allFlights.push({
        id: vuelo.id,
        origin: origen,
        dest: destino,
        progress,
        pathColor,
        planeColor,
        // Card Data
        origen: vuelo.origen,
        destino: vuelo.destino,
        salidaUtc: vuelo.salidaUtc,
        llegadaUtc: vuelo.llegadaUtc,
        capacidad: vuelo.capacidad,
        cantidadAsignada: vuelo.cantidadAsignada,
        carga: vuelo.carga || [],
      });
      seenIds.add(vuelo.id);
    });

    flightFirstSeenRef.current.forEach((_, key) => {
      if (!seenIds.has(key)) flightFirstSeenRef.current.delete(key);
    });

    // [DEBUG LOG] Resumen del ciclo de renderizado
    console.log(
        `[Simulacion Loop] Total Únicos: ${vuelosUnicos.size} -> Renderizados: ${allFlights.length}. ` +
        `Rechazados: Tiempo(${rejectedByTime}), Coords(${rejectedByCoords}), Cancel(${rejectedByCancel})`
    );

    return allFlights;
  }, [
    windows,
    simNowUtc,
    airportsMap,
    vuelosCancelados,
    selectedPedido,
    vuelosRelacionadosAlPedido,
  ]);

  // Sincronizar vuelo activo (hover/click)
  useEffect(() => {
    if (!activeFlight) return;
    if (vuelosCancelados.has(activeFlight.id)) {
      console.log("[Simulacion] Deseleccionando vuelo activo por cancelación");
      setActiveFlight(null);
      return;
    }
    const refreshed = flightsToRender.find((f) => f.id === activeFlight.id);
    if (!refreshed) setActiveFlight(null);
    else if (refreshed !== activeFlight) setActiveFlight(refreshed);
  }, [flightsToRender, activeFlight, vuelosCancelados]);

  // Data aeropuerto activo
  const activeAirportData =
    selectedAirportId && airportOccupancy[selectedAirportId]
      ? airportOccupancy[selectedAirportId]
      : null;
  const esSede = selectedAirportId
    ? ["SPIM", "EBCI", "UBBB"].includes(selectedAirportId)
    : false;

  const activeAirportStaticData = useMemo(() => {
    return airports.find((a) => a.id === selectedAirportId);
  }, [airports, selectedAirportId]);

  // Cálculos de vuelos y RECOJOS futuros
  const vuelosFuturos = useMemo(() => {
    if (!selectedAirportId || !simNowUtc) return { llegadas: [], salidas: [] };

    const now = new Date(simNowUtc).getTime();
    const next24h = now + 24 * 60 * 60 * 1000;

    // --- TIPOS ACTUALIZADOS: Incluyen pedidoId ---
    const llegadas: Array<{
      id: string;
      origen: string;
      cantidad: number;
      salidaUtc: string;
      llegadaUtc: string;
      isPickup?: boolean;
      pedidoId?: number;
    }> = [];

    const salidas: Array<{
      id: string;
      destino: string;
      cantidad: number;
      salidaUtc: string;
      llegadaUtc: string;
      isPickup?: boolean;
      pedidoId?: number;
    }> = [];

    // 1. PROCESAR VUELOS
    const vuelosUnicos = new Map<string, (typeof windows)[0]["vuelos"][0]>();
    windows.forEach((window) => {
      window.vuelos.forEach((vuelo) => {
        if (!vuelosCancelados.has(vuelo.id) && !vuelosUnicos.has(vuelo.id)) {
          vuelosUnicos.set(vuelo.id, vuelo);
        }
      });
    });

    vuelosUnicos.forEach((vuelo) => {
      const salidaTime = new Date(vuelo.salidaUtc).getTime();
      const llegadaTime = new Date(vuelo.llegadaUtc).getTime();

      if (
        vuelo.destino === selectedAirportId &&
        llegadaTime > now &&
        llegadaTime <= next24h
      ) {
        llegadas.push({
          id: vuelo.id,
          origen: vuelo.origen,
          cantidad: vuelo.cantidadAsignada,
          salidaUtc: vuelo.salidaUtc,
          llegadaUtc: vuelo.llegadaUtc,
        });
      }
      if (
        vuelo.origen === selectedAirportId &&
        salidaTime <= next24h &&
        llegadaTime > now
      ) {
        salidas.push({
          id: vuelo.id,
          destino: vuelo.destino,
          cantidad: vuelo.cantidadAsignada,
          salidaUtc: vuelo.salidaUtc,
          llegadaUtc: vuelo.llegadaUtc,
        });
      }
    });

    // 2. PROCESAR RECOJOS DE CLIENTES
    const pedidosUnicos = new Map<number, (typeof windows)[0]["pedidos"][0]>();
    windows.forEach((w) => {
      if (w.pedidos) w.pedidos.forEach((p) => pedidosUnicos.set(p.id, p));
    });

    pedidosUnicos.forEach((pedido) => {
      if (pedido.destino === selectedAirportId && pedido.recojos) {
        pedido.recojos.forEach((recojo, idx) => {
          const inicioEspera = new Date(recojo.inicioRecojo).getTime();
          const finEspera = new Date(recojo.finRecojo).getTime();

          const esFuturoCercano = inicioEspera > now && inicioEspera <= next24h;
          const estaOcurriendo = now >= inicioEspera && now <= finEspera;

          if (esFuturoCercano || estaOcurriendo) {
            salidas.push({
              id: `PICKUP-${pedido.id}-${idx}`,
              destino: `Cliente ${pedido.idCliente}`,
              cantidad: recojo.cantidad,
              salidaUtc: recojo.inicioRecojo,
              llegadaUtc: recojo.finRecojo,
              isPickup: true,
              pedidoId: pedido.id, // <--- AQUÍ SE PASA EL ID DEL PEDIDO
            });
          }
        });
      }
    });

    llegadas.sort(
      (a, b) =>
        new Date(a.llegadaUtc).getTime() - new Date(b.llegadaUtc).getTime()
    );
    salidas.sort(
      (a, b) =>
        new Date(a.salidaUtc).getTime() - new Date(b.salidaUtc).getTime()
    );

    return { llegadas, salidas };
  }, [selectedAirportId, simNowUtc, windows, vuelosCancelados]);

  // --- Lógica fin simulación ---
  const [finishedAt, setFinishedAt] = useState<number | null>(null);
  const [overlayVisible, setOverlayVisible] = useState(true);

  useEffect(() => {
    if (finishedReason) {
      console.log("[Simulacion] Run finalizado. Razón:", finishedReason);
      setOverlayVisible(true);
    }
  }, [finishedReason]);

  useEffect(() => {
    if (finishedReason && !finishedAt) setFinishedAt(Date.now());
  }, [finishedReason, finishedAt]);

  useEffect(() => {
    setFinishedAt(null);
  }, [runId]);

  const handleDownloadReports = async () => {
    if (!runId) return;
    await downloadFile(
      `reportes/downloadReporteSimulacion`,
      "reporteSimulacion.txt"
    );
    await downloadFile(
      `reportes/downloadUltimaPlanificacion`,
      "ultimaPlanificacion.txt"
    );
  };

  const handleCloseOverlay = () => {
    console.log("[Simulacion] Cerrando overlay y reseteando sesión.");
    setOverlayVisible(false);
    disconnect(); // <--- Usado
    reset();      // <--- Usado
  };

  const showFinishedOverlay = !!finishedReason && !!runId && overlayVisible;

  const flightDataForCard = useMemo(() => {
    if (selectedVuelo) {
      return {
        ...selectedVuelo,
        origenCodigo: selectedVuelo.origen,
        destinoCodigo: selectedVuelo.destino,
      } as FlightCardData;
    }
    if (hoveredFlight && !selectedAirportId) {
      return hoveredFlight;
    }
    return null;
  }, [selectedVuelo, hoveredFlight, selectedAirportId]);

  return (
    <div className="min-h-screen bg-neutral-50 relative">
    {/*<SimulationStatusWidget />*/}
      {/* Toast de carga */}
      {/* Overlay de carga */}
      {showOverlay && (
        <>
          {/* Fondo borroso */}
          <div
            className="
        fixed inset-0 z-[90]
        bg-white/40 dark:bg-slate-950/40
        backdrop-blur-sm
        transition-opacity
      "
          />

          {/* Toast de carga */}
          <div className="fixed bottom-6 right-6 z-[100] animate-in slide-in-from-bottom-5 fade-in duration-300">
            <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-2xl rounded-xl p-4 flex items-center gap-4 max-w-sm">
              <div className="relative flex h-10 w-10 shrink-0 overflow-hidden rounded-full items-center justify-center bg-blue-50 dark:bg-blue-900/20">
                <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
              </div>

              <div className="grid gap-1">
                <p className="text-sm font-semibold text-slate-900 dark:text-slate-50">
                  Cargando simulación…
                </p>

                <p className="text-xs text-slate-500 dark:text-slate-400">
                  {loadingMessage || "Preparando entorno…"}
                  {loadingProgress > 0 && (
                    <span className="ml-1 font-mono">
                      ({Math.round(loadingProgress)}%)
                    </span>
                  )}
                </p>
              </div>
            </div>
          </div>
        </>
      )}

      {/* 1. TARJETA DE AEROPUERTO */}
      {activeAirportData && selectedAirportId && (
        <AirportCard
          airportId={selectedAirportId}
          airportName={activeAirportStaticData?.name || selectedAirportId}
          data={activeAirportData}
          isSede={esSede}
          flights={vuelosFuturos}
          onClose={() => setSelectedAirport(null)}
        />
      )}

      {/* 2. TARJETA DE VUELO */}
      {flightDataForCard && !activeAirportData && (
        <FlightCard
          data={flightDataForCard}
          simNowUtc={simNowUtc}
          onClose={() => {
            setSelectedVuelo(null);
            setHoveredFlight(null);
          }}
          isHover={!!hoveredFlight && !selectedVuelo}
        />
      )}

      {/* 3. TARJETA DE PEDIDO */}
      {selectedPedido && !selectedVuelo && !selectedAirportId && (
        <OrderCard
          pedido={selectedPedido}
          simNowUtc={simNowUtc}
          variant="simulacion"
          onClose={() => setSelectedPedido(null)}
        />
      )}

      <MainMap>
        {flightsToRender.map((flight) => (
          <FlightPath
            key={flight.id}
            id={flight.id}
            origin={flight.origin}
            dest={flight.dest}
            progress={flight.progress ?? 0}
            pathColor={flight.pathColor}
            planeColor={flight.planeColor}
            onMouseEnter={() => setHoveredFlight(flight)}
            onMouseLeave={() => setHoveredFlight(null)}
            onClick={() => {
              console.log("[Simulacion] Click en FlightPath:", flight.id);
              setActiveFlight((prev) =>
                prev?.id === flight.id ? null : flight
              );
              const vueloDto = vuelosMap.get(flight.id);
              if (vueloDto) {
                setSelectedVuelo(vueloDto);
                setSelectedAirport(null);
                setToolsPanelOpen(true); // <--- Usado
              }
              setHoveredFlight(null);
            }}
          />
        ))}

        <AirportMarkers
          items={airportsForRender}
          activeId={selectedAirportId}
          hoveredId={hoveredAirportId}
          baseColor={COLOR_NORMAL}
          activeColor={ACTIVE_COLOR}
          hoverColor={HOVER_COLOR}
          onHoverChange={setHoveredAirportId}
          onClick={(id) => {
            console.log("[Simulacion] Click en Aeropuerto:", id);
            const newId = selectedAirportId === id ? null : id;
            setSelectedAirport(newId);
            if (newId) {
              setSelectedVuelo(null);
              setToolsPanelOpen(true); // <--- Usado
            }
          }}
          iconSize={16}
        />
      </MainMap>

      {finishedReason && runId && (
        <SimulationFinishedOverlay
          open={showFinishedOverlay}
          reason={finishedReason}
          simStartUtc={simStartUtc ?? null}
          simEndUtc={simNowUtc ?? null}
          wallAnchor={wallStartUtc}
          finishedAt={finishedAt}
          onClose={handleCloseOverlay}
          onDownloadReports={handleDownloadReports}
        />
      )}
    </div>
  );
}

export default function Simulacion() {
  return (
    <RunSessionProvider>
      <TopNav />
      <SimulacionContent />
    </RunSessionProvider>
  );
}
