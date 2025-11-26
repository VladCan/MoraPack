"use client";
import { useMemo, useState, useRef, useEffect } from "react";
import { z } from "zod";
import FlightPath from "@/components/common/FlightPath";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import { useAirports } from "@/hooks/useAirports";
import { useFlightsSSE } from "@/hooks/useFlightsSSE";
import { useRunSession } from "@/lib/runSession";
import { useRunSSE } from "@/hooks/useRunSSE";
import type { AeropuertoDTO } from "@/types/api";

const COLOR_SEDE   = "#005097";
const COLOR_NORMAL = "#38bdf8"; // 👈 más suave que #0ea5e9
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

export default function Operacion() {
  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeFlight, setActiveFlight] = useState<FlightForRender | null>(null);

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

  // Obtener vuelos de ambas fuentes
  const { runId, vuelosCancelados, selectedAirportId, setSelectedAirport, windows } = useRunSession();
  const { simNowUtc, airportOccupancy } = useRunSSE(runId || undefined);
  const { data: liveFlights } = useFlightsSSE("vuelos/live?limit=200");
  
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

  const flightPaths = useMemo<FlightForRender[]>(() => {
    const now = simNowUtc ? new Date(simNowUtc).getTime() : Date.now();
    const allFlights: FlightForRender[] = [];
    const seenIds = new Set<string>();

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

          // Determinar color basado en ocupación
          // Vuelos planificados por el algoritmo tienen colores diferentes
          const ocupacion = vuelo.cantidadAsignada / vuelo.capacidad;
          let pathColor = "#8b5cf6"; // Morado para vuelos planificados
          let planeColor = "#7c3aed";
          if (ocupacion > 0.8) {
            pathColor = "#dc2626"; // Rojo oscuro para alta ocupación
            planeColor = "#b91c1c";
          } else if (ocupacion > 0.5) {
            pathColor = "#f59e0b"; // Naranja para ocupación media
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
            esDeSolucion: true, // Marcar como vuelo de la solución
          });
          seenIds.add(vuelo.id);
        }
      });
    }

    // 2. SEGUNDO: Vuelos de vuelos.txt que NO están planificados por el algoritmo
    // Limitar a los primeros 20 para evitar saturación visual
    const arr = (liveFlights ?? []) as FlightDto[];
    let vuelosTxtCount = 0;
    const MAX_VUELOS_TXT = 20; // Límite de vuelos de vuelos.txt a mostrar
    
    arr.forEach(f => {
      // Solo incluir si no está cancelado, no está ya en la lista (planificado), y no excedemos el límite
      if (!vuelosCancelados.has(f.id) && !seenIds.has(f.id) && vuelosTxtCount < MAX_VUELOS_TXT) {
        allFlights.push({
      id: f.id,
      origin: { lat: f.originLat, lon: f.originLon },
      dest: { lat: f.destLat, lon: f.destLon },
      progress: f.progress,
      pathColor: f.pathColor,
      planeColor: f.planeColor,
          esDeSolucion: false, // Marcar como vuelo de vuelos.txt (sin información completa)
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
  }, [liveFlights, vuelosCancelados, windows, simNowUtc, airportsMap]);

  // Sincronizar vuelo activo con datos actualizados y limpiar si fue cancelado
  useEffect(() => {
    if (!activeFlight) return;
    // Limpiar si el vuelo fue cancelado
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
  // Mostrar popup incluso si no hay datos todavía (mostrar valores por defecto)
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

  return (
    <div className="min-h-screen bg-neutral-50 relative">
      {/* Tooltip de aeropuerto */}
      {selectedAirportId && activeAirportData && (
        <div className="absolute top-20 right-4 z-50 w-80 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90">
          <div className="space-y-3">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border pb-2">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full" style={{ backgroundColor: activeAirportData.porcentaje > 0.8 ? "#f97316" : activeAirportData.porcentaje > 0.5 ? "#facc15" : "#38bdf8" }}></div>
                <h3 className="font-semibold text-lg">
                  {selectedAirportId}
                </h3>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs text-muted-foreground">
                  {Math.round(activeAirportData.porcentaje * 100)}%
                </span>
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

            {/* Capacidad */}
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-muted-foreground">Ocupación</span>
                <span className="font-semibold">
                  {activeAirportData.capacidadTotal > 0 ? (
                    `${activeAirportData.ocupacionActual} / ${activeAirportData.capacidadTotal}`
                  ) : (
                    <span className="text-muted-foreground text-xs">0 / -</span>
                  )}
                </span>
              </div>
              {activeAirportData.capacidadTotal > 0 ? (
                <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                  <div
                    className="h-full transition-all"
                    style={{
                      width: `${activeAirportData.porcentaje * 100}%`,
                      backgroundColor: activeAirportData.porcentaje > 0.8 ? "#f97316" : activeAirportData.porcentaje > 0.5 ? "#facc15" : "#38bdf8",
                    }}
                  />
                </div>
              ) : (
                <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                  <div className="h-full bg-muted" style={{ width: "0%" }} />
                </div>
              )}
            </div>

            {/* Disponible */}
            <div>
              <p className="text-muted-foreground text-xs">Disponible</p>
              <p className="font-semibold text-lg">
                {activeAirportData.capacidadTotal > 0 ? (
                  `${activeAirportData.disponible} uds`
                ) : (
                  <span className="text-muted-foreground text-xs">-</span>
                )}
              </p>
            </div>

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

            {/* Estadísticas futuras (24h) */}
            {activeAirportData.estadisticasFuturas && (
              <div className="border-t border-border pt-3 mt-3">
                <p className="text-xs font-semibold mb-2 text-muted-foreground">Próximas 24 horas</p>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <p className="text-muted-foreground">Llegadas</p>
                    <p className="font-semibold">{activeAirportData.estadisticasFuturas.llegadasPrevistas} vuelos</p>
                    <p className="text-[10px] text-muted-foreground">+{activeAirportData.estadisticasFuturas.cargaEntrante} uds</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">Salidas</p>
                    <p className="font-semibold">{activeAirportData.estadisticasFuturas.salidasPrevistas} vuelos</p>
                    <p className="text-[10px] text-muted-foreground">-{activeAirportData.estadisticasFuturas.cargaSaliente} uds</p>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Tooltip de vuelo (solo para vuelos de la solución) */}
      {activeFlight && activeFlight.esDeSolucion && !selectedAirportId && (
        <div className="absolute top-20 right-4 z-50 w-80 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto">
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
            {activeFlight.salidaUtc && activeFlight.llegadaUtc && (
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
            )}

            {/* Capacidad */}
            {activeFlight.capacidad !== undefined && activeFlight.cantidadAsignada !== undefined && (
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
            )}

            {/* Carga */}
            {activeFlight.carga && activeFlight.carga.length > 0 && (
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
        {flightPaths.map((flight) => (
          <FlightPath
            key={flight.id}
            id={flight.id}
            origin={flight.origin}
            dest={flight.dest}
            progress={flight.progress}
            pathColor={flight.pathColor}
            planeColor={flight.planeColor}
            onClick={
              // Solo permitir click en vuelos de la solución
              flight.esDeSolucion
                ? () => setActiveFlight((prev) => (prev?.id === flight.id ? null : flight))
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
          onClick={(id) =>
            setSelectedAirport(selectedAirportId === id ? null : id)
          }
          iconSize={16}
        />
      </MainMap>
    </div>
  );
}
