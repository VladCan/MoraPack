// src/app/Simulacion.tsx
"use client";
import { useEffect, useState } from "react";
import FlightPath from "@/components/common/FlightPath";
import MainMap from "@/components/common/MainMap";

// Tipado simple para vuelos
interface Flight {
  id: string;
  origin: { lat: number; lon: number };
  dest: { lat: number; lon: number };
  pathColor: string;
  planeColor: string;
  initialProgress: number;
}

const flights: Flight[] = [
  {
    id: "vuelo1",
    origin: { lat: 25.7959, lon: -80.2871 }, // Miami
    dest: { lat: -12.0219, lon: -77.1143 }, // Lima
    pathColor: "#f472b6",
    planeColor: "#005097",
    initialProgress: 0.3,
  },
  {
    id: "vuelo2",
    origin: { lat: 40.6413, lon: -73.7781 }, // JFK
    dest: { lat: 51.47, lon: -0.4543 }, // Heathrow
    pathColor: "#60a5fa",
    planeColor: "#005097",
    initialProgress: 0.5,
  },
  {
    id: "vuelo3",
    origin: { lat: 35.5494, lon: 139.7798 }, // Haneda
    dest: { lat: 33.9416, lon: -118.4085 }, // LAX
    pathColor: "#34d399",
    planeColor: "#005097",
    initialProgress: 0.6,
  },
  // ...agrega el resto de tus vuelos aquí igual que antes
];

export default function Simulacion() {
  // Estado para manejar progreso de cada vuelo
  const [progressMap, setProgressMap] = useState<Record<string, number>>(
    Object.fromEntries(flights.map((f) => [f.id, f.initialProgress]))
  );

  useEffect(() => {
    const interval = setInterval(() => {
      setProgressMap((prev) => {
        const updated: Record<string, number> = {};
        for (const key in prev) {
          let next = prev[key] + 0.002; // velocidad
          if (next > 1) next = 0; // reset al inicio
          updated[key] = next;
        }
        return updated;
      });
    }, 100); // cada 100ms
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="min-h-screen bg-neutral-50">
      <MainMap>
        {flights.map((flight) => (
          <FlightPath
            key={flight.id}
            id={flight.id}
            origin={flight.origin}
            dest={flight.dest}
            progress={progressMap[flight.id] ?? 0}
            pathColor={flight.pathColor}
            planeColor={flight.planeColor}
          />
        ))}
      </MainMap>
    </div>
  );
}
