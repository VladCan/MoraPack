// src/pages/Colapso.tsx
"use client";
import MainMap from "@/components/common/MainMap";
import AirportMarkers, { type AirportPoint } from "@/components/common/map/AirportMarkers";
import { useAirports } from "@/hooks/useAirports";
import { useRunSSE } from "@/hooks/useRunSSE";
import { useRunSession } from "@/lib/runSession";
import { useEffect, useMemo, useState } from "react";
import { z } from "zod";

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

export default function Colapso() {

  /**Esta parte de abajo es para mostrar los almacenes (nada nuevo hasta acá)**/
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
  /**Esta parte de arriba es para mostrar los almacenes (nada nuevo hasta acá)**/

  return (
    <div className="min-h-screen bg-neutral-50">
      <MainMap>
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
