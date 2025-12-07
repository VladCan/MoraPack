// src/pages/Colapso.tsx
"use client";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import FlightPath from "@/components/common/FlightPath";
import { useAirports } from "@/hooks/useAirports";
import { useRunSSE } from "@/hooks/useRunSSE";
import { useRunSession } from "@/lib/runSession";
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";
import SimulationFinishedOverlay from "@/components/common/SimulationFinishedOverlay";
import { downloadFile } from "@/services/api";

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

type FlightForRender = {
  id: string;
  origin: { lat: number; lon: number };
  dest: { lat: number; lon: number };
  progress: number;
  pathColor: string;
  planeColor: string;
  // Información adicional para el tooltip
  origenCodigo: string;
  destinoCodigo: string;
  salidaUtc: string;
  llegadaUtc: string;
  capacidad: number;
  cantidadAsignada: number;
  carga: Array<{
    pedidoId: number;
    cantidad: number;
    destinoFinal: string;
    esConexion: boolean;
  }>;
};

export default function Colapso() {

  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeFlight, setActiveFlight] = useState<FlightForRender | null>(null);

  // Parsear aeropuertos
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

  // Crear mapa de aeropuertos por código para lookups rápidos
  const airportsMap = useMemo(() => {
    const map = new Map<string, { lat: number; lon: number }>();
    airports.forEach(a => map.set(a.id, { lat: a.lat, lon: a.lon }));
    return map;
  }, [airports]);

  // Conectar al SSE
  const { runId, selectedAirportId, setSelectedAirport, reset, vuelosCancelados, windows, selectedPedido } = useRunSession();
