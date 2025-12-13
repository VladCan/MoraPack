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

// --- NUEVO: Schema para el detalle del recojo del cliente ---
const RecojoSchema = z.object({
  cantidad: z.number(),
  inicioRecojo: z.string(), // ISO 8601
  finRecojo: z.string(),    // ISO 8601
});

const PedidoDTOSchema = z.object({
  id: z.number(),
  idCliente: z.number(),
  destino: z.string(),
  cantidad: z.number(),
  // Por si acaso el backend manda numPaquetes en otro contexto
  numPaquetes: z.number().optional(), 
  origen: z.union([z.string(), z.array(z.string()), z.null()]),
  cantidadAsignada: z.number(),
  estadoAsignacion: z.enum(["COMPLETO", "PARCIAL", "PENDIENTE"]),
  fechaCreacion: z.string(),
  fechaLocal: z.string().optional(),
  continenteDestino: z.string().optional(),
  rutas: z.array(RutaDetalleSchema).optional(),
  
  // --- NUEVO: Lista de recojos (para mostrar salidas en el frontend) ---
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

// --- EVENTOS DEL SSE ---
const RunEvtSchema = z.discriminatedUnion("type", [
  // 1. Estado LOADING
  z.object({
    type: z.literal("LOADING"), 
    message: z.string().optional(),
    progress: z.number().optional()
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
  // 4. Ventanas de decisión
  z.object({
    type: z.literal("WINDOW"),
    runId: z.string(),
    windowIndex: z.number(),
    windowStartUtc: z.string(),
    windowEndUtc: z.string(),
    vuelos: z.array(VueloDTOSchema),
    pedidos: z.array(PedidoDTOSchema),
  }),
  // 5. Estados finales
  z.object({
    type: z.literal("FINISHED"),
    runId: z.string(),
    reason: StopReasonSchema,
  }),
  // 6. Error explícito
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
// Exportamos el tipo de recojo por si se necesita fuera
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
  // Estado de conexión técnica
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // --- Estado Lógico del Run ---
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

  // =========================================================
  // 🕵️ LOG DE DIAGNÓSTICO: MONITOREO DE CAMBIO DE ESTADO
  // =========================================================
  useEffect(() => {
    // Si cambia el estado, lo imprimimos con color verde brillante
    console.log(`%c[HOOK STATE] runState cambió a: ${runState}`, 'background: #000; color: #0f0; font-size: 14px; padding: 3px;');
    
    if (runState === 'LOADING') {
        console.log('%c[HOOK STATE] ⏳ ESTAMOS EN LOADING (La UI debería mostrar spinner)', 'background: orange; color: black; font-weight: bold; padding: 4px;');
    }
  }, [runState]);

  // Construcción de URL
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
  }, [runId]);

  useEffect(() => {
    if (!url) return;

    console.log(`%c[RUN DIAG] Conectando a ${url}`, 'color: cyan');
    const es = new EventSource(url, { withCredentials: false });
    esRef.current = es;

    let alive = true;

    es.onopen = () => {
      if (!alive) return;
      console.log("%c[SSE OPEN] Conexión abierta", 'color: cyan');
      setConnected(true);
      setError(null);
      // Por defecto, si estábamos pending, pasamos a loading al conectar (esperando datos)
      if (runState === 'PENDING') setRunState("LOADING"); 
    };

    es.onmessage = (ev) => {
      if (!alive) return;
      try {
        const raw = JSON.parse(ev.data);
        
        // =========================================================
        // 🕵️ LOG DE DIAGNÓSTICO: DATOS CRUDOS DEL BACKEND
        // =========================================================
        // Solo logueamos si NO es un TICK para no saturar la consola, 
        // pero SI logueamos LOADING, ERROR, WINDOW, etc.
        if (raw.type !== 'TICK') {
            console.log(`%c📩 [SSE RAW] Evento recibido: ${raw.type}`, 'color: #aaa', raw);
        }

        const evt = RunEvtSchema.parse(raw); // Validación Zod

        switch (evt.type) {
          case "LOADING":
            console.log("%c[SSE] Entrando al case LOADING", 'color: orange');
            setRunState("LOADING"); 
            if (evt.message) setLoadingMessage(evt.message);
            if (evt.progress !== undefined) setLoadingProgress(evt.progress);
            break;

          case "RUN_STARTED":
            console.log("%c🚀 [SSE] RUN STARTED", 'color: lime');
            setRunState("RUNNING");
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
            console.log("%c🏁 [SSE] FINISHED", 'color: red');
            setRunState("COMPLETED");
            setFinishedReason(evt.reason);
            es.close();
            setConnected(false);
            break;
            
          case "ERROR":
            console.error("❌ [SSE ERROR EVENT]", evt.message);
            setRunState("FAILED");
            setError(evt.message);
            es.close();
            break;
        }
      } catch (err) {
        if (err instanceof z.ZodError) {
          // =========================================================
          // 🕵️ LOG DE DIAGNÓSTICO: ERROR DE VALIDACIÓN
          // =========================================================
          console.error("%c❌ [ZOD ERROR] El backend mandó datos inválidos:", 'background: red; color: white', err.issues);
          setError("Error de validación de datos (Backend mismatch)");
        } else {
          console.error("SSE Error:", err);
        }
      }
    };

    es.onerror = () => {
      if (!alive) return;
      console.error("⚠️ [SSE NETWORK ERROR] Conexión fallida o cerrada");
      setConnected(false);
      setError("Conexión perdida con el servidor");
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