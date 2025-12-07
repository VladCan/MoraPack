// src/hooks/useRunSSE.ts

import { useEffect, useMemo, useRef, useState } from "react";
// ... (omitiendo la definición de tipos por brevedad) ...

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
    origen: string | string[] | null; 
    cantidadAsignada: number;
    estadoAsignacion: "COMPLETO" | "PARCIAL" | "PENDIENTE";
    fechaCreacion: string;
    fechaLocal?: string;
    continenteDestino?: string;
    rutas?: RutaDetalle[]; 
};

// Datos de ocupación de un aeropuerto
export type AeropuertoOcupacion = {
    ocupacionActual: number;
    capacidadTotal: number;
    disponible: number;
    porcentaje: number;
    cargaLlegando?: number;
    cargaSaliendo?: number;
    estadisticasFuturas?: EstadisticasFuturas;
};

// Estadísticas de vuelos futuros para un aeropuerto
export type EstadisticasFuturas = {
    llegadasPrevistas: number;
    salidasPrevistas: number;
    cargaEntrante: number;
    cargaSaliente: number;
};

export type StopReason = "FIN_DE_RANGO" | "MANUAL" | "COLAPSO" | "ERROR";

export type RunEvt = 
| {type:"RUN_STARTED"; runId: string; simStartUtc: string; wallAnchorUtc: string; speed: number }
| {type: "PREPARING"; runId: string}
| { type: "TICK"; runId: string; simNowUtc: string; aeropuertos: Record<string, AeropuertoOcupacion> }
| { type: "WINDOW"; runId: string; windowIndex: number; windowStartUtc: string; windowEndUtc: string; vuelos: VueloDTO[]; pedidos: PedidoDTO[] }
| { type: "FINISHED"; runId: string; reason: StopReason };

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
    const [wallStartUtc, setWallStart] = useState<string | null>(null);
    const [windows, setWindows]   = useState<WindowData[]>([]);
    const [finished, setFinished] = useState<{reason: StopReason}|null>(null);
    const [airportOccupancy, setAirportOccupancy] = useState<Record<string, AeropuertoOcupacion>>({});

    const [preparing, setPreparing] = useState(false);

    const esRef = useRef<EventSource | null>(null);

    const url = useMemo(() => {
        if (!runId) return null;
        
        // 1. Definir la ruta interna del recurso SIN la barra inicial.
        const path = `runs/${runId}/stream`; 

        const base = import.meta.env.VITE_API_BASE_URL as string | undefined; // Base: https://.../api/
        
        console.log("[RUN DIAG] 🧪 Inicia construcción de URL");
        console.log("[RUN DIAG] 🔍 VITE_API_BASE_URL (base):", base);
        console.log("[RUN DIAG] 🔍 Recurso path:", path); // Debe ser 'runs/{id}/stream'

        // 2. Usar new URL() para construir la URL absoluta (Base + Path)
        try {
            let finalUrl: string;

            if (base && base.length > 0) {
                // CORRECCIÓN: new URL(path, base) ahora combina correctamente: .../api/runs/123/stream
                finalUrl = new URL(path, base).toString();
            } else {
                // Fallback (si la variable de entorno no se inyecta)
                finalUrl = `/${path}`; 
            }
            
            console.log("[RUN DIAG] URL FINAL construida:", finalUrl);
            return finalUrl;
        } catch (e) {
            console.error("[RUN DIAG] ❌ Error en construcción de URL:", e);
            return null; 
        }
    }, [runId]);

    useEffect(() => {
        // Reset state whenever conectamos a otro run
        setConnected(false);
        setError(null);
        setSpeed(undefined);
        setSimStart(undefined);
        setSimNow(undefined);
        setWindows([]);
        setFinished(null);
        setAirportOccupancy({});
        setPreparing(false);
    }, [runId]);

    useEffect(() => {
        if (!url) return;

        console.log(`[RUN DIAG] Conectando SSE a: ${url}`); // Log del intento de conexión

        const es = new EventSource(url, {withCredentials: false});
        esRef.current = es;
        
        let alive = true;

        es.onopen = () => {
            if (!alive) return;
            setConnected(true);
            setError(null);
            console.log("[RUN DIAG] Conexión abierta.");
        }

        es.onmessage = (ev) => {
            if (!alive) return;
            try {
                const evt: RunEvt = JSON.parse(ev.data);

                switch (evt.type){
                    case "PREPARING":
                        setPreparing(true);
                        break;
                    case "RUN_STARTED":
                        setSimStart(evt.simStartUtc);
                        setWallStart(evt.wallAnchorUtc);
                        setSpeed(evt.speed);
                        break;
                    case "TICK":
                        setSimNow(evt.simNowUtc);
                        if (evt.aeropuertos) {
                            setAirportOccupancy(evt.aeropuertos);
                        }
                        break;
                    case "WINDOW":
                        //console.log("[SSE] WINDOW", evt.windowIndex, "vuelos:", evt.vuelos.map(v => v.id));
                        setWindows((prev) => {
                            const nextWindow: WindowData = {
                                index: evt.windowIndex,
                                startUtc: evt.windowStartUtc,
                                endUtc:   evt.windowEndUtc,
                                vuelos: evt.vuelos || [],
                                pedidos: evt.pedidos || [],
                            };

                            const existingIdx = prev.findIndex((w) => w.index === evt.windowIndex);
                            if (existingIdx >= 0) {
                                const copy = [...prev];
                                copy[existingIdx] = nextWindow;
                                return copy;
                            }
                            return [...prev, nextWindow];
                        });
                        setPreparing(false);
                        break;
                    case "FINISHED":
                        setFinished({ reason: evt.reason });
                        // Cerramos la conexión para liberar recursos en el backend.
                        es.close();
                        esRef.current = null;
                        setConnected(false);
                        console.log("✅ [useRunSSE] Conexión cerrada exitosamente. (Fuera de disconnect() )")
                        break;
                }

            } catch (err: unknown) {
                console.error("[RUN DIAG] Failed to parse SSE message or handle event:", err);
                setError("Error procesando mensaje SSE");
            }
        }

        es.onerror = () => {
            if (!alive) return;
            setConnected(false);
            setError("SSE Desconectado");
            console.log("[RUN DIAG] Error de conexión.");
        }

        return () => {
            alive = false;
            es.close();
            console.log("[RUN DIAG] Conexión SSE cerrada.");
            esRef.current = null;
        };

    }, [url])

    //Refactorizamos el viejo disconnect
    const disconnect = () => {

        //Cerramos el SSE

        if (esRef.current){
            try{
                esRef.current.close();
                console.log("✅ [useRunSSE] Conexión cerrada exitosamente.")
            }
            catch{
                console.log("❌ [useRunSSE] No se pudo cerrar la conexión correctamente. (Cerrada fuera de disconnect() maybe)")
            }
            esRef.current = null;
        }
        
        //Limpiamos todo el estado del hook

        setConnected(false);
        setError(null);
        setSpeed(undefined);
        setSimStart(undefined);
        setSimNow(undefined);
        setWindows([]);
        setFinished(null);
        setAirportOccupancy({});
        setPreparing(false);

    };


    return {
        connected,
        error,
        speed,
        simStartUtc,
        wallStartUtc,
        simNowUtc,
        windows,
        finished,
        airportOccupancy,
        preparing,
        disconnect,
    };
}