const { simNowUtc, airportOccupancy, finished, simStartUtc, wallStartUtc, disconnect } = useRunSSE(runId || undefined);

  // Procesar vuelos para renderizar
  const flightFirstSeenRef = useRef<Map<string, number>>(new Map());

  // Extraer IDs de vuelos relacionados al pedido seleccionado
  const vuelosRelacionadosAlPedido = useMemo<Set<string>>(() => {
    if (!selectedPedido || !selectedPedido.rutas) {
      return new Set(); // Si no hay pedido seleccionado, no filtrar
    }
    
    const vuelosIds = new Set<string>();
    selectedPedido.rutas.forEach(ruta => {
      ruta.vuelos.forEach(vuelo => {
        // El ID del vuelo se construye como: origen-destino-salidaUtc (sin :)
        // El backend usa: vueloId.getOrigen() + "-" + vueloId.getDestino() + "-" + vueloId.getSalidaUtc().toString().replace(":", "")
        // salidaUtc viene como ISO string, necesitamos quitar los :
        const salidaUtcSinColon = vuelo.salidaUtc.replace(/:/g, '');
        const vueloId = `${vuelo.origen}-${vuelo.destino}-${salidaUtcSinColon}`;
        vuelosIds.add(vueloId);
      });
    });
    
    return vuelosIds;
  }, [selectedPedido]);

  const flightsToRender = useMemo<FlightForRender[]>(() => {
    if (!simNowUtc || windows.length === 0) {
      return [];
    }

    const now = new Date(simNowUtc).getTime();
    const allFlights: FlightForRender[] = [];
    const seenIds = new Set<string>();

    // Crear un Set para evitar duplicados (mismo vuelo en múltiples ventanas)
    const vuelosUnicos = new Map<string, typeof windows[0]['vuelos'][0]>();

    // Acumular vuelos de TODAS las ventanas para tener el panorama completo
    windows.forEach(window => {
      window.vuelos.forEach(vuelo => {
        // Solo agregar si no existe o si queremos actualizar con info más reciente
        if (!vuelosUnicos.has(vuelo.id)) {
          vuelosUnicos.set(vuelo.id, vuelo);
        }
      });
    });

    // Si hay un pedido seleccionado, solo mostrar vuelos relacionados
    const tienePedidoSeleccionado = selectedPedido !== null && vuelosRelacionadosAlPedido.size > 0;

    // Ahora procesamos todos los vuelos únicos (excluyendo cancelados)
    vuelosUnicos.forEach(vuelo => {
        // Excluir vuelos cancelados
        if (vuelosCancelados.has(vuelo.id)) return;
        
        // Si hay un pedido seleccionado, solo mostrar vuelos relacionados
        if (tienePedidoSeleccionado && !vuelosRelacionadosAlPedido.has(vuelo.id)) {
          return; // Ocultar vuelos no relacionados
        }
        
        const origen = airportsMap.get(vuelo.origen);
        const destino = airportsMap.get(vuelo.destino);

        if (!origen || !destino) {
          console.warn(`[Simulacion] Aeropuerto no encontrado: ${vuelo.origen} o ${vuelo.destino}`);
          return;
        }

        const salidaTime = new Date(vuelo.salidaUtc).getTime();
        const llegadaTime = new Date(vuelo.llegadaUtc).getTime();

        // Solo mostrar vuelos que ya salieron y no han llegado
        if (now < salidaTime || now > llegadaTime) {
          return;
        }

        // Calcular progreso (0.0 a 1.0)
        //const duracion = llegadaTime - salidaTime; //not being used
        if (!flightFirstSeenRef.current.has(vuelo.id)) {
          flightFirstSeenRef.current.set(vuelo.id, Math.max(now, salidaTime));
        }
        const firstSeen = flightFirstSeenRef.current.get(vuelo.id) ?? Math.max(now, salidaTime);
        const transcurridoDesdeVista = Math.max(0, Math.min(now, llegadaTime) - firstSeen);
        const duracionRestante = Math.max(1, llegadaTime - firstSeen);
        const progress = Math.min(1, transcurridoDesdeVista / duracionRestante);

        // Determinar color basado en ocupación
        const ocupacion = vuelo.cantidadAsignada / vuelo.capacidad;
        let pathColor = "#38bdf8"; // Azul claro
        let planeColor = "#0284c7"; // Azul

        if (ocupacion > 0.8) {
          pathColor = "#f97316"; // Naranja
          planeColor = "#ea580c";
        } else if (ocupacion > 0.5) {
          pathColor = "#facc15"; // Amarillo
          planeColor = "#eab308";
        }

        allFlights.push({
          id: vuelo.id,
          origin: origen,
          dest: destino,
          progress,
          pathColor,
          planeColor,
          origenCodigo: vuelo.origen,
          destinoCodigo: vuelo.destino,
          salidaUtc: vuelo.salidaUtc,
          llegadaUtc: vuelo.llegadaUtc,
          capacidad: vuelo.capacidad,
          cantidadAsignada: vuelo.cantidadAsignada,
          carga: vuelo.carga || [],
        });
        seenIds.add(vuelo.id);
    });

    // Limpiar vuelos que ya no están visibles
    flightFirstSeenRef.current.forEach((_, key) => {
      if (!seenIds.has(key)) {
        flightFirstSeenRef.current.delete(key);
      }
    });

    return allFlights;
  }, [windows, simNowUtc, airportsMap, vuelosCancelados, selectedPedido, vuelosRelacionadosAlPedido]);

  // Sincronizar vuelo activo con datos actualizados y limpiar si fue cancelado
  useEffect(() => {
    if (!activeFlight) return;
    // Limpiar si el vuelo fue cancelado
    if (vuelosCancelados.has(activeFlight.id)) {
      setActiveFlight(null);
      return;
    }
    const refreshed = flightsToRender.find((f) => f.id === activeFlight.id);
    if (!refreshed) {
      setActiveFlight(null);
    } else if (refreshed !== activeFlight) {
      setActiveFlight(refreshed);
    }
  }, [flightsToRender, activeFlight, vuelosCancelados]);

  // Obtener datos del aeropuerto activo
  const activeAirportData = selectedAirportId && airportOccupancy[selectedAirportId]
    ? airportOccupancy[selectedAirportId]
    : null;

  // Verificar si el aeropuerto seleccionado es una sede (SPIM, EBCI, UBBB)
  const SEDES = ["SPIM", "EBCI", "UBBB"];
  const esSede = selectedAirportId ? SEDES.includes(selectedAirportId) : false;


  //Para la pantalla de fin:

  // Calcular vuelos que llegarán y saldrán en las próximas 24 horas
  const vuelosFuturos = useMemo(() => {
    if (!selectedAirportId || !simNowUtc || windows.length === 0) {
      return { llegadas: [], salidas: [] };
    }

    const now = new Date(simNowUtc).getTime();
    const next24h = now + 24 * 60 * 60 * 1000; // 24 horas en ms
    const past24h = now - 24 * 60 * 60 * 1000; // Últimas 24 horas también

    const llegadas: Array<{ id: string; origen: string; cantidad: number }> = [];
    const salidas: Array<{ id: string; destino: string; cantidad: number }> = [];

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
          cantidad: vuelo.cantidadAsignada 
        });
      }

      // Vuelos que saldrán del aeropuerto seleccionado
      // Incluir vuelos que salieron en las últimas 24h o saldrán en las próximas 24h
      // Filtrar estrictamente para evitar mantener vuelos antiguos en memoria
      if (vuelo.origen === selectedAirportId && salidaTime >= past24h && salidaTime <= next24h) {
        salidas.push({ 
          id: vuelo.id, 
          destino: vuelo.destino, 
          cantidad: vuelo.cantidadAsignada 
        });
      }
    });

    // Ordenar salidas por tiempo de salida (más recientes primero)
    salidas.sort((a, b) => {
      const vueloA = vuelosUnicos.get(a.id);
      const vueloB = vuelosUnicos.get(b.id);
      if (!vueloA || !vueloB) return 0;
      return new Date(vueloB.salidaUtc).getTime() - new Date(vueloA.salidaUtc).getTime();
    });

    return { llegadas, salidas };
  }, [selectedAirportId, simNowUtc, windows, vuelosCancelados]);

  const [finishedAt, setFinishedAt] = useState<number | null>(null);

  useEffect(() => {
    if (!finished) return;
    console.log ("🔚 [Simulación] Terminó:", finished.reason);

    setOverlayVisible(true);
  }, [finished, disconnect, reset]);

  useEffect(() => {
    if (finished && !finishedAt) {
      setFinishedAt(Date.now());
    }
  }, [finished, finishedAt]);

  useEffect(() => {
    setFinishedAt(null);
  }, [runId]);

  const handleDownloadReports = async () => {
    if (!runId) return;

    await downloadFile(`reportes/downloadReporteSimulacion`, "reporteSimulacion.txt");
    await downloadFile(`reportes/downloadUltimaPlan`, "ultimaPlanificacion.txt")

    // Endpoint a crear, por ejemplo:
    // GET /runs/{id}/report  -> devuelve ZIP
    // downloadFile(`runs/${runId}/report`, `reporte-simulacion-${runId}.zip`);
  };

  const handleCloseOverlay = () => {
    setOverlayVisible(false);

    //Cortamos el SSE
    disconnect();

    //Limpiamos el contexto de la simulación
    reset();
  };

  const [overlayVisible, setOverlayVisible] = useState(true);

  const showFinishedOverlay = !!finished && !!runId && overlayVisible;

  return (
    <div className="min-h-screen bg-neutral-50 relative">
      {/*<p className="text-rose-600">{simNowUtc}</p>*/}
      {/* Tooltip de aeropuerto */}
      {activeAirportData && (
        <div className="absolute top-20 right-4 z-50 w-96 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90">
          <div className="space-y-3">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border pb-2">
              <div className="flex items-center gap-2">
                {!esSede && (
                  <div className="w-3 h-3 rounded-full" style={{ backgroundColor: activeAirportData.porcentaje > 0.8 ? "#f97316" : activeAirportData.porcentaje > 0.5 ? "#facc15" : "#38bdf8" }}></div>
                )}
                <h3 className="font-semibold text-lg">
                  {selectedAirportId}
                </h3>
              </div>
              <div className="flex items-center gap-2">
                {!esSede && (
                  <span className="text-xs text-muted-foreground">
                    {Math.round(activeAirportData.porcentaje * 100)}%
                  </span>
                )}
                <button
                  onClick={() => setSelectedAirport(null)}
                  className="text-muted-foreground hover:text-foreground transition-colors"
                  aria-label="Cerrar"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                    <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                  </svg>
                </button>
              </div>
            </div>

            {/* Capacidad - Solo mostrar si NO es sede */}
            {!esSede && (
              <div>
                <div className="flex justify-between text-sm mb-1">
                  <span className="text-muted-foreground">Ocupación</span>
                  <span className="font-semibold">
                    {activeAirportData.ocupacionActual} / {activeAirportData.capacidadTotal}
                  </span>
                </div>
                <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                  <div
                    className="h-full transition-all"
                    style={{
                      width: `${activeAirportData.porcentaje * 100}%`,
                      backgroundColor: activeAirportData.porcentaje > 0.8 ? "#f97316" : activeAirportData.porcentaje > 0.5 ? "#facc15" : "#38bdf8",
                    }}
                  />
                </div>
              </div>
            )}

            {/* Disponible - Solo mostrar si NO es sede */}
            {!esSede && (
              <div>
                <p className="text-muted-foreground text-xs">Disponible</p>
                <p className="font-semibold text-lg">{activeAirportData.disponible} uds</p>
              </div>
            )}

            {/* Eventos en tiempo real */}
            {(activeAirportData.cargaLlegando !== undefined && activeAirportData.cargaLlegando > 0) ||
             (activeAirportData.cargaSaliendo !== undefined && activeAirportData.cargaSaliendo > 0) ? (
              <div className="border-t border-border pt-3 mt-3">
                <p className="text-xs font-semibold mb-2 text-muted-foreground">En este momento</p>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  {activeAirportData.cargaLlegando !== undefined && activeAirportData.cargaLlegando > 0 && (
                    <div className="bg-green-500/10 rounded p-2 border border-green-500/20">
                      <p className="text-green-600 dark:text-green-400 font-semibold">Llegando</p>
                      <p className="text-sm font-bold text-green-700 dark:text-green-300">+{activeAirportData.cargaLlegando} uds</p>
                    </div>
                  )}
                  {activeAirportData.cargaSaliendo !== undefined && activeAirportData.cargaSaliendo > 0 && (
                    <div className="bg-orange-500/10 rounded p-2 border border-orange-500/20">
                      <p className="text-orange-600 dark:text-orange-400 font-semibold">Saliendo</p>
                      <p className="text-sm font-bold text-orange-700 dark:text-orange-300">-{activeAirportData.cargaSaliendo} uds</p>
                    </div>
                  )}
                </div>
              </div>
            ) : null}

            {/* Estadísticas futuras (24h) - Siempre visible */}
            <div className="border-t border-border pt-3 mt-3">
              {esSede ? (
                // Para sedes: solo mostrar Salidas
                <div className="text-xs">
                  <div>
                    <p className="text-muted-foreground mb-1">Salidas</p>
                    {vuelosFuturos.salidas.length > 0 ? (
                      <div className="space-y-1.5 max-h-32 overflow-y-auto">
                        {vuelosFuturos.salidas.map((v, idx) => (
                          <div key={idx} className="text-[10px] space-y-0.5">
                            <div className="flex items-center gap-1">
                              <span className="font-mono text-muted-foreground text-[9px]">{v.id}</span>
                            </div>
                            <div className="flex justify-between items-center">
                              <span className="text-muted-foreground">Destino: {v.destino}</span>
                              <span className="font-semibold text-orange-600 dark:text-orange-400">-{v.cantidad}</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-[10px] text-muted-foreground">0 vuelos</p>
                    )}
                  </div>
                </div>
              ) : (
                // Para no sedes: mostrar Llegadas y Salidas
                <div className="grid grid-cols-2 gap-3 text-xs">
                  <div>
                    <p className="text-muted-foreground mb-1">Llegadas</p>
                    {vuelosFuturos.llegadas.length > 0 ? (
                      <div className="space-y-1.5 max-h-32 overflow-y-auto">
                        {vuelosFuturos.llegadas.map((v, idx) => (
                          <div key={idx} className="text-[10px] space-y-0.5">
                            <div className="flex items-center gap-1">
                              <span className="font-mono text-muted-foreground text-[9px]">{v.id}</span>
                            </div>
                            <div className="flex justify-between items-center">
                              <span className="text-muted-foreground">Origen: {v.origen}</span>
                              <span className="font-semibold text-green-600 dark:text-green-400">+{v.cantidad}</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-[10px] text-muted-foreground">0 vuelos</p>
                    )}
                  </div>
                  <div>
                    <p className="text-muted-foreground mb-1">Salidas</p>
                    {vuelosFuturos.salidas.length > 0 ? (
                      <div className="space-y-1.5 max-h-32 overflow-y-auto">
                        {vuelosFuturos.salidas.map((v, idx) => (
                          <div key={idx} className="text-[10px] space-y-0.5">
                            <div className="flex items-center gap-1">
                              <span className="font-mono text-muted-foreground text-[9px]">{v.id}</span>
                            </div>
                            <div className="flex justify-between items-center">
                              <span className="text-muted-foreground">Destino: {v.destino}</span>
                              <span className="font-semibold text-orange-600 dark:text-orange-400">-{v.cantidad}</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-[10px] text-muted-foreground">0 vuelos</p>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Tooltip de vuelo */}
      {activeFlight && !selectedAirportId && (
        <div className="absolute top-20 right-4 z-50 w-96 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto">
          <div className="space-y-3">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border pb-2">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full" style={{ backgroundColor: activeFlight.planeColor }}></div>
                <h3 className="font-semibold text-lg">
                  {activeFlight.origenCodigo} → {activeFlight.destinoCodigo}
                </h3>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs text-muted-foreground">
                  {Math.round(activeFlight.progress * 100)}%
                </span>
                <button
                  onClick={() => setActiveFlight(null)}
                  className="text-muted-foreground hover:text-foreground transition-colors"
                  aria-label="Cerrar detalles de vuelo"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                  </svg>
                </button>
              </div>
            </div>

            {/* Tiempos */}
            <div className="grid grid-cols-2 gap-2 text-sm">
              <div>
                <p className="text-muted-foreground text-xs">Salida</p>
                <p className="font-mono text-xs">
                  {new Date(activeFlight.salidaUtc).toLocaleTimeString('es-PE', {
                    hour: '2-digit',
                    minute: '2-digit',
                    timeZone: 'UTC'
                  })} UTC
                </p>
              </div>
              <div>
                <p className="text-muted-foreground text-xs">Llegada</p>
                <p className="font-mono text-xs">
                  {new Date(activeFlight.llegadaUtc).toLocaleTimeString('es-PE', {
                    hour: '2-digit',
                    minute: '2-digit',
                    timeZone: 'UTC'
                  })} UTC
                </p>
              </div>
            </div>

            {/* Capacidad */}
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-muted-foreground">Ocupación</span>
                <span className="font-semibold">
                  {activeFlight.cantidadAsignada} / {activeFlight.capacidad}
                </span>
              </div>
              <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                <div
                  className="h-full transition-all"
                  style={{
                    width: `${(activeFlight.cantidadAsignada / activeFlight.capacidad) * 100}%`,
                    backgroundColor: activeFlight.pathColor,
                  }}
                />
              </div>
            </div>

            {/* Carga */}
            {activeFlight.carga.length > 0 && (
              <div>
                <p className="text-sm font-semibold mb-2">
                  Carga ({activeFlight.carga.length} {activeFlight.carga.length === 1 ? 'pedido' : 'pedidos'})
                </p>
                <div className="max-h-40 overflow-y-auto space-y-1">
                  {activeFlight.carga.map((item, idx) => (
                    <div
                      key={idx}
                      className="flex items-center justify-between text-xs p-2 rounded-lg bg-muted/50"
                    >
                      <div className="flex items-center gap-2">
                        <span className="font-mono font-semibold">#{item.pedidoId}</span>
                        {item.esConexion && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200">
                            Conexión
                          </span>
                        )}
                      </div>
                      <div className="text-right">
                        <p className="font-semibold">{item.cantidad} uds</p>
                        <p className="text-muted-foreground text-[10px]">→ {item.destinoFinal}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      <MainMap>
        {/* Renderizar vuelos animados */}
        {flightsToRender.map((flight) => (
          <FlightPath
            key={flight.id}
            id={flight.id}
            origin={flight.origin}
            dest={flight.dest}
            progress={flight.progress}
            pathColor={flight.pathColor}
            planeColor={flight.planeColor}
            onClick={() =>
              setActiveFlight((prev) => (prev?.id === flight.id ? null : flight))
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
          onClick={(id) =>
            setSelectedAirport(selectedAirportId === id ? null : id)
          }
          iconSize={16}
        />
      </MainMap>

      {finished && runId && (
        <SimulationFinishedOverlay
          open={showFinishedOverlay}
          reason={finished.reason}
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
