// src/components/common/map/AirportMarker.tsx
import { Marker } from "react-map-gl/maplibre";
import { useMemo, useState } from "react";

type IconKind = "airport" | "house";
type MarkerVariant = "circle" | "pin"; // por si luego quieres una “lágrima”

export type AirportMarkerProps = {
  id: string;
  name?: string;
  lon: number;
  lat: number;
  color?: string;       // color principal
  size?: number;        // alto del SVG (px) — default 20
  icon?: IconKind;      // “airport” o “house”
  variant?: MarkerVariant;
  compact?: boolean;    // halo sutil
  active?: boolean;
  onClick?: (id: string) => void;
  onHoverChange?: (id: string | null) => void;
};

export default function AirportMarker({
  id,
  name,
  lon,
  lat,
  color = "#0ea5e9",
  size = 20,
  icon = "airport",
  variant = "circle",
  compact = true,
  active = false,
  onClick,
  onHoverChange,
}: AirportMarkerProps) {
  const [hovered, setHovered] = useState(false);

  // escalado y halo
  const { scale, haloOpacity, haloBlur } = useMemo(() => {
    const s = hovered || active ? 1.12 : 1.0;
    const h = active ? 0.35 : hovered ? 0.22 : 0.10;
    const b = compact ? 4 : 6;
    return { scale: s, haloOpacity: h, haloBlur: b };
  }, [hovered, active, compact]);

  // stroke según tamaño para que se vea nítido
  const strokeW = Math.max(1, Math.round(size / 14));
  const innerR  = 10; // base del viewBox (24)
  const w = size;
  const h = variant === "pin" ? size * 1.15 : size; // pin un poco más alto

  const IconPath = useMemo(() => {
    if (icon === "house") {
      return (
        <>
          <path d="M12 7 L5 12 V20 H10 V15 H14 V20 H19 V12 Z" fill={color} />
          <path d="M4 12 L12 6 L20 12" fill="none" stroke={color} strokeWidth={strokeW} strokeLinejoin="round"/>
        </>
      );
    }
    // Avión relleno minimal centrado (mejor legibilidad a 16–22px)
    return (
      <>
        {/* fuselaje */}
        <path d="M4 12 H20" fill="none" stroke={color} strokeWidth={strokeW} strokeLinecap="round"/>
        {/* alas y cola (rellenas) */}
        <path d="M8 9 L12 12 L8 15 Z" fill={color}/>
        <path d="M12 8 L14 12 L12 16 Z" fill={color}/>
      </>
    );
  }, [icon, color, strokeW]);

  // base del marker (anillo + fondo)
  const BaseShape = useMemo(() => {
    if (variant === "pin") {
      // “lágrima” simple con anillo
      return (
        <>
          <path d="M12 2 C7.6 2 4 5.4 4 9.7 C4 14.6 10.2 20.3 11.4 21.4 C11.8 21.8 12.2 21.8 12.6 21.4 C13.8 20.3 20 14.6 20 9.7 C20 5.4 16.4 2 12 2 Z"
                fill="#fff" />
          <path d="M12 2 C7.6 2 4 5.4 4 9.7 C4 14.6 10.2 20.3 11.4 21.4 C11.8 21.8 12.2 21.8 12.6 21.4 C13.8 20.3 20 14.6 20 9.7 C20 5.4 16.4 2 12 2 Z"
                fill="none" stroke={color} strokeWidth={strokeW}/>
        </>
      );
    }
    // círculo compacto
    return (
      <>
        <circle cx="12" cy="12" r={innerR} fill="#ffffff"/>
        <circle cx="12" cy="12" r={innerR} fill="none" stroke={color} strokeWidth={strokeW}/>
      </>
    );
  }, [variant, color, strokeW]);

  return (
    <Marker
      longitude={lon}
      latitude={lat}
      anchor="bottom"
      pitchAlignment="auto"
      rotationAlignment="map"
    >
      <button
        aria-label={name ?? `Marker ${id}`}
        title={name ?? id}
        onClick={() => onClick?.(id)}
        onMouseEnter={() => { setHovered(true); onHoverChange?.(id); }}
        onMouseLeave={() => { setHovered(false); onHoverChange?.(null); }}
        style={{
          transform: `translateY(${variant === "pin" ? 1 : 0}px) scale(${scale})`,
          transition: "transform 120ms ease",
          background: "transparent",
          border: "none",
          padding: 0,
          cursor: "pointer",
          position: "relative",
          // sombra sutil para legibilidad sin “bruma” excesiva
          filter: compact ? "drop-shadow(0 0 2px rgba(0,0,0,.20))" : undefined,
        }}
      >
        {/* halo muy sutil */}
        <span
          aria-hidden
          style={{
            position: "absolute",
            inset: 0,
            filter: `blur(${haloBlur}px)`,
            opacity: haloOpacity,
            background: color,
            borderRadius: 9999,
            transform: "translateY(-2px)",
          }}
        />
        <svg
          width={w}
          height={h}
          viewBox="0 0 24 24"
          role="img"
          aria-hidden="true"
          style={{ display: "block", pointerEvents: "none" }}
          shapeRendering="geometricPrecision"
        >
          {BaseShape}
          {IconPath}
        </svg>
      </button>
    </Marker>
  );
}
