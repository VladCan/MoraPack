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
    simNow: string | null;
    lastWindow: RunWindow | null;
    windows: RunWindow[];
    activeFlights: VueloDTO[];
    activePedidos: PedidoDTO[];
    selectedAirportId: string | null;
    selectedPedido: PedidoDTO | null;
    vuelosCancelados: Set<string>;
    selectedVuelo: VueloDTO | null;
    setSelectedVuelo: (vuelo: VueloDTO | null) => void;
    toolsPanelOpen: boolean;
    setToolsPanelOpen: (open: boolean) => void;
    loadingMessage: string;
    loadingProgress: number;
    showLoadingOverlay: boolean;
    begin: (runId: string) => void;
    end: (status?: Extract<RunStatus, "finished" | "failed">) => void
    setSimNow: (iso: string) => void;
    setWindow: (w: RunWindow) => void;
    setSelectedAirport: (id: string | null) => void;
    setSelectedPedido: (pedido: PedidoDTO | null) => void;
    cancelarVuelo: (vueloId: string) => void;
    reset: () => void;
    autoReconnect: boolean;
    setAutoReconnect: (state: boolean) => void;
    setLoadingUI: (msg: string, progress: number) => void;
    setShowLoadingOverlay: (show: boolean) => void;
}

const RunSessionContext = createContext<RunSessionState | null>(null);

