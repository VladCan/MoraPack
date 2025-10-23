// src/pages/Simulacion.tsx
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

export default function Simulacion() {

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

  /*Acá viene lo nuevo:*/

  //El runId únicamente debería de existir luego de darle a "Aplicar"
  //al ToolsPanel
  const {runId} = useRunSession();

  //Conectamos al SSE si hay runId
  const { status, simNow, lastWindow } = useRunSession();

  //Acá debería de ir la lógica de calcular el progress, filtrar, etc.

  //Por ahora estoy colocando unos logs para verificar que llegue todo

  useEffect(() => {
    console.log("[CTX] status:", status, "simNow:", simNow, "lastWindow:", lastWindow);
  }, [status, simNow, lastWindow]);

  useEffect(() => {
    if (simNow) console.log("[CTX] TICK simNow:", simNow);
  }, [simNow]);

  useEffect(() => {
    if (lastWindow) {
      console.log("[CTX] WINDOW", lastWindow.index, lastWindow.startUtc, "->", lastWindow.endUtc);
    }
  }, [lastWindow]);

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

{/*
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
      </MainMap> */}