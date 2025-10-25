// src/pages/Simulacion.tsx
"use client";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import FlightPath from "@/components/common/FlightPath";
import { useAirports } from "@/hooks/useAirports";
import { useRunSSE } from "@/hooks/useRunSSE";
import { useRunSession } from "@/lib/runSession";
import { useEffect, useMemo, useState } from "react";
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
};

export default function Simulacion() {

  const { data: airportsDtoRaw } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeAirportId, setActiveAirportId] = useState<string | null>(null);

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
  const { runId, status } = useRunSession();
  const { simNowUtc, windows, connected } = useRunSSE(runId || undefined);

  // Logs para debug
  useEffect(() => {
    console.log("[Simulacion] Connected:", connected, "Status:", status);
  }, [connected, status]);

  useEffect(() => {
    if (windows.length > 0) {
      const last = windows[windows.length - 1];
      console.log(`[Simulacion] WINDOW #${last.index}: ${last.vuelos.length} vuelos, ${last.pedidos.length} pedidos`);
      
      // Log del primer vuelo para debug
      if (last.vuelos.length > 0) {
        const v = last.vuelos[0];
        console.log(`[Simulacion] Ejemplo vuelo:`, {
          id: v.id,
          origen: v.origen,
          destino: v.destino,
          salidaUtc: v.salidaUtc,
          llegadaUtc: v.llegadaUtc,
          capacidad: v.capacidad,
          cantidadAsignada: v.cantidadAsignada
        });
      }
    }
  }, [windows]);

  // Log del simNowUtc cuando cambia
  useEffect(() => {
    if (simNowUtc) {
      console.log(`[Simulacion] simNowUtc actualizado:`, simNowUtc);
    }
  }, [simNowUtc]);

  // Procesar vuelos para renderizar
  const flightsToRender = useMemo<FlightForRender[]>(() => {
    console.log(`[Simulacion] Recalculando vuelos... simNowUtc=${simNowUtc}, windows=${windows.length}`);
    
    if (!simNowUtc || windows.length === 0) {
      console.log(`[Simulacion] ❌ No renderizar: simNowUtc=${simNowUtc}, windows=${windows.length}`);
      return [];
    }

    const now = new Date(simNowUtc).getTime();
    console.log(`[Simulacion] Now timestamp: ${now} (${new Date(now).toISOString()})`);
    
    const allFlights: FlightForRender[] = [];

    // ⚠️ IMPORTANTE: Solo procesar la ventana más reciente para evitar duplicados
    const latestWindow = windows[windows.length - 1];
    console.log(`[Simulacion] Procesando ventana más reciente #${latestWindow.index} con ${latestWindow.vuelos.length} vuelos`);

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
          console.log(`[Simulacion] ⏭️  Vuelo ${vuelo.id} filtrado: now=${new Date(now).toISOString()}, salida=${vuelo.salidaUtc}, llegada=${vuelo.llegadaUtc}`);
          return;
        }
        
        console.log(`[Simulacion] ✈️  Vuelo ACTIVO: ${vuelo.id} (${vuelo.origen} → ${vuelo.destino})`);

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
        });
    });

    console.log(`[Simulacion] 🎯 Total vuelos a renderizar: ${allFlights.length}`);
    return allFlights;
  }, [windows, simNowUtc, airportsMap]);

  // Log cuando cambian los vuelos a renderizar
  useEffect(() => {
    console.log(`[Simulacion] 🛩️  flightsToRender actualizado: ${flightsToRender.length} vuelos`);
    if (flightsToRender.length > 0) {
      console.log(`[Simulacion] Primer vuelo:`, flightsToRender[0]);
    }
  }, [flightsToRender]);

  return (
    <div className="min-h-screen bg-neutral-50">
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