function parseTime(isoString: string | undefined | null): number {
    if (!isoString) return 0;
    
    let clean = isoString;
    if (!clean.endsWith("Z") && !clean.includes("+") && !clean.includes("-")) {
        clean += "Z";
    }
    const result = new Date(clean).getTime();
    
    // [DEBUG] Verificación de fechas
    if (isNaN(result)) {
        console.error(`[CRITICAL] parseTime falló. Original: "${isoString}", Clean: "${clean}"`);
    }
    return result;
}

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

    const [loadingMessage, setLoadingMessage] = useState("");
    const [loadingProgress, setLoadingProgress] = useState(0);
    const [showLoadingOverlay, setShowLoadingOverlayState] = useState(false);

    const [autoReconnect, setAutoReconnect] = useState<boolean>(true);

    // ==========================================
    // CÁLCULOS DERIVADOS (VUELOS)
    // ==========================================
    
    // 1. Aplanar todos los vuelos
    const allFlights = useMemo(() => {
        const flattened = windows.flatMap(w => w.vuelos);
        // [DEBUG] Cantidad de vuelos totales recibidos
        // console.log(`[DEBUG] allFlights recalculado. Ventanas: ${windows.length}, Vuelos: ${flattened.length}`);
        return flattened;
    }, [windows]);

    // 2. Filtrar vuelos activos
    const activeFlights = useMemo(() => {
        if (!simNow) return [];
        
        const simTime = parseTime(simNow);
        
        // [DEBUG] Estado del reloj vs total vuelos
        // console.log(`[DEBUG] Filtrando ActiveFlights. SimNow: ${simNow} (${simTime}), Total Vuelos: ${allFlights.length}`);

        return allFlights.filter(vuelo => {
            if (vuelosCancelados.has(vuelo.id)) return false;

            const salidaTime = parseTime(vuelo.salidaUtc);
            const llegadaTime = parseTime(vuelo.llegadaUtc);

            // [DEBUG] Validación específica por si un vuelo falla
            if (isNaN(salidaTime) || isNaN(llegadaTime)) {
                console.warn(`[WARN] Vuelo con fechas inválidas ID: ${vuelo.id}`, vuelo);
                return false;
            }

            const isActive = (salidaTime <= simTime) && (simTime <= llegadaTime);
            return isActive;
        });
    }, [simNow, allFlights, vuelosCancelados]);

    // ==========================================
    // CÁLCULOS DERIVADOS (PEDIDOS)
    // ==========================================

    const allPedidos = useMemo(() => {
        return windows.flatMap(w => w.pedidos || []);
    }, [windows]);

    const activePedidos = useMemo(() => {
        if (!simNow) return [];
        
        const simTime = parseTime(simNow);

        return allPedidos.filter(pedido => {
            if (!pedido || !pedido.fechaCreacion) return false;
            const creationTime = parseTime(pedido.fechaCreacion);
            return creationTime <= simTime;
        });
    }, [simNow, allPedidos]);

    // ==========================================
    // ACTIONS & CALLBACKS
    // ==========================================

    const begin = useCallback((id: string) => {
        console.log("[ACTION] BEGIN runId:", id);
        setRunId(id);
        setStatus("running");
        setSimNowState(null);
        setLastWindow(null);
        setWindows([]);
        setSelectedAirportId(null);
        setSelectedPedidoState(null);
        setVuelosCancelados(new Set());
        setSelectedVueloState(null);
        setLoadingMessage("");
        setLoadingProgress(0);
        setShowLoadingOverlayState(true);
    }, []);

    const end = useCallback((st: Extract<RunStatus, "finished" | "failed"> = "finished") => {
        console.log("[ACTION] END status:", st);
        setStatus(st);
    }, []);

    const reset = useCallback(() => {
        console.log("[ACTION] RESET");
        setRunId(null);
        setStatus("idle");
        setSimNowState(null);
        setLastWindow(null);
        setWindows([]);
        setSelectedAirportId(null);
        setSelectedPedidoState(null);
        setVuelosCancelados(new Set());
        setSelectedVueloState(null); 
        setLoadingMessage("");
        setLoadingProgress(0);
        setShowLoadingOverlayState(false);
    }, []);

    const cancelarVuelo = useCallback((vueloId: string) => {
        console.log("[ACTION] Cancelar Vuelo:", vueloId);
        setVuelosCancelados(prev => new Set([...prev, vueloId]));
    }, []);

    const setSimNow = useCallback((iso: string) => {
        // Nota: No logueamos aquí porque spamea mucho la consola
        setSimNowState(prev => prev === iso ? prev : iso);
    }, []);

    const setWindow = useCallback((w: RunWindow) => {
        console.log(`[ACTION] setWindow recibida. Index: ${w.index}, Vuelos: ${w.vuelos.length}`);
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

    const setLoadingUI = useCallback((msg: string, progress: number) => {
        setLoadingMessage(msg);
        setLoadingProgress(progress);
    }, []);

    const setShowLoadingOverlay = useCallback((show: boolean) => {
        setShowLoadingOverlayState(show);
    }, []);

    // ==========================================
    // EFECTO DE RECONEXIÓN
    // ==========================================
    useEffect(() => {
        if (!autoReconnect) return;
        if (runId !== null) return;

        let cancelled = false;
        const path = "runs/active"
        let msg = " ";

        const checkActiveRun = async () => {
            console.log("[DEBUG] Buscando run activo...");
            try {
                const [data, error] = await handleApi(
                    getJson<{ runId?: string }>(path)
                );

                if (error){
                    console.error("[ERROR] checkActiveRun API error:", error);
                }

                if (!cancelled && data?.runId) {
                    console.log("[RunSession] Run activo detectado:", data.runId);
                    begin(data.runId);

                    try {
                        const [snap, snapErr] = await handleApi(
                            getJson<RunWindow>(`runs/${data.runId}/snapshot`)
                        );

                        if (snap && !snapErr) {
                            console.log("[RunSession] Snapshot recibido con éxito. Index:", snap.index);
                            msg = " con snapshot "
                            setWindow(snap);
                        } else {
                            console.warn("[WARN] Snapshot falló o vino vacío", snapErr);
                        }
                    }
                    catch (e){
                        console.error("[RunSession] Excepción obteniendo snapshot:", e);
                    }

                    toast.custom((t) => (
                        <ToastCustom
                            t={t}
                            message={"Conexión establecida"+ msg}
                            type="success"
                        />),
                    { duration: 5000})
                } else {
                    console.log("[DEBUG] No se encontró run activo.");
                }

            }
            catch (err){
                console.error("[RunSession] Error detectando run activo (catch general):", err);
            }
        }

        checkActiveRun();

        return () => { cancelled = true; };
    }, [runId, status, begin, autoReconnect, setWindow]); 

    // ==========================================
    // VALUE DEL CONTEXTO
    // ==========================================

    const value = useMemo<RunSessionState>(() => ({
        runId,
        status,
        simNow,
        lastWindow,
        windows,
        activeFlights,
        activePedidos,
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
        setAutoReconnect, // Se pasa aquí para que los hijos puedan usarlo si quieren
        loadingMessage,
        loadingProgress,
        showLoadingOverlay,
        setLoadingUI,
        setShowLoadingOverlay
    }), [
        runId, status, simNow, lastWindow, windows, 
        activeFlights, activePedidos,
        selectedAirportId, selectedPedido, vuelosCancelados,
        selectedVuelo, setSelectedVuelo,
        toolsPanelOpen, setToolsPanelOpen,
        begin, end, setSimNow, setWindow, setSelectedAirport, setSelectedPedido, cancelarVuelo, reset, autoReconnect,
        loadingMessage, loadingProgress, showLoadingOverlay, setLoadingUI, setShowLoadingOverlay
    ]);
    
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