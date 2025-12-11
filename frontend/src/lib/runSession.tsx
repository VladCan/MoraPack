//src/lib/runSession.tsx
import { createContext, useContext, useMemo, useState, useCallback, useEffect } from "react";
import type { VueloDTO, PedidoDTO } from "@/hooks/useRunSSE";
import { getJson, handleApi } from "@/services/api";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";

export type RunStatus = "idle" | "running" | "finished" | "failed";

export interface RunWindow{
    index: number;
    startUtc: string;
    endUtc: string;
    vuelos: VueloDTO[];
    pedidos: PedidoDTO[];
}

interface RunSessionState {
    runId: string | null;
    status: RunStatus;
    simNow: string | null;          // ISO-8601 del "ahora" simulado
    lastWindow: RunWindow | null;   // última ventana recibida por SSE (con vuelos y pedidos)
    windows: RunWindow[];           // historial completo de ventanas recibidas
    selectedAirportId: string | null;
    selectedPedido: PedidoDTO | null;  // Pedido seleccionado para filtrar vuelos
    vuelosCancelados: Set<string>;  // IDs de vuelos cancelados localmente

    selectedVuelo: VueloDTO | null;
    setSelectedVuelo: (vuelo: VueloDTO | null) => void;

    toolsPanelOpen: boolean;
    setToolsPanelOpen: (open: boolean) => void;

    //Esto va a usar TopNav:
    begin: (runId: string) => void;
    end: (status?: Extract<RunStatus, "finished" | "failed">) => void

    setSimNow: (iso: string) => void;
    setWindow: (w: RunWindow) => void;
    setSelectedAirport: (id: string | null) => void;
    setSelectedPedido: (pedido: PedidoDTO | null) => void;  // Actualizar pedido seleccionado
    cancelarVuelo: (vueloId: string) => void;  // Agregar vuelo a la lista de cancelados

    reset: () => void;

    autoReconnect: boolean;
    setAutoReconnect: (state: boolean) => void;

}

const RunSessionContext = createContext<RunSessionState | null>(null);

export function RunSessionProvider({children}: {children: React.ReactNode}){
    const [runId, setRunId] = useState<string | null>(null);
    const [status, setStatus] = useState<RunStatus>("idle");
    const [simNow, setSimNowState] = useState<string | null>(null);
    const [lastWindow, setLastWindow] = useState<RunWindow | null>(null);
    const [windows, setWindows] = useState<RunWindow[]>([]);
    const [selectedAirportId, setSelectedAirportId] = useState<string | null>(null);
    const [selectedPedido, setSelectedPedidoState] = useState<PedidoDTO | null>(null);
    const [vuelosCancelados, setVuelosCancelados] = useState<Set<string>>(new Set());

    const [selectedVuelo, setSelectedVueloState] = useState<VueloDTO | null>(null);
    const [toolsPanelOpen, setToolsPanelOpenState] = useState(false);

    const [autoReconnect, setAutoReconnect] = useState<boolean>(true);

    const begin = useCallback((id: string) => {
        setRunId(id);
        setStatus("running");
        setSimNowState(null);
        setLastWindow(null);
        setWindows([]);
        setSelectedAirportId(null);
        setSelectedPedidoState(null);
        setVuelosCancelados(new Set());
        setSelectedVueloState(null);
    }, []);

    //Esto es para reconectar, o conectar por primera vez desde otro pc
    useEffect(() => {
        // No reconectar si esta pestaña canceló manualmente la simulación
        if (!autoReconnect) return;

        if (runId !== null) return;
        
        //if (runId !== null || status !== "idle") return;

        let cancelled = false;
        
        const path = "runs/active"
        
        let msg = " ";

        const checkActiveRun = async () => {
            try {
                const [data, error] = await handleApi(
                    getJson<{ runId?: string }>(path)
                );

                if (error){
                    console.log("Parece que hubo un error")
                }

                if (!cancelled && data?.runId) {
                    console.log("[RunSession] Run activo detectado:", data.runId);
                    begin(data.runId);

                    ///Pedimos el snapshot/última ventana
                    try {
                        const [snap, snapErr] = await handleApi(
                            getJson<RunWindow>(`runs/${data.runId}/snapshot`)
                        );

                        if (snap && !snapErr) {
                            console.log("[RunSession] Snapshot recibido:", snap);
                            msg = " con snapshot "
                            setWindow(snap);
                        }
                    }
                    catch (e){
                        console.warn("[RunSession] No se pudo obtener snapshot:", e);
                    }


                    toast.custom((t) => (
                        <ToastCustom
                            t={t}
                            message={"Conexión establecida"+ msg}
                            type="success"
                        />),
                    { duration: 5000})
                }

            }
            catch (err){
                console.error("[RunSession] Error detectando run activo:", err);
            }
        }

        checkActiveRun();

        return () => { cancelled = true; };
    }, [runId, status, begin, autoReconnect]);

    const end = useCallback((st: Extract<RunStatus, "finished" | "failed"> = "finished") => {
        setStatus(st);
    }, []);

    const reset = useCallback(() => {
        setRunId(null);
        setStatus("idle");
        setSimNowState(null);
        setLastWindow(null);
        setWindows([]);
        setSelectedAirportId(null);
        setSelectedPedidoState(null);
        setVuelosCancelados(new Set());
        setSelectedVueloState(null); 
    }, []);

    const cancelarVuelo = useCallback((vueloId: string) => {
        setVuelosCancelados(prev => new Set([...prev, vueloId]));
    }, []);

    const setSimNow = useCallback((iso: string) => {
        setSimNowState(prev => prev === iso ? prev : iso);
    }, []);

    const setWindow = useCallback((w: RunWindow) => {
        setLastWindow(w);
        setWindows(prev => {
            const idx = prev.findIndex(win => win.index === w.index);
            if (idx >= 0) {
                const next = [...prev];
                next[idx] = w;
                return next;
            }
            return [...prev, w];
        });
    }, []);

    const setSelectedAirport = useCallback((id: string | null) => {
        setSelectedAirportId(id);
    }, []);

    const setSelectedPedido = useCallback((pedido: PedidoDTO | null) => {
        setSelectedPedidoState(pedido);
    }, []);

    const setSelectedVuelo = useCallback((vuelo: VueloDTO | null) => {
        setSelectedVueloState(vuelo);
    }, []);

    const setToolsPanelOpen = useCallback((open: boolean) => {
        setToolsPanelOpenState(open);
    }, []);

    const value = useMemo<RunSessionState>(() => ({
        runId,
        status,
        simNow,
        lastWindow,
        windows,
        selectedAirportId,
        selectedPedido,
        vuelosCancelados,
        selectedVuelo,
        setSelectedVuelo,
        toolsPanelOpen,
        setToolsPanelOpen,
        begin,
        end,
        setSimNow,
        setWindow,
        setSelectedAirport,
        setSelectedPedido,
        cancelarVuelo,
        reset,
        autoReconnect,
        setAutoReconnect
    }), [runId, status, simNow, lastWindow, windows, selectedAirportId, selectedPedido, vuelosCancelados,
        selectedVuelo, toolsPanelOpen, begin, end, setSimNow, setWindow, setSelectedAirport, setSelectedPedido, cancelarVuelo, reset, autoReconnect]);

    return (
        <RunSessionContext.Provider value={value}>
            {children}
        </RunSessionContext.Provider>
    )
}

export function useRunSession(): RunSessionState{
    const ctx = useContext(RunSessionContext);
    if (!ctx) throw new Error("useRunSession must be used within <RunSessionProvider>");
    return ctx;
}