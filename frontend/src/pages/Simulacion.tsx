// src/pages/Simulacion.tsx
"use client";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import FlightPath from "@/components/common/FlightPath";
import { useAirports } from "@/hooks/useAirports";
import { useRunSSE } from "@/hooks/useRunSSE";
import { useRunSession } from "@/lib/runSession";
import { useMemo, useState } from "react";
import { z } from "zod";

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

export default function Simulacion() {

  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeAirportId, setActiveAirportId] = useState<string | null>(null);
  const [hoveredFlight, setHoveredFlight] = useState<FlightForRender | null>(null);

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
  const { runId } = useRunSession();
  const { simNowUtc, windows } = useRunSSE(runId || undefined);

  // Procesar vuelos para renderizar
  const flightsToRender = useMemo<FlightForRender[]>(() => {
    if (!simNowUtc || windows.length === 0) {
      return [];
    }

    const now = new Date(simNowUtc).getTime();
    const allFlights: FlightForRender[] = [];

    // ⚠️ IMPORTANTE: Solo procesar la ventana más reciente para evitar duplicados
    const latestWindow = windows[windows.length - 1];

    latestWindow.vuelos.forEach(vuelo => {
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
        const duracion = llegadaTime - salidaTime;
        const transcurrido = now - salidaTime;
        const progress = duracion > 0 ? Math.min(1, Math.max(0, transcurrido / duracion)) : 0;

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
    });

    return allFlights;
  }, [windows, simNowUtc, airportsMap]);

  return (
    <div className="min-h-screen bg-neutral-50 relative">
      {/* Tooltip de vuelo */}
      {hoveredFlight && (
        <div className="absolute top-20 right-4 z-50 w-80 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-none">
          <div className="space-y-3">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border pb-2">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full" style={{ backgroundColor: hoveredFlight.planeColor }}></div>
                <h3 className="font-semibold text-lg">
                  {hoveredFlight.origenCodigo} → {hoveredFlight.destinoCodigo}
                </h3>
              </div>
              <span className="text-xs text-muted-foreground">
                {Math.round(hoveredFlight.progress * 100)}%
              </span>
            </div>

            {/* Tiempos */}
            <div className="grid grid-cols-2 gap-2 text-sm">
              <div>
                <p className="text-muted-foreground text-xs">Salida</p>
                <p className="font-mono text-xs">
                  {new Date(hoveredFlight.salidaUtc).toLocaleTimeString('es-PE', {
                    hour: '2-digit',
                    minute: '2-digit',
                    timeZone: 'UTC'
                  })} UTC
                </p>
              </div>
              <div>
                <p className="text-muted-foreground text-xs">Llegada</p>
                <p className="font-mono text-xs">
                  {new Date(hoveredFlight.llegadaUtc).toLocaleTimeString('es-PE', {
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
                  {hoveredFlight.cantidadAsignada} / {hoveredFlight.capacidad}
                </span>
              </div>
              <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                <div
                  className="h-full transition-all"
                  style={{
                    width: `${(hoveredFlight.cantidadAsignada / hoveredFlight.capacidad) * 100}%`,
                    backgroundColor: hoveredFlight.pathColor,
                  }}
                />
              </div>
            </div>

            {/* Carga */}
            {hoveredFlight.carga.length > 0 && (
              <div>
                <p className="text-sm font-semibold mb-2">
                  Carga ({hoveredFlight.carga.length} {hoveredFlight.carga.length === 1 ? 'pedido' : 'pedidos'})
                </p>
                <div className="max-h-40 overflow-y-auto space-y-1">
                  {hoveredFlight.carga.map((item, idx) => (
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
            onMouseEnter={() => setHoveredFlight(flight)}
            onMouseLeave={() => setHoveredFlight(null)}
          />
        ))}

        {/* Renderizar aeropuertos */}
        <AirportMarkers
          items={airports}
          activeId={activeAirportId}
          hoveredId={hoveredAirportId}
          baseColor={COLOR_NORMAL}
          activeColor={ACTIVE_COLOR}
          hoverColor={HOVER_COLOR}
          onHoverChange={setHoveredAirportId}
          onClick={(id) =>
            setActiveAirportId((prev) => (prev === id ? null : id))
          }
          iconSize={16}
        />
      </MainMap>
    </div>
  );
}