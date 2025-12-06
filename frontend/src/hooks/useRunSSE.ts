// src/hooks/useRunSSE.ts
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";

// ==========================================
// 1. ZOD SCHEMAS & TIPOS (Source of Truth)
// ==========================================

// --- Tu Enum de Java reflejado en Zod ---
export const RunStateSchema = z.enum([
  "PENDING",
  "LOADING",   // <--- Aquí está el estado crítico para tu carga en memoria
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
  residual: z.number(),
  costo: z.number(),
  carga: z.array(CargaItemSchema),
});

const PedidoDTOSchema = z.object({
  id: z.number(),
  idCliente: z.number(),
  destino: z.string(),
  cantidad: z.number(),
  origen: z.union([z.string(), z.array(z.string()), z.null()]),
  cantidadAsignada: z.number(),
  estadoAsignacion: z.enum(["COMPLETO", "PARCIAL", "PENDIENTE"]),
  fechaCreacion: z.string(),
  fechaLocal: z.string().optional(),
  continenteDestino: z.string().optional(),
  rutas: z.array(RutaDetalleSchema).optional(),
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

// --- EVENTOS DEL SSE ---
// Aquí mapeamos los eventos que envía el backend para transicionar los estados
const RunEvtSchema = z.discriminatedUnion("type", [
  // 1. Estado LOADING: El backend está cargando archivos/optimizando
  z.object({
    type: z.literal("LOADING"), 
    message: z.string().optional(), // "Leyendo Excel...", "Optimizando rutas..."
    progress: z.number().optional() // 0 - 100
  }),
  // 2. Estado RUNNING (Inicio)
  z.object({
    type: z.literal("RUN_STARTED"),
    runId: z.string(),
    simStartUtc: z.string(),
    wallAnchorUtc: z.string(),
    speed: z.number(),
  }),
  // 3. Actualizaciones durante RUNNING
  z.object({
    type: z.literal("TICK"),
    runId: z.string(),
    simNowUtc: z.string(),
    aeropuertos: z.record(z.string(), AeropuertoOcupacionSchema).optional(),
  }),
  // 4. Ventanas de decisión (Sigue en RUNNING)
  z.object({
    type: z.literal("WINDOW"),
    runId: z.string(),
    windowIndex: z.number(),
    windowStartUtc: z.string(),
    windowEndUtc: z.string(),
    vuelos: z.array(VueloDTOSchema),
    pedidos: z.array(PedidoDTOSchema),
  }),
  // 5. Estados finales (COMPLETED, STOPPED, FAILED)
  z.object({
    type: z.literal("FINISHED"),
    runId: z.string(),
    reason: StopReasonSchema,
  }),
  // 6. Caso de Error explícito del backend
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
  // Estado de conexión técnica (SSE conectado o no)
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // --- NUEVO: Estado Lógico del Run (PENDING, LOADING, RUNNING, etc) ---
  const [runState, setRunState] = useState<RunState>("PENDING");
  
  // Datos extra para UI de carga
  const [loadingMessage, setLoadingMessage] = useState<string>("");
  const [loadingProgress, setLoadingProgress] = useState<number>(0);

  // Datos de Simulación
  const [speed, setSpeed] = useState<number | undefined>();
  const [simNowUtc, setSimNow] = useState<string | undefined>();
  const [simStartUtc, setSimStart] = useState<string | undefined>();
  const [wallStartUtc, setWallStart] = useState<string | null>(null);
  const [windows, setWindows] = useState<WindowData[]>([]);
  const [finishedReason, setFinishedReason] = useState<StopReason | null>(null);
  const [airportOccupancy, setAirportOccupancy] = useState<Record<string, AeropuertoOcupacion>>({});

  const esRef = useRef<EventSource | null>(null);

  // Construcción de URL (igual que antes)
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

  // Reset al cambiar runId
  useEffect(() => {
    setConnected(false);
    setError(null);
    setRunState("PENDING"); // Volvemos a PENDING
    setLoadingMessage("");
    setLoadingProgress(0);
    setSpeed(undefined);
    setSimStart(undefined);
    setSimNow(undefined);
    setWindows([]);
    setFinishedReason(null);
    setAirportOccupancy({});
  }, [runId]);

  useEffect(() => {
    if (!url) return;

    console.log(`[RUN DIAG] Conectando a ${url}`);
    const es = new EventSource(url, { withCredentials: false });
    esRef.current = es;

    let alive = true;

    es.onopen = () => {
      if (!alive) return;
      setConnected(true);
      setError(null);
      // Al conectar, asumimos que puede estar cargando o esperando
      // Idealmente el backend manda un evento inicial, pero por defecto:
      if (runState === 'PENDING') setRunState("LOADING"); 
    };

    es.onmessage = (ev) => {
      if (!alive) return;
      try {
        const raw = JSON.parse(ev.data);
        const evt = RunEvtSchema.parse(raw); // Validación Zod

        switch (evt.type) {
          case "LOADING":
            setRunState("LOADING"); // Sincronizamos con el Enum
            if (evt.message) setLoadingMessage(evt.message);
            if (evt.progress !== undefined) setLoadingProgress(evt.progress);
            break;

          case "RUN_STARTED":
            setRunState("RUNNING"); // Cambiamos estado a RUNNING
            setSimStart(evt.simStartUtc);
            setWallStart(evt.wallAnchorUtc);
            setSpeed(evt.speed);
            break;

          case "TICK":
            // Solo actualizamos datos, el estado sigue siendo RUNNING
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
            setRunState("COMPLETED"); // O STOPPED, dependiendo de tu lógica, pero ya no es RUNNING
            setFinishedReason(evt.reason);
            es.close();
            setConnected(false);
            break;
            
          case "ERROR":
            setRunState("FAILED");
            setError(evt.message);
            es.close();
            break;
        }
      } catch (err) {
        if (err instanceof z.ZodError) {
          console.error("Zod Validation Error:", err.issues);
          setError("Error de validación de datos (Backend mismatch)");
        } else {
          console.error("SSE Error:", err);
        }
      }
    };

    es.onerror = () => {
      if (!alive) return;
      setConnected(false);
      setError("Conexión perdida con el servidor");
      // Opcional: setRunState("FAILED") si la conexión muere
    };

    return () => {
      alive = false;
      es.close();
      esRef.current = null;
    };
  }, [runState, url]);

  const disconnect = () => {
    if (esRef.current) esRef.current.close();
    setConnected(false);
    setRunState("STOPPED");
  };

  return {
    // Estado Principal (Enum)
    runState,         // "PENDING" | "LOADING" | "RUNNING" | "COMPLETED" | "FAILED"
    
    // Datos de Loading
    loadingMessage,
    loadingProgress,

    // Datos técnicos
    connected,
    error,

    // Datos de Simulación
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