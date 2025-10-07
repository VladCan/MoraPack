// src/components/common/MainMap.tsx
import "maplibre-gl/dist/maplibre-gl.css";
import Map from "react-map-gl/maplibre";
import { useMemo, useState, useEffect } from "react";
import type { ReactNode } from "react";
import { Map as MapIcon, Earth as EarthIcon } from "lucide-react";

const MAP_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

interface MainMapProps { children?: ReactNode; }
type Proj = "mercator" | "globe";

export default function MainMap({ children }: MainMapProps) {
  const [projection, setProjection] = useState<Proj>("mercator");
  const isMobile = useMediaQuery("(max-width: 768px)");

  // define min/max por dispositivo y proyección
  const minZoom = useMemo(() => {
    if (projection === "mercator") return isMobile ? 0.5 : 1.6;
    return isMobile ? 1.4 : 2.4; // globe
  }, [isMobile, projection]);

  const maxZoom = useMemo(() => (isMobile ? 18 : 22), [isMobile]);

  return (
    <div className="w-full h-screen relative">
      <button
        onClick={() => setProjection(p => (p === "mercator" ? "globe" : "mercator"))}
        className="absolute bottom-4 left-4 z-10 px-3 py-3 rounded-full shadow-lg ring-1 ring-black/10 bg-white/20 backdrop-blur"
        aria-label="Cambiar proyección"
      >
        {projection === "mercator" ? <EarthIcon className="text-primary" /> : <MapIcon className="text-primary" />}
      </button>

      <Map
        initialViewState={{ longitude: -50, latitude: 0, zoom: 2.1 }}
        dragRotate
        bearing={0}
        projection={projection}
        mapStyle={MAP_STYLE}
        style={{ width: "100%", height: "100%" }}
        minZoom={minZoom}
        maxZoom={maxZoom}
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

    // set inicial
    setMatches(mql.matches);

    // listener con tipado correcto y fallback para Safari antiguo
    const handler = (e: MediaQueryListEvent | MediaQueryList) => {
      setMatches('matches' in e ? e.matches : (e as MediaQueryList).matches);
    };

    if (mql.addEventListener) mql.addEventListener("change", handler as (e: MediaQueryListEvent)=>void);
    else (mql as MediaQueryList).addEventListener?.("change",handler); // opcional: soporte legacy

    return () => {
      if (mql.removeEventListener) mql.removeEventListener("change", handler as (e: MediaQueryListEvent)=>void);
      else (mql as MediaQueryList).removeEventListener?.("change",handler);
    };
  }, [query]);

  return matches;
}
