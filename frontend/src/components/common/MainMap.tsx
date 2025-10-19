// src/components/common/MainMap.tsx
import "maplibre-gl/dist/maplibre-gl.css";
import Map from "react-map-gl/maplibre";
import { useMemo, useState, useEffect, useCallback } from "react";
import type { ReactNode } from "react";
import { Map as MapIcon, Earth as EarthIcon } from "lucide-react";
import { useTheme } from "@/components/ui/theme-provider";
import type { Map as MapLibreMap } from "maplibre-gl";

const MAP_STYLE_LIGHT = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";
// Dark con labels (simple y limpio)
const MAP_STYLE_DARK  = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";

interface MainMapProps { children?: ReactNode; }
type Proj = "mercator" | "globe";

export default function MainMap({ children }: MainMapProps) {
  const [projection, setProjection] = useState<Proj>("mercator");
  const isMobile = useMediaQuery("(max-width: 768px)");
  const { resolvedTheme } = useTheme();

  const mapStyle = useMemo(
    () => (resolvedTheme === "dark" ? MAP_STYLE_DARK : MAP_STYLE_LIGHT),
    [resolvedTheme]
  );

  const minZoom = useMemo(() => {
    if (projection === "mercator") return isMobile ? 0.5 : 1.6;
    return isMobile ? 1.4 : 2.4;
  }, [isMobile, projection]);

  const maxZoom = useMemo(() => (isMobile ? 18 : 22), [isMobile]);

  // color fuera del globo (sin cambios)
  const globeBg = resolvedTheme === "dark" ? "#0b1018" : "#eef2f6";

  // Ajuste mínimo de color SOLO en dark: mar y tierra
  const handleLoad = useCallback(({ target }: { target: MapLibreMap }) => {
    if (resolvedTheme !== "dark") return;

    const setPaint = (id: string, prop: string, value: unknown) => {
      if (target.getLayer(id)) target.setPaintProperty(id, prop, value);
    };

    // Tonos sugeridos (puedes cambiarlos si quieres):
    const LAND  = "#0b0f14"; // grafito profundo para continentes
    const WATER = "#0f1722"; // azul petróleo para océanos

    // Agua (CARTO usa "water")
    setPaint("water", "fill-color", WATER);
    setPaint("water", "fill-opacity", 0.78);

    // Tierra (dependiendo del estilo puede haber "land", "landcover" o "landuse")
    setPaint("land", "background-color", LAND);   // algunas versiones usan background para land
    setPaint("landcover", "fill-color", LAND);
    setPaint("landuse", "fill-color", LAND);

    // Fondo general (por si el estilo define un background base)
    setPaint("background", "background-color", LAND);
  }, [resolvedTheme]);

  return (
    <div
      className="w-full h-screen relative"
      style={projection === "globe" ? { backgroundColor: globeBg } : undefined}
    >
      <button
        onClick={() => setProjection(p => (p === "mercator" ? "globe" : "mercator"))}
        className="absolute bottom-4 left-4 z-10 px-3 py-3 rounded-full shadow-lg ring-1 ring-black/10
                   bg-white/20 dark:bg-neutral-900/30 backdrop-blur"
        aria-label="Cambiar proyección"
        title={projection === "mercator" ? "Cambiar a globo" : "Cambiar a Mercator"}
      >
        {projection === "mercator"
          ? <EarthIcon className="text-primary" />
          : <MapIcon className="text-primary" />}
      </button>

      <Map
        initialViewState={{ longitude: -50, latitude: 0, zoom: 2.1 }}
        dragRotate
        bearing={0}
        projection={projection}
        mapStyle={mapStyle}
        style={{ width: "100%", height: "100%" }}
        minZoom={minZoom}
        maxZoom={maxZoom}
        onLoad={handleLoad}   // ← sólo colores de mar/tierra en dark
      >
        {children}
      </Map>
    </div>
  );
}

/** Hook tipado y sencillo para media queries (sin any) */
function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    if (typeof window === "undefined" || !("matchMedia" in window)) return;
    const mql = window.matchMedia(query);

    setMatches(mql.matches);

    const handler = (e: MediaQueryListEvent | MediaQueryList) => {
      setMatches("matches" in e ? e.matches : (e as MediaQueryList).matches);
    };

    if (mql.addEventListener) mql.addEventListener("change", handler as (e: MediaQueryListEvent)=>void);
    else (mql as MediaQueryList).addEventListener?.("change", handler);

    return () => {
      if (mql.removeEventListener) mql.removeEventListener("change", handler as (e: MediaQueryListEvent)=>void);
      else (mql as MediaQueryList).removeEventListener?.("change", handler);
    };
  }, [query]);

  return matches;
}
