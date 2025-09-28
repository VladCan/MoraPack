// src/pages/Simulacion.tsx
"use client";
import { useMemo, useState } from "react";
import FlightPath from "@/components/common/FlightPath";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, {
  type AirportPoint,
} from "@/components/common/map/AirportMarkers";
import { useAirports } from "@/hooks/useAirports";
import { useFlightsSSE } from "@/hooks/useFlightsSSE";

const COLOR_SEDE = "#005097";
const COLOR_NORMAL = "#0ea5e9";
const HOVER_COLOR = "#ef4444";
const ACTIVE_COLOR = "#005097";

export default function Simulacion() {
  // Aeropuertos (como ya lo tenías)
  const { data: airportsDto } = useAirports();
  const [hoveredAirportId, setHoveredAirportId] = useState<string | null>(null);
  const [activeAirportId, setActiveAirportId] = useState<string | null>(null);

  const airports: AirportPoint[] = useMemo(() => {
    if (!airportsDto) return [];
    return airportsDto
      .filter((a) => typeof a.lat === "number" && typeof a.lon === "number")
      .map((a) => ({
        id: a.codigo,
        name: `${a.ciudad ?? a.codigo} (${a.codigo})`,
        lon: a.lon!,
        lat: a.lat!,
        color: a.sede ? COLOR_SEDE : COLOR_NORMAL,
      }));
  }, [airportsDto]);

  const displayAirports: AirportPoint[] = useMemo(() => {
    return airports.map((a) => ({
      ...a,
      color:
        a.id === activeAirportId
          ? ACTIVE_COLOR
          : a.id === hoveredAirportId
          ? HOVER_COLOR
          : a.color,
    }));
  }, [airports, hoveredAirportId, activeAirportId]);

  // 🔴 VUELOS EN VIVO por SSE
  const { data: liveFlights } = useFlightsSSE("/vuelos/live?limit=50");

  // Mapear DTO → props de FlightPath (usa progress del backend)
  const flightPaths = useMemo(() => {
    const arr = (liveFlights ?? []).slice(0, 50); // doble seguro en el front
    return arr.map((f) => ({
      id: f.id,
      origin: { lat: f.originLat, lon: f.originLon },
      dest: { lat: f.destLat, lon: f.destLon },
      progress: f.progress,
      pathColor: f.pathColor,
      planeColor: f.planeColor,
    }));
  }, [liveFlights]);

  return (
    <div className="min-h-screen bg-neutral-50">
      <MainMap>
        {/* Vuelos en vivo */}
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

        {/* Aeropuertos */}
        <AirportMarkers
          items={displayAirports}
          activeId={activeAirportId}
          icon="airport"
          size={20}
          onHoverChange={(id) => setHoveredAirportId(id)}
          onClick={(id) =>
            setActiveAirportId((prev) => (prev === id ? null : id))
          }
        />
      </MainMap>
    </div>
  );
}
