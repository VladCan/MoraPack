import * as React from "react";
import { useEffect, useMemo, useState, useCallback } from "react";
import {
  Building2,
  Plane,
  Package,
  Filter,
  X,
  Check,
  Search,
  Calendar,
  Trash2,
  RefreshCw,
} from "lucide-react";
import { DateTimePicker } from "@/components/ui/DatetimePicker";
import { es } from "date-fns/locale";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../ui/select";
import { buildStartRunRequest } from "@/services/buildStartRunRequest";
import { getJson, handleApi, postJson } from "@/services/api";
import type { StartRunResponse } from "@/types/runs";
import { useRunSession } from "@/lib/runSession";
import { useAirports } from "@/hooks/useAirports";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import type { VueloDTO, PedidoDTO } from "@/hooks/useRunSSE";
import { useFlightsSSE } from "@/hooks/useFlightsSSE";
import type { CancelarVueloRequest, CancelarVueloResponse } from "@/types/vuelos";
import type { ForceReplanResponse } from "@/types/planificacion";

type NivelCarga = "disponible" | "limitado" | "saturado";

type ApplyPayload = {
  inicio?: Date;
  fin?: Date;
  niveles: Record<NivelCarga, boolean>;
  region?: string;
  ciudad?: string;
  vuelo?: string | null;
  almacen?: string | null;
  pedido?: string | null;
};
type Variant = "simulacion" | "operacion" | "colapso";

/** ===== utilidades de estilo ===== */
const GLASS =
  "ring-1 ring-border shadow-lg backdrop-blur-2xl backdrop-saturate-150 " +
  "bg-card/80 supports-[backdrop-filter]:bg-card/50";

const GLASS_SOFT =
  "ring-1 ring-border shadow-sm backdrop-blur-xl backdrop-saturate-150 " +
  "bg-card/70 supports-[backdrop-filter]:bg-card/40";

