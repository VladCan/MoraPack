// src/hooks/useFlightsSSE.ts
import { useEffect, useMemo, useState } from "react";
import type { FlightLiveDTO } from "@/types/api";

/**
 * Suscribe a vuelos en vivo via SSE.
 */
// 🛑 CORRECCIÓN 1: El valor por defecto no debe tener barra inicial
export function useFlightsSSE(endpoint: string = "vuelos/live?limit=50") {
    const [data, setData] = useState<FlightLiveDTO[]>([]);
    const [connected, setConnected] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);

    // Construye URL absoluta de forma segura (evita /api/vuelos/live)
    const fullUrl = useMemo(() => {
        const base = import.meta.env.VITE_API_BASE_URL as string | undefined; // Base: https://.../api/ (DEBE tener barra final)
        
        // 1. Lógica robusta: Eliminar la barra inicial del endpoint si existe (para evitar que new URL() anule la base).
        const relativeEndpoint = endpoint.startsWith('/') ? endpoint.substring(1) : endpoint;
        
        try {
            if (base) {
                // 2. new URL(relativeEndpoint, base) combina correctamente: .../api/vuelos/live
                return new URL(relativeEndpoint, base).toString();
            }
            
            // 3. Fallback: Si no hay base, devolver la ruta relativa con la barra inicial.
            return `/${relativeEndpoint}`; 
        } catch {
            return endpoint;
        }
    }, [endpoint]);

    useEffect(() => {
        if (!fullUrl) return;

        // La URL (fullUrl) ahora debe ser correcta (e.g., https://.../api/vuelos/live?limit=200)
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
                    setData(arr as FlightLiveDTO[]);
                }
            } catch {
                // ignora líneas no JSON
            }
        };

        es.onerror = () => {
            if (!alive) return;
            setConnected(false);
            setError("SSE desconectado");
        };

        return () => {
            alive = false;
            es.close();
        };
    }, [fullUrl]);

    return { data, connected, error };
}