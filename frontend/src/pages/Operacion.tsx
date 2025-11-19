"use client";
import { useMemo, useState, useRef } from "react";
import { z } from "zod";
import FlightPath from "@/components/common/FlightPath";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import { useAirports } from "@/hooks/useAirports";
import { useFlightsSSE } from "@/hooks/useFlightsSSE";
import { useRunSession } from "@/lib/runSession";
import { useRunSSE } from "@/hooks/useRunSSE";

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

export default function Operacion() {
  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeAirportId, setActiveAirportId] = useState<string | null>(null);

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
  const { runId, vuelosCancelados } = useRunSession();
  const { simNowUtc, windows } = useRunSSE(runId || undefined);
  const { data: liveFlights } = useFlightsSSE("vuelos/live?limit=200");
  
  // Crear mapa de aeropuertos para calcular posiciones
  const airportsMap = useMemo(() => {
    const map = new Map<string, { lat: number; lon: number }>();
    airports.forEach(a => map.set(a.id, { lat: a.lat, lon: a.lon }));
    return map;
  }, [airports]);

  const flightFirstSeenRef = useRef<Map<string, number>>(new Map());

  const flightPaths = useMemo(() => {
    const now = simNowUtc ? new Date(simNowUtc).getTime() : Date.now();
    const allFlights: FlightDto[] = [];
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
            originLat: origen.lat,
            originLon: origen.lon,
            destLat: destino.lat,
            destLon: destino.lon,
            progress,
            pathColor,
            planeColor,
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
        allFlights.push(f);
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

    return allFlights.map((f) => ({
      id: f.id,
      origin: { lat: f.originLat, lon: f.originLon },
      dest: { lat: f.destLat, lon: f.destLon },
      progress: f.progress,
      pathColor: f.pathColor,
      planeColor: f.planeColor,
    }));
  }, [liveFlights, vuelosCancelados, windows, simNowUtc, airportsMap]);

  return (
    <div className="min-h-screen bg-neutral-50">
      <MainMap>
        {flightPaths.map((fp) => (
          <FlightPath
            key={fp.id}
            id={fp.id}
            origin={fp.origin}
            dest={fp.dest}
            progress={fp.progress}
            pathColor={fp.pathColor}
            planeColor={fp.planeColor}
          />
        ))}

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
