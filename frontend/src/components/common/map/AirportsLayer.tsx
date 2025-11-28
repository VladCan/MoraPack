// src/components/common/map/AirportsLayer.tsx
import { useMemo } from "react";
import { Marker } from "react-map-gl/maplibre";
import AirportIcon from "@/assets/airport3.svg?react";
import { z } from "zod";

export const AirportPointSchema = z.object({
  id: z.string(),
  name: z.string().optional(),
  lon: z.number(),
  lat: z.number(),
  color: z.string().optional(),
  isSede: z.boolean().optional(), // 👈 nuevo
});
export type AirportPoint = z.infer<typeof AirportPointSchema>;

type AirportsLayerProps = {
  airports: AirportPoint[];
  activeId?: string | null;
  hoveredId?: string | null;
  baseColor?: string;
  activeColor?: string;
  hoverColor?: string;
  iconSize?: number; // px (default 16)
  /** etiqueta solo en hover/activo */
  showLabelsOnHighlightOnly?: boolean;
  onClick?: (id: string) => void;
  onHoverChange?: (id: string | null) => void;
};

export default function AirportsLayer({
  airports,
  activeId = null,
  hoveredId = null,
  baseColor = "#38bdf8",
  activeColor = "#005097",
  hoverColor = "#ef4444",
  iconSize = 16,
  showLabelsOnHighlightOnly = true,
  onClick,
  onHoverChange,
}: AirportsLayerProps) {
  const safeAirports = useMemo(() => {
    const list: AirportPoint[] = [];
    for (const a of airports) {
      const p = AirportPointSchema.safeParse(a);
      if (p.success) list.push(p.data);
    }
    return list;
  }, [airports]);

  return (
    <>
      {safeAirports.map((a) => {
        const isActive = a.id === activeId;
        const isHovered = a.id === hoveredId;
        const isSede = !!a.isSede;

        const color = isActive
          ? activeColor
          : isHovered
          ? hoverColor
          : a.color ?? baseColor;

        const scale = isActive || isHovered ? 1.12 : 1;
        const ringSize = Math.max(
          12,
          Math.round(iconSize * (isActive ? 1.25 : 1.18))
        );
        const ringWidth = isActive ? 2 : 1; // borde más fino
        const badgeSize = Math.max(5, Math.round(iconSize * 0.35)); // badge más pequeño

        const showLabel =
          !!a.name && (!showLabelsOnHighlightOnly || isActive || isHovered);

        return (
          <Marker
            key={a.id}
            longitude={a.lon}
            latitude={a.lat}
            anchor="center"
            pitchAlignment="auto"
            rotationAlignment="map"
          >
            <button
              type="button"
              aria-label={a.name ?? `Airport ${a.id}`}
              title={a.name ?? a.id}
              onClick={() => onClick?.(a.id)}
              onMouseEnter={() => onHoverChange?.(a.id)}
              onMouseLeave={() => onHoverChange?.(null)}
              className="relative grid place-items-center"
              style={{
                background: "transparent",
                border: "none",
                padding: 0,
                cursor: "pointer",
                transform: `scale(${scale})`,
                transition: "transform 120ms ease",
                filter: "drop-shadow(0 0 2px rgba(0,0,0,.20))",
              }}
            >
              {isSede && (
                <span
                  aria-hidden
                  className="absolute rounded-full"
                  style={{
                    width: ringSize,
                    height: ringSize,
                    // borde sutil; glow solo cuando está activo
                    boxShadow: isActive
                      ? `0 0 0 ${ringWidth}px ${activeColor}, 0 0 6px 1px ${activeColor}33`
                      : `0 0 0 ${ringWidth}px ${activeColor}`,
                  }}
                />
              )}

              <AirportIcon
                style={{ color, width: iconSize, height: iconSize }}
              />

              {/* Badge para SEDE (esquina inferior-derecha) */}
              {isSede && (
                <span
                  aria-hidden
                  className="absolute rounded-full"
                  style={{
                    width: badgeSize,
                    height: badgeSize,
                    right: -Math.round(badgeSize * 0.25),
                    bottom: -Math.round(badgeSize * 0.25),
                    background: activeColor,
                    border: "2px solid white",
                  }}
                />
              )}

              {/* Etiqueta solo en hover/activo */}
              {showLabel && (
                <span
                  className="absolute left-1/2 top-full -translate-x-1/2 mt-1 px-2 py-0.5 rounded bg-white/90 text-[11px] leading-none text-slate-800 shadow"
                  style={{ whiteSpace: "nowrap" }}
                >
                  {a.name}
                </span>
              )}
            </button>
          </Marker>
        );
      })}
    </>
  );
}
