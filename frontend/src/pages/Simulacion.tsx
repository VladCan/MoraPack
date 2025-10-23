// src/pages/Simulacion.tsx
"use client";
import MainMap from "@/components/common/MainMap";
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
  
  //El runId únicamente debería de existir luego de darle a "Aplicar"
  //al ToolsPanel
  const {runId} = useRunSession();

  //Conectamos al SSE si hay runId
  const { connected, simNowUtc, windows, finished, error } = 
    useRunSSE(runId ?? undefined);

  //Acá debería de ir la lógica de calcular el progress, filtrar, etc.

  //Por ahora estoy colocando unos logs para verificar que llegue todo

  useEffect(() => {
    console.log("[SSE] connected:", connected, "error:", error);
  }, [connected, error]);

  useEffect(() => {
    if (simNowUtc) console.log("[SSE] TICK simNowUtc:", simNowUtc);
  }, [simNowUtc]);

  useEffect(() => {
    if (windows.length > 0) {
      const last = windows[windows.length - 1];
      console.log("[SSE] WINDOW", last.index, last.startUtc, "→", last.endUtc);
    }
  }, [windows]);

  useEffect(() => {
    if (finished) console.log("[SSE] FINISHED:", finished.reason);
  }, [finished]);

  return (
    <div className="min-h-screen bg-neutral-50">
      <MainMap />
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