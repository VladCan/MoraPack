//src/lib/runSession.tsx
import { createContext, useContext, useMemo, useState, useCallback } from "react";
import type { VueloDTO, PedidoDTO } from "@/hooks/useRunSSE";

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
    vuelosCancelados: Set<string>;  // IDs de vuelos cancelados localmente

    //Esto va a usar TopNav:
    begin: (runId: string) => void;
    end: (status?: Extract<RunStatus, "finished" | "failed">) => void

    setSimNow: (iso: string) => void;
    setWindow: (w: RunWindow) => void;
    setSelectedAirport: (id: string | null) => void;
    cancelarVuelo: (vueloId: string) => void;  // Agregar vuelo a la lista de cancelados

    reset: () => void;
}

const RunSessionContext = createContext<RunSessionState | null>(null);

export function RunSessionProvider({children}: {children: React.ReactNode}){
    const [runId, setRunId] = useState<string | null>(null);
    const [status, setStatus] = useState<RunStatus>("idle");
    const [simNow, setSimNowState] = useState<string | null>(null);
    const [lastWindow, setLastWindow] = useState<RunWindow | null>(null);
    const [windows, setWindows] = useState<RunWindow[]>([]);
    const [selectedAirportId, setSelectedAirportId] = useState<string | null>(null);
    const [vuelosCancelados, setVuelosCancelados] = useState<Set<string>>(new Set());

    const begin = useCallback((id: string) => {
        setRunId(id);
        setStatus("running");
        setSimNowState(null);
        setLastWindow(null);
        setWindows([]);
        setSelectedAirportId(null);
        setVuelosCancelados(new Set());
    }, []);

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
        setVuelosCancelados(new Set());
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

    const value = useMemo<RunSessionState>(() => ({
        runId,
        status,
        simNow,
        lastWindow,
        windows,
        selectedAirportId,
        vuelosCancelados,
        begin,
        end,
        setSimNow,
        setWindow,
        setSelectedAirport,
        cancelarVuelo,
        reset,
    }), [runId, status, simNow, lastWindow, windows, selectedAirportId, vuelosCancelados, begin, end, setSimNow, setWindow, setSelectedAirport, cancelarVuelo, reset]);

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