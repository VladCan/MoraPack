// src/hooks/useRunSSE.ts

import { useEffect, useMemo, useRef, useState } from "react";

// Tipos de carga en un vuelo
export type CargaItem = {
    pedidoId: number;
    cantidad: number;
    destinoFinal: string;
    esConexion: boolean;
    creadoUtc?: string;
};

// Vuelo con su manifiesto de carga
export type VueloDTO = {
    id: string;
    origen: string;
    destino: string;
    salidaUtc: string;
    llegadaUtc: string;
    cantidadAsignada: number;
    capacidad: number;
    residual: number;
    costo: number;
    carga: CargaItem[];
};

// Ruta de entrega de un pedido
export type RutaDetalle = {
    cantidad: number;
    origen: string;
    destinoFinal: string;
    vuelos: Array<{
        id: string;
        origen: string;
        destino: string;
        salidaUtc: string;
        llegadaUtc: string;
        cantidad: number;
    }>;
};

// Pedido con su origen asignado
export type PedidoDTO = {
    id: number;
    idCliente: number;
    destino: string;
    cantidad: number;
    origen: string | string[] | null;  // Puede ser string, array o null
    cantidadAsignada: number;
    estadoAsignacion: "COMPLETO" | "PARCIAL" | "PENDIENTE";
    fechaCreacion: string;
    fechaLocal?: string;
    continenteDestino?: string;
    rutas?: RutaDetalle[];  // NUEVO: desglose de rutas
};

export type RunEvt = 
| {type:"RUN_STARTED"; runId: string; simStartUtc: string; wallAnchorUtc: string; speed: number }
| { type: "TICK";        runId: string; simNowUtc:  string }
| { type: "WINDOW";      runId: string; windowIndex: number; windowStartUtc: string; windowEndUtc: string; vuelos: VueloDTO[]; pedidos: PedidoDTO[] }
| { type: "FINISHED";    runId: string; reason: string };

export type WindowData = {
    index: number;
    startUtc: string;
    endUtc: string;
    vuelos: VueloDTO[];
    pedidos: PedidoDTO[];
}

export function useRunSSE(runId?: string){
    const [connected, setConnected] = useState(false);
    const [error, setError] = useState<string|null>(null);

    const [speed, setSpeed] = useState<number|undefined>();
    const [simNowUtc, setSimNow]  = useState<string|undefined>();
    const [simStartUtc, setSimStart] = useState<string|undefined>();
    const [windows, setWindows]   = useState<WindowData[]>([]);
    const [finished, setFinished] = useState<{reason:string}|null>(null);

    const esRef = useRef<EventSource | null>(null);

    const url = useMemo(() => {
        if (!runId) return null;
        const base = import.meta.env.VITE_API_BASE_URL as string | undefined;
        return new URL(`/runs/${runId}/stream`, base).toString();
    }, [runId]);

    useEffect(() => {
        if (!url) return;

        const es = new EventSource(url, {withCredentials: false});
        esRef.current = es;
        
        //Evita "setState on unmounted component" (no actualiza estado si el efecto ya fue limpiado)
        let alive = true;

        es.onopen = () => {
            if (!alive) return;
            setConnected(true);
            setError(null);
        }

        es.onmessage = (ev) => {
            if (!alive) return;
            try {
                const evt: RunEvt = JSON.parse(ev.data);

                switch (evt.type){
                    case "RUN_STARTED":
                        setSimStart(evt.simStartUtc);
                        setSpeed(evt.speed);
                        break;
                    case "TICK":
                        setSimNow(evt.simNowUtc);
                        break;
                    case "WINDOW":
                        setWindows((prev) => {
                        // evita duplicados por reconexiones
                        if (prev.find(w => w.index === evt.windowIndex)) return prev;
                        return [...prev, {
                            index: evt.windowIndex,
                            startUtc: evt.windowStartUtc,
                            endUtc:   evt.windowEndUtc,
                            vuelos: evt.vuelos || [],
                            pedidos: evt.pedidos || [],
                        }];
                        });
                        break;
                    case "FINISHED":
                        setFinished({ reason: evt.reason });
                        break;
                }

            }
            catch (e) {

            }
        }

        es.onerror = () => {
            if (!alive) return;
            setConnected(false);
            setError("SSE Desconectado");
        }

        return () => {
            alive = false;
            es.close();
            esRef.current = null;
        };

    }, [url])

    const disconnect = () => {
        esRef.current?.close();
        esRef.current = null;
        setConnected(false);
    };

    return {
        connected,
        error,
        speed,
        simStartUtc,
        simNowUtc,
        windows,
        finished,
        disconnect, //<- opcional por ahora
    };

}