export default function ToolsPanel({
  variant = "simulacion",
}: {
  onApply?: (filters: ApplyPayload) => void;
  variant?: Variant;
}) {
  // Estado principal
  const [inicio, setInicio] = useState<Date | undefined>();
  const [fin, setFin] = useState<Date | undefined>();

  //Por los cambios del profesor, todos tienen fecha de inicio.
  const showStart = true;
  const showEnd = variant === "simulacion";

  const [niveles, setNiveles] = useState<Record<NivelCarga, boolean>>({
    disponible: true,
    limitado: false,
    saturado: false,
  });

  const [almacen, setAlmacen] = useState<string | null>(null);

  // CONEXIÓN CON EL CONTEXTO GLOBAL
  const { 
    begin, 
    simNow: simNowUtc, 
    windows, 
    selectedAirportId, 
    setSelectedAirport, 
    runId: currentRunId, 
    vuelosCancelados, 
    cancelarVuelo, 
    status, 
    selectedPedido, 
    setSelectedPedido, 
    setPedidoSource,
    selectedVuelo, 
    setSelectedVuelo 
  } = useRunSession();

  // [DEBUG] Log de renderizado principal
  // console.log(`[ToolsPanel] RENDER. RunId: ${currentRunId}, Windows: ${windows.length}, SimNow: ${simNowUtc}`);
  
  // Alias para mantener compatibilidad con tu código de subcomponentes
  const pedido = selectedPedido;
  const setPedido = setSelectedPedido;
  const vuelo = selectedVuelo;
  const setVuelo = setSelectedVuelo;

  const [showPedidosBD, setShowPedidosBD] = useState(false);
  const [pedidosBD, setPedidosBD] = useState<PedidoDTO[]>([]);
  const [loadingPedidosBD, setLoadingPedidosBD] = useState(false);
  const [errorPedidosBD, setErrorPedidosBD] = useState<string | null>(null);

  const fetchPedidosBD = useCallback(async () => {
    setLoadingPedidosBD(true);
    setErrorPedidosBD(null);

    try {
      const [data, error] = await handleApi(
        getJson<PedidoDTO[]>("pedidos/bd")
      );

      if (error) {
        setErrorPedidosBD(String(error));
        setPedidosBD([]);
        return;
      }
      setPedidosBD(Array.isArray(data) ? data : []);

      if (data){
        console.log("Los pedidos de BD son:", pedidosBD)
      }

    }
    catch (e: any) {
    setErrorPedidosBD(e?.message ?? "Error cargando pedidos BD");
    setPedidosBD([]);
  } finally {
    setLoadingPedidosBD(false);
  }

  }, []);

  const { data: airportsData } = useAirports();

  // Obtener vuelos planificados
  const isSimulacionLike = variant === "simulacion" || variant === "colapso";
  const shouldFetchScheduled = isSimulacionLike && !!currentRunId;
  
  const scheduledEndpoint = useMemo(() => {
    if (!shouldFetchScheduled) return null;
    return `vuelos/scheduled/next-day?runId=${currentRunId}`;
  }, [shouldFetchScheduled, currentRunId]);
  
  const { data: scheduledFlightsRaw } = useFlightsSSE(scheduledEndpoint);
  
  const vuelosProgramados = useMemo<VueloDTO[]>(() => {
    if (!shouldFetchScheduled || !scheduledFlightsRaw || !Array.isArray(scheduledFlightsRaw)) {
      return [];
    }
    const mapped = scheduledFlightsRaw.map((v: any) => ({
      id: v.id || "",
      origen: v.origen || "",
      destino: v.destino || "",
      salidaUtc: v.salidaUtc || "",
      llegadaUtc: v.llegadaUtc || "",
      cantidadAsignada: v.cantidadAsignada || 0,
      capacidad: v.capacidad || 0,
      residual: v.residual || 0,
      costo: v.costo || 0,
      carga: v.carga || [],
    })).filter((v: VueloDTO) => 
      v.id && v.origen && v.destino && !vuelosCancelados.has(v.id)
    );
    
    // [DEBUG] Vuelos programados
    if (mapped.length > 0) {
      console.log(`[ToolsPanel] Vuelos Programados cargados: ${mapped.length}`);
    }
    return mapped;
  }, [scheduledFlightsRaw, shouldFetchScheduled, vuelosCancelados]);

  const warehouseOptions = useMemo(() => {
    if (!airportsData) return [];
    return airportsData
      .filter((a) => !a.sede)
      .map((a) => ({
        id: a.codigo,
        label: `${a.ciudad ?? a.codigo} (${a.codigo})`,
      }));
  }, [airportsData]);

  const warehouseLabelMap = useMemo(() => {
    const map = new Map<string, string>();
    warehouseOptions.forEach((a) => map.set(a.id, a.label));
    return map;
  }, [warehouseOptions]);

  // Sincronizar almacén seleccionado con el contexto
  useEffect(() => {
    setAlmacen(selectedAirportId ?? null);
  }, [selectedAirportId]);

  // Limpiar vuelo si se cancela
  useEffect(() => {
    if (vuelo && vuelosCancelados.has(vuelo.id)) {
      console.log(`[ToolsPanel] Vuelo seleccionado ${vuelo.id} fue cancelado, limpiando selección.`);
      setVuelo(null);
    }
  }, [vuelo, vuelosCancelados]);

  const handleSelectAlmacen = (codigo: string) => {
    console.log("[ToolsPanel] Seleccionando almacén:", codigo);
    setAlmacen(codigo);
    setSelectedAirport(codigo);
  };

  const handleInicio = (value?: Date) => {
    setInicio(value);
    if (value){
      const end = new Date(value);
      end.setDate(end.getDate() + 7);
      setFin(end);
    }
    else {
      setFin(undefined);
    }
  }

  // Obtener vuelos activos
  const vuelosActivos = useMemo<VueloDTO[]>(() => {
    // [DEBUG] Inicio cálculo vuelos activos
    // console.log(`[ToolsPanel DEBUG] Calculando VuelosActivos. Windows: ${windows.length}, SimNow: ${simNowUtc}`);

    if (windows.length === 0) return [];
    
    if (!simNowUtc) {
      const lastWindow = windows[windows.length - 1];
      const vuelos = lastWindow?.vuelos ?? [];
      console.log(`[ToolsPanel DEBUG] Sin SimNow, usando última ventana. Vuelos encontrados: ${vuelos.length}`);
      return vuelos.filter(v => !vuelosCancelados.has(v.id));
    }

    const now = new Date(simNowUtc).getTime();
    const vuelosActivosMap = new Map<string, VueloDTO>();
    
    windows.forEach(window => {
      window.vuelos?.forEach(v => {
        if (vuelosCancelados.has(v.id)) return;
        
        if (!vuelosActivosMap.has(v.id)) {
          const salida = new Date(v.salidaUtc).getTime();
          const llegada = new Date(v.llegadaUtc).getTime();
          
          if (variant === "operacion") {
             // En operación diaria mostramos vuelos que aún no han terminado
            if (llegada > now) vuelosActivosMap.set(v.id, v);
          } else {
            // En simulación solo mostramos vuelos EN EL AIRE
            if (now >= salida && now <= llegada) {
                // console.log(`[ToolsPanel DEBUG] Vuelo ACTIVO detectado: ${v.id} (Now: ${now} entre ${salida} y ${llegada})`);
                vuelosActivosMap.set(v.id, v);
            }
          }
        }
      });
    });
    
    const resultado = Array.from(vuelosActivosMap.values());
    
    // Fallback si no hay activos (útil al inicio o si el reloj salta)
    if (resultado.length === 0) {
      // console.warn("[ToolsPanel DEBUG] No se encontraron vuelos activos exactos, buscando en última ventana...");
      const lastWindow = windows[windows.length - 1];
      const vuelos = lastWindow?.vuelos ?? [];
      return vuelos.filter(v => !vuelosCancelados.has(v.id));
    }
    
    console.log(`[ToolsPanel DEBUG] Total Vuelos Activos calculados: ${resultado.length}`);
    return resultado;
  }, [windows, simNowUtc, vuelosCancelados, variant]);

  // Mapa de pedidos
  const pedidosPorIdOperacion = useMemo(() => {
    if (variant !== "operacion") return null;
    const map = new Map<number, PedidoDTO>();
    windows.forEach(window => {
      window.pedidos?.forEach(p => {
        map.set(p.id, p);
      });
    });
    return map;
  }, [windows, variant]);

  // Pedidos activos
  const pedidosActivos = useMemo<PedidoDTO[]>(() => {
    if (variant === "operacion") {
      const pedidosOperacion = pedidosPorIdOperacion ? Array.from(pedidosPorIdOperacion.values()) : [];
      if (pedidosOperacion.length === 0) return [];
      if (!simNowUtc) return pedidosOperacion;

      const now = new Date(simNowUtc).getTime();
      
      return pedidosOperacion.filter(pedido => {
        if (!pedido.rutas || pedido.rutas.length === 0) return true;
        let todosVuelosLlegaron = true;
        
        pedido.rutas.forEach(ruta => {
          ruta.vuelos.forEach(vuelo => {
            const llegada = new Date(vuelo.llegadaUtc).getTime();
            if (now <= llegada) todosVuelosLlegaron = false;
          });
        });
        return !todosVuelosLlegaron;
      });
    }

    if (windows.length === 0) return [];

    const pedidosMap = new Map<number, PedidoDTO>();
    windows.forEach(w => {
      w.pedidos?.forEach(p => pedidosMap.set(p.id, p));
    });
    const pedidosUnicos = Array.from(pedidosMap.values());

    if (!simNowUtc) return pedidosUnicos;

    const now = new Date(simNowUtc).getTime();

    return pedidosUnicos.filter(pedido => {
      if (!pedido.rutas || pedido.rutas.length === 0) return true;
      let llegadaMax = -Infinity;
      pedido.rutas.forEach(ruta => {
        if (!ruta.vuelos || ruta.vuelos.length === 0) return;
        ruta.vuelos.forEach(vuelo => {
          const llegada = new Date(vuelo.llegadaUtc).getTime();
          if (llegada > llegadaMax) llegadaMax = llegada;
        });
      });
      return now <= llegadaMax;
    });
  }, [windows, simNowUtc, variant, pedidosPorIdOperacion]);

  const toggleNivel = (k: NivelCarga) =>
    setNiveles((prev) => ({ ...prev, [k]: !prev[k] }));

  const canApply = (showStart ? Boolean(inicio) : true) && (showEnd ? Boolean(fin) : true);
  const [loading, setLoading] = useState(false);

  const handleRun = async () => {
    console.log("[ToolsPanel] Click en Iniciar Simulación");
    setLoading(true);
    try {
      const req = buildStartRunRequest(variant, {inicio, fin});
      console.log("[ToolsPanel] Payload enviado:", req);
      const [data, error] = await handleApi(postJson<StartRunResponse>("runs", req))

      if (error) {
        console.error("❌ [ToolsPanel] Error al iniciar la simulación:", error);
        toast.custom((t) => <ToastCustom t={t} message={error+"❗"} type="error" />, { duration: 5000});
      } else if (data) {
        console.log("[ToolsPanel] Simulación iniciada. RunID:", data.runId);
        toast.custom((t) => <ToastCustom t={t} message={"¡Simulación iniciada exitosamente!"} type="success" />, { duration: 5000});
        begin(data.runId);
      }
    } catch (err) {
      console.error("💥 [ToolsPanel] Error inesperado:", err);
    } finally {
      setLoading(false);
    }
  }

  const handleClear = () => {
    setInicio(undefined);
    setFin(undefined);
    setNiveles({ disponible: true, limitado: false, saturado: false });
    setVuelo(null);
    setAlmacen(null);
    setPedido(null);
    setSelectedAirport(null);
  };

  const [forcing, setForcing] = useState(false);

  const handleForceReplan = async () => {
    if (variant != "operacion") return;
    if (!currentRunId || status !== "running") {
      toast.custom((t) => <ToastCustom t={t} message={"No hay una simulación de Operación Diaria en ejecución." + "❗"} type="error" />, { duration: 4000 });
      return;
    }

    setForcing(true);
    try {
      const path = `operacionDiaria/${currentRunId}/force`;
      console.log("[ToolsPanel] Forzando replan en:", path);
      const [data, error] = await handleApi(postJson<ForceReplanResponse>(path))

      if (error) {
        console.error("[ToolsPanel] Error al forzar replan:", error);
        toast.custom((t) => <ToastCustom t={t} message={"Error al forzar planificación." + "❗"} type="error" />, { duration: 4000 });
      } else if (data){
        console.log("[ToolsPanel] Replan forzado OK:", data);
        toast.custom((t) => <ToastCustom t={t} message={"Planificación forzada exitosamente!"} type="success" />, { duration: 4000 });
      }
    } catch (err) {
      console.error("[ToolsPanel] Error inesperado al forzar replan:", err);
    } finally {
      setForcing(false);
    }
  }

  const pedidosParaSelector = useMemo(() => {
    return showPedidosBD ? pedidosBD : pedidosActivos;
  }, [showPedidosBD, pedidosBD, pedidosActivos] );

  return (
    <section className="mx-auto max-w-6xl px-3 sm:px-4">
      {/* Selecciones principales (botones) */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4 relative">
        <FlightSelectCard
          runId={currentRunId}
          label="Vuelo"
          icon={<Plane className="h-4 w-4" />}
          placeholder="Seleccionar vuelo"
          value={vuelo}
          items={vuelosActivos}
          onSelect={setVuelo}
          scheduledFlights={isSimulacionLike ? (vuelosProgramados || []) : undefined}
          showScheduledToggle={isSimulacionLike}
          variant={variant}
          simNowUtc={simNowUtc}
          cancelarVuelo={cancelarVuelo}
        />
        <WarehouseSelectCard
          label="Almacén"
          icon={<Building2 className="h-4 w-4" />}
          placeholder="Seleccionar almacén"
          value={almacen ? warehouseLabelMap.get(almacen) ?? `${almacen}` : null}
          items={warehouseOptions}
          onSelect={handleSelectAlmacen}
        />
        <OrderSelectCard
          label="Pedido"
          icon={<Package className="h-4 w-4" />}
          placeholder="Seleccionar pedido"
          value={pedido}
          items={pedidosParaSelector}
          onSelect={setPedido}
          setPedidoSource={setPedidoSource}
          simNowUtc={simNowUtc}
          showBDToggle={variant === "operacion"}         // solo operación diaria
          showPedidosBD={showPedidosBD}
          onTogglePedidosBD={async (next) => {
            setShowPedidosBD(next);
            if (next && pedidosBD.length === 0) {
              await fetchPedidosBD();
            }
          }}
          loadingBD={loadingPedidosBD}
          errorBD={errorPedidosBD}
        />
      </div>

      {/* Barra de control */}
      <div className={`rounded-2xl ${GLASS}`}>
        <div className="flex items-center justify-between px-4 sm:px-5 py-3 border-b border-border">
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-primary" />
            <h3 className="text-foreground font-semibold tracking-wide text-sm">
              Controles de simulación
            </h3>
          </div>
        </div>

        <div className="p-4 sm:p-5 space-y-4 text-foreground">
          {/* rango de fechas */}
          {(showStart || showEnd) && (
            <div className={`grid grid-cols-1 ${showStart && showEnd ? "md:grid-cols-2" : ""} gap-3`}>
              {showStart && (
                <Field label="Fecha de inicio">
                  <DateTimePicker
                    value={inicio}
                    onChange={handleInicio}
                    granularity="minute"
                    hourCycle={24}
                    locale={es}
                    placeholder="dd/MM/aaaa HH:mm"
                    className="picker-trigger"
                  />
                </Field>
              )}
              {showEnd && (
                <Field label="Fecha de fin">
                  <DateTimePicker
                    value={fin}
                    disabled
                    granularity="minute"
                    hourCycle={24}
                    locale={es}
                    placeholder="dd/MM/aaaa HH:mm"
                    className="picker-trigger"
                  />
                </Field>
              )}
            </div>
          )}

          {/* nivel de carga */}
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[11px] font-semibold uppercase tracking-wide mr-1">
              Nivel de carga
            </span>
            <FilterChip label="Disponible" active={niveles.disponible} color="emerald" onClick={() => toggleNivel("disponible")} />
            <FilterChip label="Limitado" active={niveles.limitado} color="amber" onClick={() => toggleNivel("limitado")} />
            <FilterChip label="Saturado" active={niveles.saturado} color="rose" onClick={() => toggleNivel("saturado")} />
          </div>

          {/* acciones */}
          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              onClick={handleClear}
              className={`px-3 py-2 text-sm rounded-full ${GLASS_SOFT} hover:brightness-105 transition inline-flex items-center gap-1`}
              title="Restablecer filtros"
            >
              <X className="h-4 w-4" /> Limpiar
            </button>

            { variant == "operacion" && currentRunId != null && <button
              onClick={handleForceReplan}
              disabled={forcing || !currentRunId || status !== "running" || variant !== "operacion"}
              className={`px-3 py-2 text-sm rounded-full inline-flex items-center gap-1 ${
                  forcing || !currentRunId || status !== "running" || variant !== "operacion"
                    ? "bg-rose-300 text-white/70 cursor-not-allowed"
                    : "bg-rose-600 text-white hover:bg-rose-700"
                }`}
              title={!currentRunId || status !== "running" ? "Requiere una simulación de Operación Diaria en ejecución" : "Forzar una nueva planificación"}
            >
              <RefreshCw className="w-4 h-4" />
              {forcing ? "Forzando..." : "Forzar planificación"}
            </button>
            }

            <button
              onClick={handleRun}
              disabled={!canApply || loading}
              className={`px-3 py-2 text-sm rounded-full transition inline-flex items-center gap-1 ${canApply && !loading ? "bg-primary text-primary-foreground hover:brightness-95" : "bg-primary/50 text-primary-foreground/80 cursor-not-allowed"}`}
              title={loading ? "Iniciando simulación..." : canApply ? "Aplicar filtros" : "Selecciona el rango de fechas"}
            >
              <Check className="h-4 w-4" /> 
              {loading ? "Iniciando..." : "Aplicar"}
            </button>
          </div>
        </div>
      </div>

      <style>{`
        .picker-trigger{
          @apply w-full rounded-lg text-foreground ring-1 ring-border
                bg-card/70 supports-[backdrop-filter]:bg-card/40
                backdrop-blur-xl backdrop-saturate-150
                px-3 py-2 text-sm outline-none hover:brightness-105
                focus:ring-2 focus:ring-ring transition;
        }
        .dense-select{
          @apply w-full rounded-lg text-foreground ring-1 ring-border
                bg-card/70 supports-[backdrop-filter]:bg-card/40
                backdrop-blur-xl backdrop-saturate-150
                px-3 py-2 text-sm;
        }
      `}</style>
    </section>
  );
}

/* ---------- Subcomponentes ---------- */

type WarehouseOption = { id: string; label: string; city?: string; country?: string };

function WarehouseSelectCard({
  label,
  icon,
  placeholder,
  value,
  items,
  onSelect,
}: {
  label: string;
  icon: React.ReactNode;
  placeholder: string;
  value: string | null;
  items: WarehouseOption[];
  onSelect: (val: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [countryFilter, setCountryFilter] = useState<string>("all");

  const countries = useMemo(
    () =>
      Array.from(
        new Set(items.map((opt) => opt.country).filter((c): c is string => Boolean(c)))
      ).sort(),
    [items]
  );

  const filtered = useMemo(
    () =>
      items
        .filter((opt) =>
          opt.label.toLowerCase().includes(q.toLowerCase()) ||
          opt.id.toLowerCase().includes(q.toLowerCase()) ||
          (opt.city ?? "").toLowerCase().includes(q.toLowerCase())
        )
        .filter((opt) => (countryFilter === "all" ? true : opt.country === countryFilter)),
    [items, q, countryFilter]
  );

  const displayValue = value ?? placeholder;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          className={[
            "group w-full text-left relative overflow-hidden rounded-2xl px-4 py-3",
            "text-foreground",
            GLASS,
            "transition hover:shadow-xl hover:-translate-y-[1px] focus:outline-none focus:ring-2 focus:ring-ring",
          ].join(" ")}
          aria-label={`Seleccionar ${label.toLowerCase()}`}
        >
          <div className="flex items-center gap-3">
            <span className="opacity-90 text-primary">{icon}</span>
            <div className="min-w-0">
              <p className="text-[12px] text-muted-foreground leading-tight">{label}</p>
              <p className="text-[17px] font-semibold leading-tight truncate">
                {displayValue}
              </p>
            </div>
          </div>
        </button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className={[
          "w-[min(320px,90vw)] p-3 rounded-xl",
          GLASS,
          "animate-in fade-in-0 zoom-in-95",
        ].join(" ")}
      >
        <div className="space-y-2 mb-2">
          {countries.length > 1 && (
            <Select value={countryFilter} onValueChange={setCountryFilter}>
              <SelectTrigger className="h-8 text-xs">
                <SelectValue placeholder="Filtrar por país" />
              </SelectTrigger>
              <SelectContent className={GLASS_SOFT}>
                <SelectItem value="all">Todos los países</SelectItem>
                {countries.map((country) => (
                  <SelectItem key={country} value={country}>
                    {country}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          <div className="flex items-center gap-2">
            <Search className="h-4 w-4 text-muted-foreground" />
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={`Buscar ${label.toLowerCase()}…`}
              className="h-8 text-sm ring-1 ring-border bg-card/70 supports-[backdrop-filter]:bg-card/20 supports-[backdrop-filter]:backdrop-blur-md"
            />
          </div>
        </div>
        <div className="max-h-56 overflow-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-muted-foreground px-1 py-2">Sin resultados</p>
          )}
          <ul className="space-y-1">
            {filtered.map((opt) => (
              <li key={opt.id}>
                <button
                  onClick={() => {
                    onSelect(opt.id);
                    setOpen(false);
                    setQ("");
                  }}
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-accent/40 text-sm"
                >
                  <div className="flex items-center justify-between">
                    <span>{opt.label}</span>
                    <span className="text-xs text-muted-foreground font-mono">{opt.id}</span>
                  </div>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </PopoverContent>
    </Popover>
  );
}

function FlightSelectCard({
  runId,
  label,
  icon,
  placeholder,
  value,
  items,
  onSelect,
  scheduledFlights,
  showScheduledToggle,
  variant,
  simNowUtc,
  cancelarVuelo,
}: {
  runId: string | null,
  label: string;
  icon: React.ReactNode;
  placeholder: string;
  value: VueloDTO | null;
  items: VueloDTO[];
  onSelect: (val: VueloDTO) => void;
  scheduledFlights?: VueloDTO[];
  showScheduledToggle?: boolean;
  variant?: Variant;
  simNowUtc?: string | null;
  cancelarVuelo?: (vueloId: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [originFilter, setOriginFilter] = useState<string>("all");
  const [destFilter, setDestFilter] = useState<string>("all");
  const [showScheduled, setShowScheduled] = useState(false);
  
  // Determinar qué lista de vuelos usar
  const hasScheduledFlights = showScheduledToggle || (scheduledFlights !== undefined);
  const currentItems = showScheduled && scheduledFlights ? scheduledFlights : items;

  // [DEBUG] Logs dentro del selector
  // console.log(`[FlightSelectCard] Modo Programado: ${showScheduled}. Total Items: ${currentItems.length}`);

  // Función para verificar si un vuelo está en el aire (no se puede cancelar)
  const isVueloEnAire = useCallback((vuelo: VueloDTO): boolean => {
    if (!simNowUtc) return false;
    const now = new Date(simNowUtc).getTime();
    const salida = new Date(vuelo.salidaUtc).getTime();
    const llegada = new Date(vuelo.llegadaUtc).getTime();
    return now >= salida && now <= llegada;
  }, [simNowUtc]);

  const origins = useMemo(
    () => Array.from(new Set(currentItems.map((v) => v.origen))).sort(),
    [currentItems]
  );
  const dests = useMemo(
    () => Array.from(new Set(currentItems.map((v) => v.destino))).sort(),
    [currentItems]
  );

  const filtered = useMemo(
    () =>
      currentItems
        .filter((v) =>
          v.id.toLowerCase().includes(q.toLowerCase()) ||
          v.origen.toLowerCase().includes(q.toLowerCase()) ||
          v.destino.toLowerCase().includes(q.toLowerCase())
        )
        .filter((v) => (originFilter === "all" ? true : v.origen === originFilter))
        .filter((v) => (destFilter === "all" ? true : v.destino === destFilter)),
    [currentItems, q, originFilter, destFilter]
  );

  const handleCancelar = async (vuelo: VueloDTO, e: React.MouseEvent) => {
    e.stopPropagation();

    // Validar que el vuelo no esté en el aire (solo para operación diaria)
    if (variant === "operacion" && simNowUtc) {
      const now = new Date(simNowUtc).getTime();
      const salida = new Date(vuelo.salidaUtc).getTime();
      const llegada = new Date(vuelo.llegadaUtc).getTime();
      if (now >= salida && now <= llegada) {
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={"No se puede cancelar: el vuelo está en el aire"+"❗"}
            type="error"
          />),
        { duration: 5000});
        return;
      }
    }

    console.log(`[FlightSelectCard] Solicitando cancelación vuelo ${vuelo.id} en runId ${runId}`);

    try {
      const req : CancelarVueloRequest = {
        origen: vuelo.origen,
        destino: vuelo.destino,
        salidaUtc: vuelo.salidaUtc,
        llegadaUtc: vuelo.llegadaUtc
      }

      if (runId == null) return;

      const path = `vuelos/${runId}/cancelar`;

      const [data, error] = await handleApi(
        postJson<CancelarVueloResponse>(path, req)
      )

      if (error) {
        console.error("❌ [ToolsPanel] Error al cancelar el vuelo:", error);
        toast.custom((t) => (
          <ToastCustom t={t} message={error+"❗"} type="error" />),
        { duration: 5000});

      } else if (data) {
        if (cancelarVuelo) {
          cancelarVuelo(vuelo.id);
        }
        toast.custom((t) => (
          <ToastCustom t={t} message={"Vuelo cancelado exitosamente!"} type="success" />),
        { duration: 5000});
      }
    }
    catch(err) {
      console.error("💥 [ToolsPanel] Error inesperado:", err);
    }
  };

  // Helper para renderizar el contenido de la tarjeta de vuelo (Id, Ruta, Fecha)
  const renderFlightContent = (v: VueloDTO) => (
    <>
      <div className="flex items-center justify-between mb-1">
        <span className="font-mono text-xs truncate">{v.id}</span>
      </div>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <span className="font-mono">{v.origen}</span>
        <span>→</span>
        <span className="font-mono">{v.destino}</span>
      </div>
      <div className="mt-1 text-xs text-muted-foreground">
        {new Date(v.salidaUtc).toLocaleString('es-PE', {
          day: '2-digit',
          month: '2-digit',
          year: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
          timeZone: 'UTC'
        })} UTC
      </div>
    </>
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          className={[
            "group w-full text-left relative overflow-hidden rounded-2xl px-4 py-3",
            "text-foreground",
            GLASS,
            "transition hover:shadow-xl hover:-translate-y-[1px] focus:outline-none focus:ring-2 focus:ring-ring",
          ].join(" ")}
          aria-label={`Seleccionar ${label.toLowerCase()}`}
        >
          <div className="flex items-center gap-3">
            <span className="opacity-90 text-primary">{icon}</span>
            <div className="min-w-0">
              <p className="text-[12px] text-muted-foreground leading-tight">{label}</p>
              <p className="text-[17px] font-semibold leading-tight truncate">
                {value?.id ?? placeholder}
              </p>
            </div>
          </div>
        </button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className={[
          "w-[min(320px,90vw)] p-3 rounded-xl",
          GLASS,
          "animate-in fade-in-0 zoom-in-95",
        ].join(" ")}
      >
        <div className="space-y-2 mb-2">
          {hasScheduledFlights && (
            <div className="flex items-center gap-2 pb-2 border-b border-border/50">
              <button
                onClick={() => { setShowScheduled(false); setQ(""); }}
                className={`flex-1 px-2 py-1.5 text-xs font-medium rounded-md transition-colors ${
                  !showScheduled
                    ? "bg-primary text-primary-foreground"
                    : "bg-card/50 text-muted-foreground hover:bg-card/70"
                }`}
              >
                Activos
              </button>
              <button
                onClick={() => { setShowScheduled(true); setQ(""); }}
                className={`flex-1 px-2 py-1.5 text-xs font-medium rounded-md transition-colors flex items-center justify-center gap-1 ${
                  showScheduled
                    ? "bg-primary text-primary-foreground"
                    : "bg-card/50 text-muted-foreground hover:bg-card/70"
                }`}
              >
                <Calendar className="h-3 w-3" />
                Programados
                {scheduledFlights && scheduledFlights.length > 0 && (
                  <span className="ml-1 text-[10px] opacity-75">({scheduledFlights.length})</span>
                )}
              </button>
            </div>
          )}
          
          {(origins.length > 1 || dests.length > 1) && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {origins.length > 1 && (
                <Select value={originFilter} onValueChange={setOriginFilter}>
                  <SelectTrigger className="h-8 text-xs">
                    <SelectValue placeholder="Origen" />
                  </SelectTrigger>
                  <SelectContent className={GLASS_SOFT}>
                    <SelectItem value="all">Todos los orígenes</SelectItem>
                    {origins.map((origin) => (
                      <SelectItem key={origin} value={origin}>
                        {origin}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
              {dests.length > 1 && (
                <Select value={destFilter} onValueChange={setDestFilter}>
                  <SelectTrigger className="h-8 text-xs">
                    <SelectValue placeholder="Destino" />
                  </SelectTrigger>
                  <SelectContent className={GLASS_SOFT}>
                    <SelectItem value="all">Todos los destinos</SelectItem>
                    {dests.map((dest) => (
                      <SelectItem key={dest} value={dest}>
                        {dest}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </div>
          )}
          <div className="flex items-center gap-2">
            <Search className="h-4 w-4 text-muted-foreground" />
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={`Buscar ${label.toLowerCase()}…`}
              className="h-8 text-sm ring-1 ring-border bg-card/70 supports-[backdrop-filter]:bg-card/20 supports-[backdrop-filter]:backdrop-blur-md"
            />
          </div>
        </div>
        <div className="max-h-56 overflow-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-muted-foreground px-1 py-2">Sin resultados</p>
          )}
          <ul className="space-y-1">
            {filtered.map((v) => (
              <li key={v.id}>
                <div
                  className={`w-full px-2 py-2 rounded-md text-sm ${
                    showScheduled
                      ? "bg-card/50 border border-border/50 hover:bg-card/70"
                      : "hover:bg-accent/40"
                  }`}
                >
                  {showScheduled ? (
                    // --- VISTA DE VUELOS PROGRAMADOS ---
                    <div className="flex items-start justify-between gap-2">
                      <button
                        onClick={() => { 
                            console.log("[FlightSelectCard] Vuelo Programado seleccionado:", v.id);
                            onSelect(v); setOpen(false); setQ(""); 
                        }}
                        className="flex-1 text-left min-w-0"
                      >
                        {renderFlightContent(v)}
                      </button>
                      <button
                        onClick={(e) => handleCancelar(v, e)}
                        className="flex-shrink-0 px-2 py-1 text-xs font-medium text-red-600 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-950/20 rounded border border-red-200 dark:border-red-900/50 transition-colors flex items-center gap-1"
                        title={`Cancelar vuelo ${v.id}`}
                      >
                        <Trash2 className="h-3 w-3" />
                        Cancelar
                      </button>
                    </div>
                  ) : (
                    // --- VISTA DE VUELOS ACTIVOS (ACTUALIZADA) ---
                    variant === "operacion" ? (
                      // Operación Diaria: con botón cancelar y formato detallado
                      (() => {
                        const enVuelo = isVueloEnAire(v);
                        return (
                          <div className="flex items-start justify-between gap-2">
                            <button
                              onClick={() => { 
                                  console.log("[FlightSelectCard] Vuelo Activo seleccionado:", v.id);
                                  onSelect(v); setOpen(false); setQ(""); 
                              }}
                              className="flex-1 text-left min-w-0"
                            >
                              {renderFlightContent(v)}
                            </button>
                            <button
                              onClick={(e) => handleCancelar(v, e)}
                              disabled={enVuelo}
                              className={`flex-shrink-0 px-2 py-1 text-xs font-medium rounded border transition-colors flex items-center gap-1 ${
                                enVuelo
                                  ? "text-muted-foreground bg-muted border-border cursor-not-allowed opacity-50"
                                  : "text-red-600 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-950/20 border-red-200 dark:border-red-900/50"
                              }`}
                              title={enVuelo ? `No se puede cancelar: vuelo en el aire` : `Cancelar vuelo ${v.id}`}
                            >
                              <Trash2 className="h-3 w-3" />
                              Cancelar
                            </button>
                          </div>
                        );
                      })()
                    ) : (
                      // Simulación / Colapso: sin botón cancelar pero con formato detallado
                      <button
                        onClick={() => { 
                            console.log("[FlightSelectCard] Vuelo Simulación seleccionado:", v.id);
                            onSelect(v); setOpen(false); setQ(""); 
                        }}
                        className="w-full text-left"
                      >
                        {renderFlightContent(v)}
                      </button>
                    )
                  )}
                </div>
              </li>
            ))}
          </ul>
        </div>
      </PopoverContent>
    </Popover>
  );
}
function OrderSelectCard({
  label,
  icon,
  placeholder,
  value,
  items,
  onSelect,
  setPedidoSource,
  simNowUtc,
  showBDToggle,
  showPedidosBD,
  onTogglePedidosBD,
  loadingBD,
  errorBD,

}: {
  label: string;
  icon: React.ReactNode;
  placeholder: string;
  value: PedidoDTO | null;
  items: PedidoDTO[];
  onSelect: (val: PedidoDTO) => void;
  setPedidoSource?: (src: "PLANIFICADO" | "BD") => void;
  simNowUtc?: string | null;

  showBDToggle?: boolean;
  showPedidosBD?: boolean;
  onTogglePedidosBD?: (next: boolean) => void;
  loadingBD?: boolean;
  errorBD?: string | null;

}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [estadoFilter, setEstadoFilter] = useState<string>("all");
  const [destinoFilter, setDestinoFilter] = useState<string>("all");

  // Función para calcular el estado basado en cantidadEnVuelo
  const calcularEstado = useCallback((pedido: PedidoDTO): string => {
    if (!pedido.rutas || pedido.rutas.length === 0 || !simNowUtc) {
      if (pedido.rutas && pedido.rutas.length > 0) {
        return "PROGRAMADO";
      }
      return pedido.estadoAsignacion ?? "PENDIENTE";
    }

    const now = new Date(simNowUtc).getTime();
    let cantidadEntregada = 0;
    let cantidadEnCamino = 0;

    pedido.rutas.forEach(ruta => {
      if (!ruta.vuelos || ruta.vuelos.length === 0) return;

      const primerVuelo = ruta.vuelos[0];
      const ultimoVuelo = ruta.vuelos[ruta.vuelos.length - 1];

      const horaSalidaInicial = new Date(primerVuelo.salidaUtc).getTime();
      const horaLlegadaFinal = new Date(ultimoVuelo.llegadaUtc).getTime();

      if (now >= horaLlegadaFinal) {
        cantidadEntregada += ruta.cantidad;
      } else if (now >= horaSalidaInicial) {
        cantidadEnCamino += ruta.cantidad;
      }
    });

    const cantidadTotalProcesada = cantidadEntregada + cantidadEnCamino;

    if (cantidadEntregada >= pedido.cantidad) {
      return "COMPLETO";
    }
    if (cantidadTotalProcesada > 0) {
      return "EN CAMINO";
    }
    if (pedido.rutas.length > 0) {
      return "PROGRAMADO";
    }
    return "PENDIENTE";
  }, [simNowUtc]);

  const estados = useMemo(
    () =>
      Array.from(new Set(items.map((p) => calcularEstado(p)))).sort(),
    [items, calcularEstado]
  );

  const destinos = useMemo(
    () => Array.from(new Set(items.map((p) => p.destino))).sort(),
    [items]
  );

  const filtered = useMemo(
    () =>
      items
        .filter((p) => p.id.toString().includes(q) || p.destino.toLowerCase().includes(q.toLowerCase()))
        .filter((p) => (estadoFilter === "all" ? true : calcularEstado(p) === estadoFilter))
        .filter((p) => (destinoFilter === "all" ? true : p.destino === destinoFilter)),
    [items, q, estadoFilter, destinoFilter, calcularEstado]
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          className={[
            "group w-full text-left relative overflow-hidden rounded-2xl px-4 py-3",
            "text-foreground",
            GLASS,
            "transition hover:shadow-xl hover:-translate-y-[1px] focus:outline-none focus:ring-2 focus:ring-ring",
          ].join(" ")}
          aria-label={`Seleccionar ${label.toLowerCase()}`}
        >
          <div className="flex items-center gap-3">
            <span className="opacity-90 text-primary">{icon}</span>
            <div className="min-w-0">
              <p className="text-[12px] text-muted-foreground leading-tight">{label}</p>
              <p className="text-[17px] font-semibold leading-tight truncate">
                {value ? `PED-${value.id}` : placeholder}
              </p>
            </div>
          </div>
        </button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className={[
          "w-[min(320px,90vw)] p-3 rounded-xl",
          GLASS,
          "animate-in fade-in-0 zoom-in-95",
        ].join(" ")}
      >

        {showPedidosBD && errorBD && (
          <p className="text-xs text-rose-600 px-1 pt-2">
            {errorBD}
          </p>
        )}

        {/* 🔹🔹🔹 AQUÍ VAN LOS BOTONES 🔹🔹🔹 */}
        {showBDToggle && (
          <div className="flex items-center gap-2 pb-2 mb-2 border-b border-border/50">
            <button
              onClick={() => {
                onTogglePedidosBD?.(false);
                setQ("");
              }}
              className={`flex-1 px-2 py-1.5 text-xs font-medium rounded-md transition-colors ${
                !showPedidosBD
                  ? "bg-primary text-primary-foreground"
                  : "bg-card/50 text-muted-foreground hover:bg-card/70"
              }`}
            >
              Planificados
            </button>

            <button
              onClick={() => {
                onTogglePedidosBD?.(true);
                setQ("");
              }}
              className={`flex-1 px-2 py-1.5 text-xs font-medium rounded-md transition-colors ${
                showPedidosBD
                  ? "bg-primary text-primary-foreground"
                  : "bg-card/50 text-muted-foreground hover:bg-card/70"
              }`}
            >
              En BD
              {loadingBD && <span className="ml-1 text-[10px] opacity-70">(…)</span>}
              {!loadingBD && <span className="ml-1 text-[10px] opacity-70">({items.length})</span>}
            </button>
          </div>
        )}

        <div className="space-y-2 mb-2">
          {(estados.length > 1 || destinos.length > 1) && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {estados.length > 1 && (
                <Select value={estadoFilter} onValueChange={setEstadoFilter}>
                  <SelectTrigger className="h-8 text-xs">
                    <SelectValue placeholder="Estado" />
                  </SelectTrigger>
                  <SelectContent className={GLASS_SOFT}>
                    <SelectItem value="all">Todos los estados</SelectItem>
                    {estados.map((estado) => (
                      <SelectItem key={estado} value={estado}>
                        {estado}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
              {destinos.length > 1 && (
                <Select value={destinoFilter} onValueChange={setDestinoFilter}>
                  <SelectTrigger className="h-8 text-xs">
                    <SelectValue placeholder="Destino" />
                  </SelectTrigger>
                  <SelectContent className={GLASS_SOFT}>
                    <SelectItem value="all">Todos los destinos</SelectItem>
                    {destinos.map((dest) => (
                      <SelectItem key={dest} value={dest}>
                        {dest}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </div>
          )}
          <div className="flex items-center gap-2">
            <Search className="h-4 w-4 text-muted-foreground" />
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={`Buscar ${label.toLowerCase()}…`}
              className="h-8 text-sm ring-1 ring-border bg-card/70 supports-[backdrop-filter]:bg-card/20 supports-[backdrop-filter]:backdrop-blur-md"
            />
          </div>
        </div>
        <div className="max-h-56 overflow-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-muted-foreground px-1 py-2">Sin resultados</p>
          )}
          <ul className="space-y-1">
            {filtered.map((p) => (
              <li key={p.id}>
                <button
                  onClick={() => {
                    console.log("[ToolsPanel] Pedido seleccionado:", p.id);
                    onSelect(p);
                    setPedidoSource?.(showPedidosBD ? "BD" : "PLANIFICADO"); 
                    setOpen(false);
                    setQ("");
                  }}
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-accent/40 text-sm"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono">PED-{p.id}</span>
                    {(() => {
                      const estado = showPedidosBD ? "EN BD" : calcularEstado(p);
                      return (
                        <span className={`text-xs px-1.5 py-0.5 rounded ${
                          estado === "COMPLETO" ? "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-200" :
                          estado === "EN CAMINO" ? "bg-blue-100 text-blue-900 dark:bg-blue-900/30 dark:text-blue-200" :
                          estado === "PROGRAMADO" ? "bg-slate-100 text-slate-900 dark:bg-slate-800/60 dark:text-slate-200" :
                          estado === "EN BD" ? "bg-violet-100 text-violet-900 dark:bg-violet-900/30 dark:text-violet-200" :
                          "bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200"
                        }`}>
                          {estado}
                        </span>
                      );
                    })()}
                  </div>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </PopoverContent>
    </Popover>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="block text-xs font-semibold mb-1.5">{label}</span>
      <div className={["rounded-lg p-2", GLASS_SOFT].join(" ")}>
        {children}
      </div>
    </label>
  );
}

function FilterChip({
  label,
  active,
  color,
  onClick,
}: {
  label: string;
  active: boolean;
  color: "emerald" | "amber" | "rose";
  onClick: () => void;
}) {
  const base =
    "text-xs px-3 py-1.5 rounded-full border transition-colors select-none cursor-pointer";
  const palette = active
    ? color === "emerald"
      ? "bg-emerald-100/80 text-emerald-900 border-emerald-300 dark:bg-emerald-200/30 dark:text-emerald-200 dark:border-emerald-300/40"
      : color === "amber"
      ? "bg-amber-100/80 text-amber-900 border-amber-300 dark:bg-amber-200/30 dark:text-amber-200 dark:border-amber-300/40"
      : "bg-rose-100/80 text-rose-900 border-rose-300 dark:bg-rose-200/30 dark:text-rose-200 dark:border-rose-300/40"
    : "bg-card text-foreground/85 border-border hover:bg-accent/40";
  return (
    <button onClick={onClick} className={`${base} ${palette}`}>
      {label}
    </button>
  );
}

export function OperacionDiariaToolsPanel(
  props: Omit<React.ComponentProps<typeof ToolsPanel>, "variant">
) {
  return <ToolsPanel variant="operacion" {...props} />;
}

export function ColapsoToolsPanel(
  props: Omit<React.ComponentProps<typeof ToolsPanel>, "variant">
) {
  return <ToolsPanel variant="colapso" {...props} />;
}