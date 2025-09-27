// src/components/common/MainMap.tsx
import "maplibre-gl/dist/maplibre-gl.css";
import Map, { NavigationControl } from "react-map-gl/maplibre";
import { useState } from "react";
import type { ReactNode } from "react";


const MAP_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

interface MainMapProps {
  children?: ReactNode;
}

export default function MainMap({ children }: MainMapProps) {
  const [projection, setProjection] = useState<"mercator" | "globe">("mercator");

  return (
    <div className="w-full h-screen relative">
      {/* Botón flotante para cambiar proyección */}
      <button
        onClick={() =>
          setProjection((p) => (p === "mercator" ? "globe" : "mercator"))
        }
        className="absolute bottom-4 left-4 z-10 px-4 py-2 rounded-xl shadow-lg 
                   bg-white text-gray-700 font-medium hover:bg-gray-100 
                   transition-colors border border-gray-200"
      >
        {projection === "mercator" ? "🌍 Globo" : "🗺️ Plano"}
      </button>

      <Map
        initialViewState={{
          longitude: -50,
          latitude: 0,
          zoom: 2.1,
        }}
        dragRotate
        bearing={0}
        projection={projection}
        mapStyle={MAP_STYLE}
        style={{ width: "100%", height: "100%" }}
        minZoom={projection === "mercator" ? 1.6 : 2.4}
      >
        <NavigationControl position="top-right" />
        {children}
      </Map>
    </div>
  );
}
