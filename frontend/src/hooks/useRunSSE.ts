// src/hooks/useRunSSE.ts
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";

// ==========================================
// 1. ZOD SCHEMAS & TIPOS (Source of Truth)
// ==========================================

export const RunStateSchema = z.enum([
  "PENDING",
  "LOADING",   
  "RUNNING",
  "STOPPED",
  "COMPLETED",
  "FAILED"
]);

// Tipos auxiliares (Carga, Vuelos, etc)
const CargaItemSchema = z.object({
  pedidoId: z.number(),
  cantidad: z.number(),
  destinoFinal: z.string(),
  esConexion: z.boolean(),
  creadoUtc: z.string().optional(),
});

const VueloSimpleSchema = z.object({
  id: z.string(),
  origen: z.string(),
  destino: z.string(),
  salidaUtc: z.string(),
  llegadaUtc: z.string(),
  cantidad: z.number(),
});

const RutaDetalleSchema = z.object({
  cantidad: z.number(),
  origen: z.string(),
  destinoFinal: z.string(),
  vuelos: z.array(VueloSimpleSchema),
});

const VueloDTOSchema = z.object({
  id: z.string(),
  origen: z.string(),
  destino: z.string(),
  salidaUtc: z.string(),
  llegadaUtc: z.string(),
  cantidadAsignada: z.number(),
  capacidad: z.number(),
  carga: z.array(CargaItemSchema),
  residual: z.number().optional().nullable(),
  costo: z.number().optional().nullable(),
});

const RecojoSchema = z.object({
  cantidad: z.number(),
  inicioRecojo: z.string(), 
  finRecojo: z.string(),    
});

const PedidoDTOSchema = z.object({
  id: z.number(),
  idCliente: z.number(),
  destino: z.string(),
  cantidad: z.number(),
  numPaquetes: z.number().optional(), 
  origen: z.union([z.string(), z.array(z.string()), z.null()]),
  cantidadAsignada: z.number(),
  estadoAsignacion: z.enum(["COMPLETO", "PARCIAL", "PENDIENTE"]),
  fechaCreacion: z.string(),
  fechaLocal: z.string().optional(),
  continenteDestino: z.string().optional(),
  rutas: z.array(RutaDetalleSchema).optional(),
  recojos: z.array(RecojoSchema).optional(), 
});

const EstadisticasFuturasSchema = z.object({
  llegadasPrevistas: z.number(),
  salidasPrevistas: z.number(),
  cargaEntrante: z.number(),
  cargaSaliente: z.number(),
});

const AeropuertoOcupacionSchema = z.object({
  ocupacionActual: z.number(),
  capacidadTotal: z.number(),
  disponible: z.number(),
  porcentaje: z.number(),
  cargaLlegando: z.number().optional(),
  cargaSaliendo: z.number().optional(),
  estadisticasFuturas: EstadisticasFuturasSchema.optional(),
});

const StopReasonSchema = z.enum(["FIN_DE_RANGO", "MANUAL", "COLAPSO", "ERROR"]);

// --- NUEVO: Schema para el Snapshot ---
const SnapshotSchema = z.object({
    runId: z.string(),
    simNow: z.string(),
    vuelos: z.array(VueloDTOSchema)
});

// --- EVENTOS DEL SSE ---
const RunEvtSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("LOADING"), 
    runId: z.string(),
    message: z.string().optional(),
    progress: z.number().optional()
  }),
  z.object({
    type: z.literal("RUN_STARTED"),
    runId: z.string(),
    simStartUtc: z.string(),
    wallAnchorUtc: z.string(),
    speed: z.number(),
  }),
  z.object({
    type: z.literal("TICK"),
    runId: z.string(),
    simNowUtc: z.string(),
    aeropuertos: z.record(z.string(), AeropuertoOcupacionSchema).optional(),
  }),
  z.object({
    type: z.literal("WINDOW"),
    runId: z.string(),
    windowIndex: z.number(),
    windowStartUtc: z.string(),
    windowEndUtc: z.string(),
    vuelos: z.array(VueloDTOSchema),
    pedidos: z.array(PedidoDTOSchema),
  }),
  z.object({
    type: z.literal("FINISHED"),
    runId: z.string(),
    reason: StopReasonSchema,
  }),
  z.object({
    type: z.literal("ERROR"),
    message: z.string()
  })
]);

