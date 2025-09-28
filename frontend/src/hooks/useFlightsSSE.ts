// src/hooks/useFlightsSSE.ts
import { useEffect, useMemo, useState } from "react";
import type { FlightLiveDTO } from "@/types/api";

/**
 * Suscribe a vuelos en vivo via SSE.
 */
export function useFlightsSSE(endpoint: string = "/vuelos/live?limit=50") {
  const [data, setData] = useState<FlightLiveDTO[]>([]);
  const [connected, setConnected] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Construye URL absoluta de forma segura (evita //)
  const fullUrl = useMemo(() => {
    const base = import.meta.env.VITE_API_BASE_URL as string | undefined;
    try {
      return base ? new URL(endpoint, base).toString() : endpoint;
    } catch {
      // Si endpoint ya es absoluta o base es inválida, usa tal cual
      return endpoint;
    }
  }, [endpoint]);

  useEffect(() => {
    const es = new EventSource(fullUrl, { withCredentials: false });
    let alive = true;

    es.onopen = () => {
      if (!alive) return;
      setConnected(true);
      setError(null);
    };

    es.onmessage = (ev: MessageEvent<string>) => {
      try {
        const arr: unknown = JSON.parse(ev.data);
        if (!alive) return;
        if (Array.isArray(arr)) {
          // Validación mínima de shape
          // (si quieres, puedes filtrar solo objetos con id/progress)
          setData(arr as FlightLiveDTO[]);
        }
      } catch {
        // ignora líneas no JSON (comentarios SSE, heartbeats, etc.)
      }
    };

    es.onerror = () => {
      if (!alive) return;
      setConnected(false);
      setError("SSE desconectado");
      // EventSource reintenta solo; no cerramos aquí.
    };

    return () => {
      alive = false;
      es.close();
    };
  }, [fullUrl]);

  return { data, connected, error };
}
