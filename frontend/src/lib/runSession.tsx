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

    //Esto va a usar TopNav:
    begin: (runId: string) => void;
    end: (status?: Extract<RunStatus, "finished" | "failed">) => void

    setSimNow: (iso: string) => void;
    setWindow: (w: RunWindow) => void;
    setSelectedAirport: (id: string | null) => void;
}

const RunSessionContext = createContext<RunSessionState | null>(null);

export function RunSessionProvider({children}: {children: React.ReactNode}){
    const [runId, setRunId] = useState<string | null>(null);
    const [status, setStatus] = useState<RunStatus>("idle");
    const [simNow, setSimNowState] = useState<string | null>(null);
    const [lastWindow, setLastWindow] = useState<RunWindow | null>(null);
    const [windows, setWindows] = useState<RunWindow[]>([]);
    const [selectedAirportId, setSelectedAirportId] = useState<string | null>(null);

    const begin = useCallback((id: string) => {
        setRunId(id);
        setStatus("running");
        setSimNowState(null);
        setLastWindow(null);
        setWindows([]);
        setSelectedAirportId(null);
    }, []);

    const end = useCallback((st: Extract<RunStatus, "finished" | "failed"> = "finished") => {
        setStatus(st);
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
        begin,
        end,
        setSimNow,
        setWindow,
        setSelectedAirport,
    }), [runId, status, simNow, lastWindow, windows, selectedAirportId, begin, end, setSimNow, setWindow, setSelectedAirport]);

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