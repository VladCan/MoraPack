// src/pages/Operacion.tsx
"use client";
import { useMemo, useState, useRef, useEffect } from "react";
import { z } from "zod";
import FlightPath from "@/components/common/FlightPath";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import { useAirports } from "@/hooks/useAirports";
import { useFlightsSSE } from "@/hooks/useFlightsSSE";
import { useRunSession } from "@/lib/runSession";
import { useRunSSE, type VueloDTO } from "@/hooks/useRunSSE";
import type { AeropuertoDTO } from "@/types/api";
import { downloadFile } from "@/services/api";
import SimulationFinishedOverlay from "@/components/common/SimulationFinishedOverlay";

// --- IMPORTS DE LAS CARDS REUTILIZABLES ---
import AirportCard from "@/components/common/cards/AirportCard";
import FlightCard, { type FlightCardData } from "@/components/common/cards/FlightCard";
import OrderCard from "@/components/common/cards/OrderCard";

import { RunSessionProvider } from "@/lib/runSession";
import TopNav from "@/components/common/TopNav";

const COLOR_SEDE   = "#005097";
const COLOR_NORMAL = "#38bdf8"; 
const HOVER_COLOR  = "#ef4444";
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

// Tipo para vuelos de vuelos.txt (sin información completa)
type FlightDto = {
  id: string;
  originLat: number;
  originLon: number;
  destLat: number;
  destLon: number;
  progress: number;
  pathColor: string;
  planeColor: string;
};

// Tipo para vuelos de la solución (con información completa)
type FlightForRender = {
  id: string;
  origin: { lat: number; lon: number };
  dest: { lat: number; lon: number };
  progress: number;
  pathColor: string;
  planeColor: string;
  // Información adicional para el tooltip (solo vuelos de la solución)
  origenCodigo?: string;
  destinoCodigo?: string;
  salidaUtc?: string;
  llegadaUtc?: string;
  capacidad?: number;
  cantidadAsignada?: number;
  carga?: Array<{
    pedidoId: number;
    cantidad: number;
    destinoFinal: string;
    esConexion: boolean;
  }>;
  esDeSolucion?: boolean; // Flag para distinguir vuelos de la solución
};

