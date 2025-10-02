// src/components/common/map/AirportMarkers.tsx
import AirportsLayer, { type AirportPoint } from "./AirportsLayer";

type AirportMarkersProps = {
  items: AirportPoint[];
  activeId?: string | null;
  hoveredId?: string | null;
  onClick?: (id: string) => void;
  onHoverChange?: (id: string | null) => void;

  // Estilo
  showLabels?: boolean;
  baseColor?: string;
  activeColor?: string;
  hoverColor?: string;
  iconSize?: number; // 👈 px (default 16)

  // Compat (ya no se usan, los dejamos para no romper imports antiguos)
  icon?: "airport" | "house";
  size?: number;
};

export type { AirportPoint };

export default function AirportMarkers({
  items,
  activeId = null,
  hoveredId = null,
  onClick,
  onHoverChange,
  baseColor = "#0ea5e9",
  activeColor = "#005097",
  hoverColor = "#ef4444",
  iconSize = 16, // 👈 más pequeño por defecto
}: AirportMarkersProps) {
  return (
    <AirportsLayer
      airports={items}
      activeId={activeId}
      hoveredId={hoveredId}
      onClick={onClick}
      onHoverChange={onHoverChange}
      baseColor={baseColor}
      activeColor={activeColor}
      hoverColor={hoverColor}
      iconSize={iconSize}
    />
  );
}
