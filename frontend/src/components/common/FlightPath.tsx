// src/components/FlightPath.tsx
import PlaneIcon from "@/assets/plane2.svg?react"; // Asegúrate de que el path sea correcto
import { Marker, Source, Layer } from "react-map-gl/maplibre";
import { useMemo } from "react";

// ==== Helpers matemáticos ====
const toRad = (d: number) => (d * Math.PI) / 180;
const toDeg = (r: number) => (r * 180) / Math.PI;
const ROTATION_OFFSET = -45;
function llToVec({ lat, lon }: { lat: number; lon: number }) {
  const φ = toRad(lat);
  const λ = toRad(lon);
  const x = Math.cos(φ) * Math.cos(λ);
  const y = Math.cos(φ) * Math.sin(λ);
  const z = Math.sin(φ);
  return [x, y, z] as const;
}

function vecToLl([x, y, z]: readonly [number, number, number]) {
  const hyp = Math.sqrt(x * x + y * y);
  const lat = toDeg(Math.atan2(z, hyp));
  const lon = toDeg(Math.atan2(y, x));
  return { lat, lon };
}

function slerpOnSphere(
  aLL: { lat: number; lon: number },
  bLL: { lat: number; lon: number },
  t: number
) {
  const a = llToVec(aLL);
  const b = llToVec(bLL);

  const dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const theta = Math.acos(Math.max(-1, Math.min(1, dot)));
  if (theta === 0) return aLL;

  const sinTheta = Math.sin(theta);
  const w1 = Math.sin((1 - t) * theta) / sinTheta;
  const w2 = Math.sin(t * theta) / sinTheta;

  const x = w1 * a[0] + w2 * b[0];
  const y = w1 * a[1] + w2 * b[1];
  const z = w1 * a[2] + w2 * b[2];

  return vecToLl([x, y, z]);
}

function bearing(
  a: { lat: number; lon: number },
  b: { lat: number; lon: number }
) {
  const φ1 = toRad(a.lat);
  const φ2 = toRad(b.lat);
  const λ1 = toRad(a.lon);
  const λ2 = toRad(b.lon);
  const y = Math.sin(λ2 - λ1) * Math.cos(φ2);
  const x =
    Math.cos(φ1) * Math.sin(φ2) -
    Math.sin(φ1) * Math.cos(φ2) * Math.cos(λ2 - λ1);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

function buildGreatCirclePath(
  a: { lat: number; lon: number },
  b: { lat: number; lon: number },
  steps = 256
) {
  const pts = Array.from({ length: steps + 1 }, (_, i) =>
    slerpOnSphere(a, b, i / steps)
  );

  // Detectar cruces de ±180° y partir la línea
  const segments: { lon: number; lat: number }[][] = [[]];
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    const prev = segments[segments.length - 1].at(-1);

    if (
      prev &&
      Math.abs(p.lon - prev.lon) > 180 // salto sospechoso → cruce del meridiano
    ) {
      // Inicia un nuevo segmento
      segments.push([]);
    }
    segments[segments.length - 1].push(p);
  }

  return {
    type: "FeatureCollection" as const,
    features: segments.map((seg) => ({
      type: "Feature" as const,
      geometry: {
        type: "LineString" as const,
        coordinates: seg.map((p) => [p.lon, p.lat]),
      },
      properties: {},
    })),
  };
}

// ==== Componente ====
interface FlightPathProps {
  id: string;
  origin: { lat: number; lon: number };
  dest: { lat: number; lon: number };
  progress: number; // 0..1
  pathColor?: string;
  planeColor?: string;
}

export default function FlightPath({
  id,
  origin,
  dest,
  progress,
  pathColor = "#7f7f7f7f",
  planeColor = "#2563eb",
}: FlightPathProps) {
  const pathGeoJSON = useMemo(
    () => buildGreatCirclePath(origin, dest, 512),
    [origin, dest]
  );
  const planePos = useMemo(
    () => slerpOnSphere(origin, dest, progress),
    [origin, dest, progress]
  );
  const planeNext = useMemo(
    () => slerpOnSphere(origin, dest, Math.min(1, progress + 0.002)),
    [origin, dest, progress]
  );
  const hdg = useMemo(
    () => bearing(planePos, planeNext),
    [planePos, planeNext]
  );

  return (
    <>
      {/* Línea de vuelo */}
      <Source id={`route-${id}`} type="geojson" data={pathGeoJSON}>
        <Layer
          id={`route-line-${id}`}
          type="line"
          paint={{
            "line-color": pathColor,
            "line-width": 3,
          }}
        />
      </Source>

      {/* Avioncito minimalista */}
      <Marker longitude={planePos.lon} latitude={planePos.lat} anchor="center">
        <PlaneIcon
            className="w-5 h-5 transition-transform duration-300"
            style={{
                color: planeColor, // ← Aplica directamente el color
                transform: `rotate(${hdg + ROTATION_OFFSET}deg)`,
                transformOrigin: "center center",
            }}
        />
      </Marker>
    </>
  );
}