export  function OperacionContent() {
  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  
  // Estado local para sincronización visual
  const [activeFlight, setActiveFlight] = useState<FlightForRender | null>(null);
  const [hoveredFlight, setHoveredFlight] = useState<FlightForRender | null>(null);

  const airports: AirportPoint[] = useMemo(() => {
    const parsed = AirportsDtoSchema.safeParse(airportsDtoRaw);
    if (!parsed.success) return [];
    return parsed.data.map((a) => ({
      id: a.codigo,
      name: `${a.ciudad ?? a.codigo} (${a.codigo})`,
      lon: a.lon,
      lat: a.lat,
      color: a.sede ? COLOR_SEDE : COLOR_NORMAL,
      isSede: !!a.sede, 
    }));
  }, [airportsDtoRaw]);

  // Conectar a la sesión y al SSE
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
    setSelectedVuelo 
  } = useRunSession();
  
  const { 
    runState,           
    loadingMessage,     
    loadingProgress,    
    simNowUtc, 
    airportOccupancy, 
    finishedReason,     
    simStartUtc, 
    wallStartUtc, 
    disconnect 
  } = useRunSSE(runId || undefined);

   const vuelosMap = useMemo(() => {
      const map = new Map<string, VueloDTO>();
      windows.forEach(w => {
        w.vuelos.forEach(v => {
          if (!map.has(v.id)) {
            map.set(v.id, v);
          }
        });
      });
      return map;
    }, [windows]);

  // Usar tiempo simulado si hay runId, sino usar hora del sistema
  const liveFlightsEndpoint = runId 
    ? `vuelos/live?runId=${runId}&limit=200`
    : "vuelos/live?limit=200";
  const { data: liveFlights } = useFlightsSSE(liveFlightsEndpoint);
  
  // Crear mapa de aeropuertos para calcular posiciones
  const airportsMap = useMemo(() => {
    const map = new Map<string, { lat: number; lon: number }>();
    airports.forEach(a => map.set(a.id, { lat: a.lat, lon: a.lon }));
    return map;
  }, [airports]);

  // Crear mapa de capacidades de aeropuertos desde el frontend
  const airportsCapacityMap = useMemo(() => {
    const map = new Map<string, number>();
    if (airportsDtoRaw) {
      (airportsDtoRaw as AeropuertoDTO[]).forEach(a => {
        if (a.capacidad !== null && a.capacidad !== undefined) {
          map.set(a.codigo, a.capacidad);
        }
      });
    }
    return map;
  }, [airportsDtoRaw]);

  const flightFirstSeenRef = useRef<Map<string, number>>(new Map());

  // Extraer IDs de vuelos relacionados al pedido seleccionado
  const vuelosRelacionadosAlPedido = useMemo<Set<string>>(() => {
    if (!selectedPedido || !selectedPedido.rutas) {
      return new Set();
    }
    
    const vuelosIds = new Set<string>();
    selectedPedido.rutas.forEach(ruta => {
      ruta.vuelos.forEach(vuelo => {
        const salidaUtcSinColon = vuelo.salidaUtc.replace(/:/g, '');
        const vueloId = `${vuelo.origen}-${vuelo.destino}-${salidaUtcSinColon}`;
        vuelosIds.add(vueloId);
      });
    });
    
    return vuelosIds;
  }, [selectedPedido]);

  const flightPaths = useMemo<FlightForRender[]>(() => {
    const now = simNowUtc ? new Date(simNowUtc).getTime() : Date.now();
    const allFlights: FlightForRender[] = [];
    const seenIds = new Set<string>();

    // Si hay un pedido seleccionado, solo mostrar vuelos relacionados
    const tienePedidoSeleccionado = selectedPedido !== null && vuelosRelacionadosAlPedido.size > 0;

    // 1. PRIMERO: Vuelos planificados por el algoritmo ALNS (prioridad)
    if (windows.length > 0 && simNowUtc) {
      const vuelosUnicos = new Map<string, typeof windows[0]['vuelos'][0]>();
      
      // Acumular vuelos de todas las ventanas
      windows.forEach(window => {
        window.vuelos.forEach(vuelo => {
          if (!vuelosCancelados.has(vuelo.id) && !vuelosUnicos.has(vuelo.id)) {
            vuelosUnicos.set(vuelo.id, vuelo);
          }
        });
      });

      // Procesar vuelos planificados que están en el aire
      vuelosUnicos.forEach(vuelo => {
        if (tienePedidoSeleccionado && !vuelosRelacionadosAlPedido.has(vuelo.id)) {
          return;
        }
        
        const origen = airportsMap.get(vuelo.origen);
        const destino = airportsMap.get(vuelo.destino);
        
        if (!origen || !destino) return;

        const salidaTime = new Date(vuelo.salidaUtc).getTime();
        const llegadaTime = new Date(vuelo.llegadaUtc).getTime();

        // Solo mostrar vuelos que ya salieron y no han llegado
        if (now >= salidaTime && now <= llegadaTime) {
          // Calcular progreso
          if (!flightFirstSeenRef.current.has(vuelo.id)) {
            flightFirstSeenRef.current.set(vuelo.id, Math.max(now, salidaTime));
          }
          const firstSeen = flightFirstSeenRef.current.get(vuelo.id) ?? Math.max(now, salidaTime);
          const transcurridoDesdeVista = Math.max(0, Math.min(now, llegadaTime) - firstSeen);
          const duracionRestante = Math.max(1, llegadaTime - firstSeen);
          const progress = Math.min(1, transcurridoDesdeVista / duracionRestante);

          const ocupacion = vuelo.cantidadAsignada / vuelo.capacidad;
          let pathColor = "#38bdf8"; // Azul claro
          let planeColor = "#0284c7";
          if (ocupacion > 0.8) {
            pathColor = "#dc2626";
            planeColor = "#b91c1c";
          } else if (ocupacion > 0.5) {
            pathColor = "#f59e0b";
            planeColor = "#d97706";
          }

          allFlights.push({
            id: vuelo.id,
            origin: origen,
            dest: destino,
            progress,
            pathColor,
            planeColor,
            // Información completa para el tooltip
            origenCodigo: vuelo.origen,
            destinoCodigo: vuelo.destino,
            salidaUtc: vuelo.salidaUtc,
            llegadaUtc: vuelo.llegadaUtc,
            capacidad: vuelo.capacidad,
            cantidadAsignada: vuelo.cantidadAsignada,
            carga: vuelo.carga || [],
            esDeSolucion: true,
          });
          seenIds.add(vuelo.id);
        }
      });
    }

    // 2. SEGUNDO: Vuelos de vuelos.txt que NO están planificados por el algoritmo
    const arr = (liveFlights ?? []) as FlightDto[];
    let vuelosTxtCount = 0;
    const MAX_VUELOS_TXT = 20; 
    
    arr.forEach(f => {
      if (tienePedidoSeleccionado) {
        return; // Ocultar todos los vuelos SSE cuando hay un pedido seleccionado
      }
      
      if (!vuelosCancelados.has(f.id) && !seenIds.has(f.id) && vuelosTxtCount < MAX_VUELOS_TXT) {
        allFlights.push({
          id: f.id,
          origin: { lat: f.originLat, lon: f.originLon },
          dest: { lat: f.destLat, lon: f.destLon },
          progress: f.progress,
          pathColor: f.pathColor,
          planeColor: f.planeColor,
          esDeSolucion: false,
        });
        seenIds.add(f.id);
        vuelosTxtCount++;
      }
    });

    // Limpiar vuelos que ya no están visibles
    flightFirstSeenRef.current.forEach((_, key) => {
      if (!seenIds.has(key)) {
        flightFirstSeenRef.current.delete(key);
      }
    });

    return allFlights;
  }, [liveFlights, vuelosCancelados, windows, simNowUtc, airportsMap, selectedPedido, vuelosRelacionadosAlPedido]);

  // Sincronizar vuelo activo con datos actualizados
  useEffect(() => {
    if (!activeFlight) return;
    if (vuelosCancelados.has(activeFlight.id)) {
      setActiveFlight(null);
      return;
    }
    const refreshed = flightPaths.find((f) => f.id === activeFlight.id);
    if (!refreshed) {
      setActiveFlight(null);
    } else if (refreshed !== activeFlight) {
      setActiveFlight(refreshed);
    }
  }, [flightPaths, activeFlight, vuelosCancelados]);

  // Obtener datos del aeropuerto activo
  const activeAirportData = selectedAirportId
    ? (airportOccupancy[selectedAirportId] || {
        ocupacionActual: 0,
        capacidadTotal: airportsCapacityMap.get(selectedAirportId) || 0,
        disponible: airportsCapacityMap.get(selectedAirportId) || 0,
        porcentaje: 0,
        cargaLlegando: 0,
        cargaSaliendo: 0,
      })
    : null;

  const SEDES = ["SPIM", "EBCI", "UBBB"];
  const esSede = selectedAirportId ? SEDES.includes(selectedAirportId) : false;
  
  const activeAirportStaticData = useMemo(() => {
    return airports.find((a) => a.id === selectedAirportId);
  }, [airports, selectedAirportId]);

  // Calcular vuelos futuros (llegadas/salidas) + RECOJOS DE CLIENTES
  const vuelosFuturos = useMemo(() => {
    if (!selectedAirportId || !simNowUtc || windows.length === 0) {
      return { llegadas: [], salidas: [] };
    }

    const now = new Date(simNowUtc).getTime();
    const next24h = now + 24 * 60 * 60 * 1000; // 24 horas en ms

    const llegadas: Array<{ id: string; origen: string; cantidad: number; salidaUtc: string; llegadaUtc: string; isPickup?: boolean; pedidoId?: number }> = [];
    const salidas: Array<{ id: string; destino: string; cantidad: number; salidaUtc: string; llegadaUtc: string; isPickup?: boolean; pedidoId?: number }> = [];

    // Recopilar todos los vuelos únicos de todas las ventanas
    const vuelosUnicos = new Map<string, typeof windows[0]['vuelos'][0]>();
    windows.forEach(window => {
      window.vuelos.forEach(vuelo => {
        if (!vuelosCancelados.has(vuelo.id) && !vuelosUnicos.has(vuelo.id)) {
          vuelosUnicos.set(vuelo.id, vuelo);
        }
      });
    });

    vuelosUnicos.forEach(vuelo => {
      const salidaTime = new Date(vuelo.salidaUtc).getTime();
      const llegadaTime = new Date(vuelo.llegadaUtc).getTime();

      // Vuelos que llegarán al aeropuerto seleccionado (solo futuros)
      if (vuelo.destino === selectedAirportId && llegadaTime > now && llegadaTime <= next24h) {
        llegadas.push({ 
          id: vuelo.id, 
          origen: vuelo.origen, 
          cantidad: vuelo.cantidadAsignada,
          salidaUtc: vuelo.salidaUtc,
          llegadaUtc: vuelo.llegadaUtc
        });
      }

      // Vuelos que saldrán del aeropuerto seleccionado
      if (vuelo.origen === selectedAirportId && salidaTime <= next24h && llegadaTime > now) {
        salidas.push({ 
          id: vuelo.id, 
          destino: vuelo.destino, 
          cantidad: vuelo.cantidadAsignada,
          salidaUtc: vuelo.salidaUtc,
          llegadaUtc: vuelo.llegadaUtc
        });
      }
    });

    // --- NUEVO: PROCESAR RECOJOS DE CLIENTES ---
    // Iteramos los pedidos únicos de todas las ventanas
    const pedidosUnicos = new Map<number, typeof windows[0]["pedidos"][0]>();
    windows.forEach(w => {
        if(w.pedidos) w.pedidos.forEach(p => pedidosUnicos.set(p.id, p));
    });

    pedidosUnicos.forEach(pedido => {
        // Solo si el destino es este aeropuerto y tiene recojos
        if (pedido.destino === selectedAirportId && pedido.recojos) {
            
            pedido.recojos.forEach((recojo, idx) => {
                const inicioEspera = new Date(recojo.inicioRecojo).getTime();
                const finEspera = new Date(recojo.finRecojo).getTime();

                // Lógica de visualización:
                const esFuturoCercano = (inicioEspera > now && inicioEspera <= next24h);
                const estaOcurriendo = (now >= inicioEspera && now <= finEspera);

                if (esFuturoCercano || estaOcurriendo) {
                    salidas.push({
                        id: `PICKUP-${pedido.id}-${idx}`, // ID único visual
                        destino: `Cliente ${pedido.idCliente}`, // Se verá en la Card
                        cantidad: recojo.cantidad,
                        salidaUtc: recojo.inicioRecojo, // Usamos inicio como "Salida" visual
                        llegadaUtc: recojo.finRecojo,   // Usamos fin como "Llegada" visual
                        isPickup: true, // Flag importante
                        pedidoId: pedido.id // <--- PASAMOS EL ID DEL PEDIDO
                    });
                }
            });
        }
    });

    // Ordenar cronológicamente
    llegadas.sort((a, b) => new Date(a.llegadaUtc).getTime() - new Date(b.llegadaUtc).getTime());
    salidas.sort((a, b) => new Date(a.salidaUtc).getTime() - new Date(b.salidaUtc).getTime());

    return { llegadas, salidas };
  }, [selectedAirportId, simNowUtc, windows, vuelosCancelados]);

  // --- LÓGICA DE FIN ACTUALIZADA ---
  const [finishedAt, setFinishedAt] = useState<number | null>(null);
  const [overlayVisible, setOverlayVisible] = useState(true);

  // Usamos finishedReason en lugar de finished
  useEffect(() => {
    if (!finishedReason) return;
    console.log ("🔚 [Operación] Terminó:", finishedReason);
    setOverlayVisible(true);
  }, [finishedReason, disconnect, reset]);

  useEffect(() => {
    if (finishedReason && !finishedAt) {
      setFinishedAt(Date.now());
    }
  }, [finishedReason, finishedAt]);

  useEffect(() => {
    setFinishedAt(null);
  }, [runId]);

  const handleDownloadReports = async () => {
    if (!runId) return;
    await downloadFile(`reportes/downloadReporteSimulacion`, "reporteSimulacion.txt");
    await downloadFile(`reportes/downloadUltimaPlan`, "ultimaPlanificacion.txt")
  };

  const handleCloseOverlay = () => {
    setOverlayVisible(false);
    disconnect();
    reset();
  };

  const showFinishedOverlay = !!finishedReason && !!runId && overlayVisible;

  // --- ADAPTADOR DE DATOS PARA FLIGHTCARD ---
  const flightDataForCard = useMemo(() => {
    // 1. Prioridad: Vuelo seleccionado por Click (desde Mapa o Panel)
    if (selectedVuelo) {
      return {
        ...selectedVuelo,
        origenCodigo: selectedVuelo.origen,
        destinoCodigo: selectedVuelo.destino
      } as FlightCardData;
    }
    // 2. Prioridad: Vuelo en Hover (solo si es de solución)
    if (hoveredFlight && hoveredFlight.esDeSolucion && !selectedAirportId) {
        // En Operación, los tipos ya coinciden bastante bien, solo aseguramos el casteo
        if(hoveredFlight.origenCodigo && hoveredFlight.destinoCodigo && hoveredFlight.salidaUtc && hoveredFlight.llegadaUtc) {
             return {
                id: hoveredFlight.id,
                origen: hoveredFlight.origenCodigo, // Card espera string
                destino: hoveredFlight.destinoCodigo,
                salidaUtc: hoveredFlight.salidaUtc,
                llegadaUtc: hoveredFlight.llegadaUtc,
                capacidad: hoveredFlight.capacidad || 0,
                cantidadAsignada: hoveredFlight.cantidadAsignada || 0,
                carga: hoveredFlight.carga || [],
                planeColor: hoveredFlight.planeColor,
                pathColor: hoveredFlight.pathColor,
                progress: hoveredFlight.progress
             } as FlightCardData;
        }
    }
    return null;
  }, [selectedVuelo, hoveredFlight, selectedAirportId]);

  return (
    <div className="min-h-screen bg-neutral-50 relative">

      {/* --- TOAST DE CARGA --- */}
      {runState === 'LOADING' && (
        <div className="fixed bottom-6 right-6 z-[100] animate-in slide-in-from-bottom-5 fade-in duration-300">
           <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-2xl rounded-xl p-4 flex items-center gap-4 max-w-sm">
              <div className="relative flex h-10 w-10 shrink-0 overflow-hidden rounded-full items-center justify-center bg-blue-50 dark:bg-blue-900/20">
                 <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
              </div>
              <div className="grid gap-1">
                 <p className="text-sm font-semibold text-slate-900 dark:text-slate-50">
                    Cargando Operación...
                 </p>
                 <p className="text-xs text-slate-500 dark:text-slate-400">
                    {loadingMessage || "Sincronizando datos..."} 
                    {loadingProgress > 0 && <span className="ml-1 font-mono">({Math.round(loadingProgress)}%)</span>}
                 </p>
              </div>
           </div>
        </div>
      )}

      {/* --- CARDS FLOTANTES --- */}

      {/* 1. AEROPUERTO */}
      {selectedAirportId && activeAirportData && (
        <AirportCard
          airportId={selectedAirportId}
          airportName={activeAirportStaticData?.name || selectedAirportId}
          data={activeAirportData}
          isSede={esSede}
          flights={vuelosFuturos}
          onClose={() => setSelectedAirport(null)}
        />
      )}

      {/* 2. VUELO */}
      {flightDataForCard && !selectedAirportId && (
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

      {/* 3. PEDIDO */}
      {selectedPedido && !selectedVuelo && !selectedAirportId && (
        <OrderCard
          pedido={selectedPedido}
          simNowUtc={simNowUtc}
          variant="operacion" 
          onClose={() => setSelectedPedido(null)}
        />
      )}

      <MainMap>
        {/* Renderizar vuelos animados */}
        {flightPaths.map((flight) => (
          <FlightPath
            key={flight.id}
            id={flight.id}
            origin={flight.origin}
            dest={flight.dest}
            progress={flight.progress}
            pathColor={flight.pathColor}
            planeColor={flight.planeColor}
            onMouseEnter={
              flight.esDeSolucion
              ? () => setHoveredFlight(flight)
              : undefined
            }
            onMouseLeave={
              flight.esDeSolucion
              ? () => setHoveredFlight((prev) => (prev?.id === flight.id ? null : prev))
              : undefined
            }
            onClick={
              // Solo permitir click en vuelos de la solución
              flight.esDeSolucion
                ? () => {
                   setActiveFlight((prev) => (prev?.id === flight.id ? null : flight))

                   // sincroniza con el panel:
                   const vueloDto = vuelosMap.get(flight.id);
                   if (vueloDto) {
                     setSelectedVuelo(vueloDto);
                     setSelectedAirport(null);
                   }
                   //Para asegurar que el hover no quede abierto
                   setHoveredFlight(null);
                }
                : undefined
            }
          />
        ))}

        {/* Renderizar aeropuertos */}
        <AirportMarkers
          items={airports}
          activeId={selectedAirportId}
          hoveredId={hoveredAirportId}
          baseColor={COLOR_NORMAL}
          activeColor={ACTIVE_COLOR}
          hoverColor={HOVER_COLOR}
          onHoverChange={setHoveredAirportId}
          onClick={(id) => {
            const newId = selectedAirportId === id ? null : id;
            setSelectedAirport(newId);
            if (newId) {
                setSelectedVuelo(null);
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

export default function Operacion() {
  return (
    <RunSessionProvider>
      <TopNav />
      <OperacionContent />
    </RunSessionProvider>
  );
}