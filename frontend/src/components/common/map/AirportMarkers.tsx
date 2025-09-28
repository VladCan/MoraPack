import AirportMarker from "./AirportMarker";

export type AirportPoint = {
  id: string;
  name?: string;
  lon: number;
  lat: number;
  color?: string;
};

type AirportMarkersProps = {
  items: AirportPoint[];
  activeId?: string | null;
  onClick?: (id: string) => void;
  onHoverChange?: (id: string | null) => void;
  icon?: "airport" | "house";
  size?: number;
};

export default function AirportMarkers({
  items,
  activeId = null,
  onClick,
  onHoverChange,
  icon = "airport",
  size = 28,
}: AirportMarkersProps) {
  return (
    <>
      {items.map((a) => (
        <AirportMarker
          key={a.id}
          id={a.id}
          name={a.name}
          lon={a.lon}
          lat={a.lat}
          color={a.color}
          size={size}
          icon={icon}
          active={a.id === activeId}
          onClick={onClick}
          onHoverChange={onHoverChange}
        />
      ))}
    </>
  );
}
