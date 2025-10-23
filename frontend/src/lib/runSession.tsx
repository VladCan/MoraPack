//src/lib/runSession.tsx
import { createContext, useContext, useMemo, useState } from "react";

export type RunStatus = "idle" | "running" | "finished" | "failed";

export interface RunWindow{
    index: number;
    startUtc: string;
    endUtc: string;
}

interface RunSessionState {
    runId: string | null;
    status: RunStatus;
    simNow: string | null;          // ISO-8601 del “ahora” simulado
    lastWindow: RunWindow | null;   // última ventana recibida por SSE

    //Esto va a usar TopNav:
    begin: (runId: string) => void;
    end: (status?: Extract<RunStatus, "finished" | "failed">) => void

    setSimNow: (iso: string) => void;
    setWindow: (w: RunWindow) => void;
}

const RunSessionContext = createContext<RunSessionState | null>(null);

export function RunSessionProvider({children}: {children: React.ReactNode}){
    const [runId, setRunId] = useState<string | null>(null);
    const [status, setStatus] = useState<RunStatus>("idle");
    const [simNow, setSimNowState] = useState<string | null>(null);
    const [lastWindow, setLastWindow] = useState<RunWindow | null>(null);

    const value = useMemo<RunSessionState>(() => ({
        runId,
        status,
        simNow,
        lastWindow,

        begin: (id: string) => {
            setRunId(id);
            setStatus("running");
            setSimNowState(null);
            setLastWindow(null);
        },

        end: (st = "finished") => {
            setStatus(st);
            //Podríamos mantener runId para consultar resultados o limpiarlo
            //setRunId(null);
        }, 

        setSimNow: (iso: string) => setSimNowState(iso),
        setWindow: (w: RunWindow) => setLastWindow(w),
    }), [runId, status, simNow, lastWindow])

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