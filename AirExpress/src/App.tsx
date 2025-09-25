import "maplibre-gl/dist/maplibre-gl.css";
import maplibregl from "maplibre-gl";
import Map, { NavigationControl, Marker, Source, Layer } from "react-map-gl/maplibre";
import { useEffect, useMemo, useRef, useState } from "react";

// ==== Config ====
const MAP_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

// Jorge Chávez (LIM)
const ORIGIN = { lon: -77.1143, lat: -12.0219 };
// Adolfo Suárez Madrid-Barajas (MAD)
const DEST = { lon: -3.5626, lat: 40.4719 };

// ===== Math helpers (gran círculo con SLERP) =====
const toRad = (d: number) => (d * Math.PI) / 180;
const toDeg = (r: number) => (r * 180) / Math.PI;

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

function slerpOnSphere(aLL: { lat: number; lon: number }, bLL: { lat: number; lon: number }, t: number) {
  const a = llToVec(aLL);
  const b = llToVec(bLL);

  // ángulo entre a y b
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

// bearing para rotar el ✈️ hacia el rumbo
function bearing(a: { lat: number; lon: number }, b: { lat: number; lon: number }) {
  const φ1 = toRad(a.lat);
  const φ2 = toRad(b.lat);
  const λ1 = toRad(a.lon);
  const λ2 = toRad(b.lon);
  const y = Math.sin(λ2 - λ1) * Math.cos(φ2);
  const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(λ2 - λ1);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

// discretiza la ruta en N puntos
function buildGreatCirclePath(a: { lat: number; lon: number }, b: { lat: number; lon: number }, steps = 256) {
  const pts = Array.from({ length: steps + 1 }, (_, i) => slerpOnSphere(a, b, i / steps));
  return {
    type: "FeatureCollection" as const,
    features: [
      {
        type: "Feature" as const,
        geometry: {
          type: "LineString" as const,
          coordinates: pts.map((p) => [p.lon, p.lat]),
        },
        properties: {},
      },
    ],
  };
}

// ====== App ======
export default function App() {
  
  const [projection, setProjection] = useState<"mercator" | "globe">("mercator");
  const [progress, setProgress] = useState(0); // 0..1 a lo largo de la ruta
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(0.15); // fracción por segundo (0.15 = ~6.6s de viaje)
  const rafRef = useRef<number | null>(null);
  const lastTs = useRef<number | null>(null);

  const pathGeoJSON = useMemo(() => buildGreatCirclePath(ORIGIN, DEST, 512), []);
  const planePos = useMemo(() => slerpOnSphere(ORIGIN, DEST, progress), [progress]);
  const planeNext = useMemo(
    () => slerpOnSphere(ORIGIN, DEST, Math.min(1, progress + 0.002)),
    [progress]
  );
  const hdg = useMemo(() => bearing(planePos, planeNext), [planePos, planeNext]);

  useEffect(() => {
    if (!playing) {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
      lastTs.current = null;
      return;
    }
    const tick = (ts: number) => {
      if (lastTs.current == null) lastTs.current = ts;
      const dt = (ts - lastTs.current) / 1000; // seconds
      lastTs.current = ts;

      setProgress((p) => {
        const np = p + speed * dt;
        if (np >= 1) {
          // llegó
          setPlaying(false);
          return 1;
        }
        return np;
      });

      rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
      lastTs.current = null;
    };
  }, [playing, speed]);

  return (
    <div className="w-full h-screen">
      {/* Toolbar */}
      <div
        style={{
          position: "absolute",
          zIndex: 10,
          top: 12,
          left: 12,
          background: "white",
          borderRadius: 12,
          padding: "10px 12px",
          boxShadow: "0 6px 18px rgba(0,0,0,.12)",
          display: "flex",
          gap: 8,
          alignItems: "center",
          fontFamily: "system-ui, sans-serif",
        }}
      >
        <button onClick={() => setProjection((p) => (p === "mercator" ? "globe" : "mercator"))}>
          {projection === "mercator" ? "🌍 Globo" : "🗺️ Plano"}
        </button>
        <button onClick={() => setPlaying((v) => !v)}>{playing ? "⏸️ Pausa" : "▶️ Iniciar"}</button>
        <button
          onClick={() => {
            setPlaying(false);
            setProgress(0);
          }}
        >
          🔁 Reiniciar
        </button>
        <label style={{ display: "flex", alignItems: "center", gap: 6 }}>
          Velocidad
          <input
            type="range"
            min={0.05}
            max={0.5}
            step={0.01}
            value={speed}
            onChange={(e) => setSpeed(parseFloat(e.target.value))}
          />
        </label>
        <div style={{ fontSize: 12, opacity: 0.7 }}>
          Progreso: {(progress * 100).toFixed(0)}%
        </div>
      </div>

      <Map
        initialViewState={{
          longitude: (-77.1143 + -3.5626) / 2,
          latitude: (-12.0219 + 40.4719) / 2,
          zoom: 2.1,
        }}
        dragRotate
        pitch={30}          // un poco de inclinación
        bearing={0}
        projection={projection}
        mapStyle={MAP_STYLE}
        style={{ width: "100%", height: "100%" }}
      >
        <NavigationControl position="top-right" />

        {/* Línea de ruta (gran círculo) */}
        <Source id="route" type="geojson" data={pathGeoJSON}>
          <Layer
            id="route-line"
            type="line"
            paint={{
              "line-color": "#2563eb",
              "line-width": 3,
            }}
          />
        </Source>

        {/* Avioncito */}
        <Marker longitude={planePos.lon} latitude={planePos.lat} anchor="center">
          <div
            style={{
              fontSize: 22,
              transform: `rotate(${hdg}deg)`,
              transformOrigin: "center center",
              filter: "drop-shadow(0 2px 2px rgba(0,0,0,.25))",
            }}
            aria-label="airplane"
            title="Avión Lima → Madrid"
          >
            ✈️
          </div>
        </Marker>
      </Map>
    </div>
  );
}