// Exportamos Tipos Inferidos
export type RunState = z.infer<typeof RunStateSchema>;
export type RunEvt = z.infer<typeof RunEvtSchema>;
export type VueloDTO = z.infer<typeof VueloDTOSchema>;
export type PedidoDTO = z.infer<typeof PedidoDTOSchema>;
export type AeropuertoOcupacion = z.infer<typeof AeropuertoOcupacionSchema>;
export type StopReason = z.infer<typeof StopReasonSchema>;
export type RecojoDTO = z.infer<typeof RecojoSchema>;

export type WindowData = {
  index: number;
  startUtc: string;
  endUtc: string;
  vuelos: VueloDTO[];
  pedidos: PedidoDTO[];
};

// ==========================================
// 2. HOOK IMPLEMENTATION
// ==========================================

export function useRunSSE(runId?: string) {
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [runState, setRunState] = useState<RunState>("PENDING");
  const [loadingMessage, setLoadingMessage] = useState<string>("");
  const [loadingProgress, setLoadingProgress] = useState<number>(0);

  const [speed, setSpeed] = useState<number | undefined>();
  const [simNowUtc, setSimNow] = useState<string | undefined>();
  const [simStartUtc, setSimStart] = useState<string | undefined>();
  const [wallStartUtc, setWallStart] = useState<string | null>(null);
  const [windows, setWindows] = useState<WindowData[]>([]);
  const [finishedReason, setFinishedReason] = useState<StopReason | null>(null);
  const [airportOccupancy, setAirportOccupancy] = useState<Record<string, AeropuertoOcupacion>>({});

  const esRef = useRef<EventSource | null>(null);

  // LOG de diagnóstico de estado (solo informativo)
  useEffect(() => {
    console.log(`%c[HOOK STATE] runState cambió a: ${runState}`, 'background: #000; color: #0f0; font-size: 14px; padding: 3px;');
  }, [runState]);

  const url = useMemo(() => {
    if (!runId) return null;
    const path = `runs/${runId}/stream`;
    const base = import.meta.env.VITE_API_BASE_URL as string | undefined;
    try {
      if (base && base.length > 0) return new URL(path, base).toString();
      return `/${path}`;
    } catch (e) {
      console.error("URL Error", e);
      return null;
    }
  }, [runId]);

  // Reset al cambiar runId (Limpieza de estado local)
  useEffect(() => {
    if (runId) {
      console.log(`[HOOK RESET] Nuevo runId detectado: ${runId}`);
      setConnected(false);
      setError(null);
      setRunState("PENDING"); 
      setLoadingMessage("");
      setLoadingProgress(0);
      setSpeed(undefined);
      setSimStart(undefined);
      setSimNow(undefined);
      setWindows([]);
      setFinishedReason(null);
      setAirportOccupancy({});
    }
  }, [runId]);

  // =========================================================
  // 📸 FETCH SNAPSHOT (Rehidratación) - opcional si ya lo manejas fuera
  // =========================================================
  // Nota: Si ya lo estás llamando en runSession.tsx (el provider autónomo), 
  // tenerlo aquí también podría duplicar llamadas, pero no rompe nada.
  // Lo dejaremos por seguridad.
  useEffect(() => {
      if (!runId) return;

      const fetchSnapshot = async () => {
          try {
              // Construir URL del snapshot
              const path = `runs/${runId}/snapshot`;
              const base = import.meta.env.VITE_API_BASE_URL as string | undefined;
              const snapshotUrl = base ? new URL(path, base).toString() : `/${path}`;

              const res = await fetch(snapshotUrl);
              if (!res.ok) return;

              const json = await res.json();
              const data = SnapshotSchema.parse(json);

              console.log(`%c[SNAPSHOT HOOK] Recibidos ${data.vuelos.length} vuelos`, 'color: violet');
              
              setSimNow(prev => prev || data.simNow);
              
              setWindows(prev => {
                  const snapshotWindow: WindowData = {
                      index: -999, 
                      startUtc: data.simNow,
                      endUtc: data.simNow,
                      vuelos: data.vuelos,
                      pedidos: [] 
                  };
                  if (prev.some(w => w.index === -999)) return prev;
                  return [snapshotWindow, ...prev];
              });

          } catch (error) {
              console.error("[SNAPSHOT HOOK] Error:", error);
          }
      };
      fetchSnapshot();
  }, [runId]);

  // =========================================================
  // SSE CONNECTION (CORREGIDO)
  // =========================================================
  useEffect(() => {
    if (!url) return;

    console.log(`%c[RUN SSE] Iniciando conexión a ${url}`, 'color: cyan');
    const es = new EventSource(url, { withCredentials: false });
    esRef.current = es;

    let alive = true;

    es.onopen = () => {
      if (!alive) return;
      console.log("%c[SSE OPEN] Conectado", 'color: cyan');
      setConnected(true);
      setError(null);
      // No forzamos cambio de estado aquí para evitar loops, 
      // dejamos que el evento LOADING o RUN_STARTED lo dicte.
    };

    es.onmessage = (ev) => {
      if (!alive) return;
      try {
        const raw = JSON.parse(ev.data);
        
        // Logs reducidos para no saturar, solo eventos clave
        if (raw.type !== 'TICK') {
            console.log(`%c📩 [SSE] ${raw.type}`, 'color: #aaa', raw);
        }

        const evt = RunEvtSchema.parse(raw); 

        switch (evt.type) {
          case "LOADING":
            setRunState("LOADING"); 
            if (evt.message) setLoadingMessage(evt.message);
            if (evt.progress !== undefined) setLoadingProgress(evt.progress);
            break;

          case "RUN_STARTED":
            console.log("%c🚀 RUN STARTED", 'color: lime');
            setRunState("RUNNING"); // Esto ya no reiniciará la conexión
            setSimStart(evt.simStartUtc);
            setWallStart(evt.wallAnchorUtc);
            setSpeed(evt.speed);
            break;

          case "TICK":
            setSimNow(evt.simNowUtc);
            if (evt.aeropuertos) setAirportOccupancy(evt.aeropuertos);
            break;

          case "WINDOW":
            setWindows((prev) => {
              const nw: WindowData = {
                index: evt.windowIndex,
                startUtc: evt.windowStartUtc,
                endUtc: evt.windowEndUtc,
                vuelos: evt.vuelos,
                pedidos: evt.pedidos,
              };
              // Evitar duplicados
              const idx = prev.findIndex((w) => w.index === nw.index);
              if (idx >= 0) {
                const cp = [...prev];
                cp[idx] = nw;
                return cp;
              }
              return [...prev, nw];
            });
            break;

          case "FINISHED":
            console.log("%c🏁 FINISHED", 'color: red');
            setRunState("COMPLETED");
            setFinishedReason(evt.reason);
            es.close();
            setConnected(false);
            break;
            
          case "ERROR":
            console.error("❌ SSE Error:", evt.message);
            setRunState("FAILED");
            setError(evt.message);
            es.close();
            break;
        }
      } catch (err) {
        if (err instanceof z.ZodError) {
          console.error("%c❌ Zod Error:", 'color:red', err.issues);
          // Opcional: No cerrar conexión por error de validación de un solo paquete
          // setError("Data mismatch"); 
        } else {
          console.error("SSE Parse Error:", err);
        }
      }
    };

    es.onerror = () => {
      if (!alive) return;
      console.error("⚠️ SSE Network Error");
      setConnected(false);
      // EventSource tiene reconexión automática nativa, 
      // pero si falla fatalmente:
      // setError("Conexión perdida");
    };

    return () => {
      console.log("%c[SSE CLEANUP] Cerrando conexión...", 'color: gray');
      alive = false;
      es.close();
      esRef.current = null;
    };

  // ⚠️ CORRECCIÓN CRÍTICA: Quitamos 'runState' de las dependencias.
  // Ahora la conexión solo se reinicia si cambia la URL (runId).
  }, [url]); 

  const disconnect = () => {
    if (esRef.current) esRef.current.close();
    setConnected(false);
    setRunState("STOPPED");
  };

  return {
    runState,
    loadingMessage,
    loadingProgress,
    connected,
    error,
    speed,
    simStartUtc,
    wallStartUtc,
    simNowUtc,
    windows,
    finishedReason,
    airportOccupancy,
    disconnect,
  };
}