import { useEffect, useState } from "react";
import type { FlightLiveDTO } from "@/types/api";

export function useFlightsSSE(endpoint = "/vuelos/live") {
  const [data, setData] = useState<FlightLiveDTO[]>([]);
  const [connected, setConnected] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const url = (import.meta.env.VITE_API_BASE_URL ?? "") + endpoint;

    const es = new EventSource(url, { withCredentials: false });
    let alive = true;

    es.onopen = () => { if (alive) { setConnected(true); setError(null); } };

    es.onmessage = (ev: MessageEvent<string>) => {
      try {
        const arr: FlightLiveDTO[] = JSON.parse(ev.data);
        if (alive && Array.isArray(arr)) setData(arr);
      } catch (e) {
        // ignora eventos malformados
      }
    };

    es.onerror = () => {
      if (alive) {
        setConnected(false);
        setError("SSE desconectado");
      }
      // Nota: algunos navegadores reintentan automáticamente
    };

    return () => {
      alive = false;
      es.close();
    };
  }, [endpoint]);

  return { data, connected, error };
}
