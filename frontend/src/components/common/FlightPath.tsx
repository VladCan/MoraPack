// src/components/common/FlightPath.tsx
import PlaneIcon from "@/assets/plane2.svg?react";
import { Marker, Source, Layer } from "react-map-gl/maplibre";
import { useMemo } from "react";
import { useTheme } from "@/components/ui/theme-provider"; 

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

  const segments: { lon: number; lat: number }[][] = [[]];
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    const prev = segments[segments.length - 1].at(-1);

    if (prev && Math.abs(p.lon - prev.lon) > 180) {
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
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
  onClick?: () => void;
}

export default function FlightPath({
  id,
  origin,
  dest,
  progress,
  pathColor,
  planeColor,
  onMouseEnter,
  onMouseLeave,
  onClick,
}: FlightPathProps) {
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === "dark";

  // —— SOLO colores/estilos:
  // Rutas: en dark un cian suave, en light mantenemos gris translúcido
  const pathColorFinal =
    pathColor ??
    (isDark ? "rgba(56, 189, 248, 0.55)" /* cyan-400/55 */ : "rgba(127,127,127,0.5)");

  // Avión: en dark un cian más vivo, en light tu azul original
  const planeColorFinal =
    planeColor ?? (isDark ? "#38bdf8" /* cyan-400 */ : "#2563eb" /* blue-600 */);

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
            "line-color": pathColorFinal,
            // <--- CAMBIO: Grosor duplicado en todos los niveles de zoom
            "line-width": [
              "interpolate",
              ["linear"],
              ["zoom"],
              0, 0.6,  // Antes 0.3
              3, 1.2,  // Antes 0.6
              6, 2.4,  // Antes 1.2
              10, 4    // Antes 2
            ],
            // ligera suavidad para que no “corte”
            "line-blur": isDark ? 0.3 : 0.15,
            // opacidad cómoda por tema
            "line-opacity": isDark ? 0.9 : 0.7,
          }}
        />
      </Source>

      {/* Avioncito */}
      <Marker longitude={planePos.lon} latitude={planePos.lat} anchor="center">
        <div
          onMouseEnter={onMouseEnter}
          onMouseLeave={onMouseLeave}
          onClick={onClick}
          className="cursor-pointer"
        >
          <PlaneIcon
            // <--- CAMBIO: Aumentado tamaño de w-3 h-3 (12px) a w-4 h-4 (16px)
            className="w-4 h-4 transition-transform duration-300"
            style={{
              color: planeColorFinal,
              transform: `rotate(${hdg + ROTATION_OFFSET}deg)`,
              transformOrigin: "center center",
            }}
          />
        </div>
      </Marker>
    </>
  );
}