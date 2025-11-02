import * as React from "react";
import { useMemo, useState } from "react";
import {
  Building2,
  Plane,
  Package,
  Filter,
  MapPin,
  X,
  Check,
  ChevronDown,
  Search,
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
import { handleApi, postJson } from "@/services/api";
import type { StartRunResponse } from "@/types/runs";
import { useRunSession } from "@/lib/runSession";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import type { VueloDTO, PedidoDTO } from "@/hooks/useRunSSE";
import { useRunSSE } from "@/hooks/useRunSSE";

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

/** ===== utilidades de estilo (mismo lenguaje que tu reloj) ===== */
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
  const showStart = variant !== "operacion";
  const showEnd = variant === "simulacion";

  const [niveles, setNiveles] = useState<Record<NivelCarga, boolean>>({
    disponible: true,
    limitado: false,
    saturado: false,
  });

  const [region, setRegion] = useState<string | undefined>(undefined);
  const [ciudad, setCiudad] = useState<string | undefined>(undefined);

  const [showAdvanced, setShowAdvanced] = useState(false);

  const [vuelo, setVuelo] = useState<VueloDTO | null>(null);
  const [almacen, setAlmacen] = useState<string | null>(null);
  const [pedido, setPedido] = useState<PedidoDTO | null>(null);

  const {begin, runId} = useRunSession();
  const {windows, simNowUtc} = useRunSSE(runId || undefined);

  // Obtener SOLO vuelos que están EN EL AIRE en este momento
  const vuelosActivos = useMemo<VueloDTO[]>(() => {
    if (!simNowUtc || windows.length === 0) return [];
    
    const now = new Date(simNowUtc).getTime();
    const vuelosEnAire = new Map<string, VueloDTO>();
    
    windows.forEach(window => {
      window.vuelos.forEach(v => {
        if (!vuelosEnAire.has(v.id)) {
          const salida = new Date(v.salidaUtc).getTime();
          const llegada = new Date(v.llegadaUtc).getTime();
          
          // Solo incluir si está en el aire AHORA
          if (now >= salida && now <= llegada) {
            vuelosEnAire.set(v.id, v);
          }
        }
      });
    });
    
    const resultado = Array.from(vuelosEnAire.values());
    console.log('[ToolsPanel] Vuelos EN EL AIRE:', resultado.length);
    return resultado;
  }, [windows, simNowUtc]);

  // Obtener SOLO pedidos que están en vuelos activos
  const pedidosActivos = useMemo<PedidoDTO[]>(() => {
    if (!simNowUtc || windows.length === 0) return [];
    
    const now = new Date(simNowUtc).getTime();
    
    // Recopilar IDs de pedidos que están en vuelos activos
    const pedidosEnVueloSet = new Set<number>();
    windows.forEach(window => {
      window.vuelos.forEach(vuelo => {
        const salida = new Date(vuelo.salidaUtc).getTime();
        const llegada = new Date(vuelo.llegadaUtc).getTime();
        // Solo considerar vuelos que están en el aire AHORA
        if (now >= salida && now <= llegada) {
          // Agregar los IDs de los pedidos en la carga de este vuelo
          vuelo.carga?.forEach(item => {
            pedidosEnVueloSet.add(item.pedidoId);
          });
        }
      });
    });
    
    // Filtrar pedidos que están en vuelo AHORA
    const lastWindow = windows[windows.length - 1];
    const resultado = (lastWindow?.pedidos || []).filter(p => pedidosEnVueloSet.has(p.id));
    console.log('[ToolsPanel] Pedidos EN VUELO:', resultado.length);
    return resultado;
  }, [windows, simNowUtc]);

  const almacenes = useMemo(() => ["WH-LIM01", "WH-BOG02", "WH-MEX03", "WH-SCL04"], []);

  const toggleNivel = (k: NivelCarga) =>
    setNiveles((prev) => ({ ...prev, [k]: !prev[k] }));

  const canApply =
    (showStart ? Boolean(inicio) : true) && (showEnd ? Boolean(fin) : true);


  const [loading, setLoading] = useState(false);

  const handleRun = async () => {
    console.log("🚀 [ToolsPanel] Iniciando run...");
    console.log("📅 [ToolsPanel] Fechas seleccionadas:", { inicio, fin });
    console.log("📊 [ToolsPanel] Variant:", variant);
    
    setLoading(true);
    try {
      const req = buildStartRunRequest(variant, {inicio, fin});

      console.log("📤 [ToolsPanel] Request construido:", req);
      console.log("🌐 [ToolsPanel] API URL:", import.meta.env.VITE_API_BASE_URL);

      const [data, error] = await handleApi(
        postJson<StartRunResponse>("runs", req)
      )

      if (error) {
        // aquí tu toast o UI de error
        console.error("❌ [ToolsPanel] Error al iniciar la simulación:", error);
        //alert(`Error al iniciar simulación: ${error.message}`);
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={error+"❗"}
            type="error"
          />),
        { duration: 5000});

      } else if (data) {
        // éxito
        console.log("✅ [ToolsPanel] Simulación iniciada exitosamente:", data);
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={"¡Simulación iniciada exitosamente!"+"✅"}
            type="success"
          />),
        { duration: 5000});
        
        //Colocamos lo necesario en el hook
        begin(data.runId);

        //setShowContent(false); //opcional para cerrar el panel
        //navigate("/simulacion"); 

        console.log("🎯 [ToolsPanel] Run iniciado con ID:", data.runId);
      }
    } catch (err) {
      console.error("💥 [ToolsPanel] Error inesperado:", err);
    }
    finally {
      setLoading(false);
    }

  }

  const handleClear = () => {
    setInicio(undefined);
    setFin(undefined);
    setRegion(undefined);
    setCiudad(undefined);
    setNiveles({ disponible: true, limitado: false, saturado: false });
    setVuelo(null);
    setAlmacen(null);
    setPedido(null);
  };

  return (
    <section className="mx-auto max-w-6xl px-3 sm:px-4">
      {/* Tooltips detallados */}
      {vuelo && (
        <div className="absolute top-20 right-4 z-50 w-80 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto mb-4">
          <div className="space-y-3">
            <div className="flex items-center justify-between border-b border-border pb-2">
              <div className="flex items-center gap-2">
                <Plane className="w-4 h-4 text-primary" />
                <h3 className="font-semibold text-lg">{vuelo.id}</h3>
              </div>
              <button onClick={() => setVuelo(null)} className="text-muted-foreground hover:text-foreground">
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="grid grid-cols-2 gap-2 text-sm">
              <div>
                <p className="text-muted-foreground text-xs">Origen</p>
                <p className="font-mono text-xs">{vuelo.origen}</p>
              </div>
              <div>
                <p className="text-muted-foreground text-xs">Destino</p>
                <p className="font-mono text-xs">{vuelo.destino}</p>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2 text-sm">
              <div>
                <p className="text-muted-foreground text-xs">Salida</p>
                <p className="font-mono text-xs">
                  {new Date(vuelo.salidaUtc).toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' })} UTC
                </p>
              </div>
              <div>
                <p className="text-muted-foreground text-xs">Llegada</p>
                <p className="font-mono text-xs">
                  {new Date(vuelo.llegadaUtc).toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' })} UTC
                </p>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-muted-foreground">Carga</span>
                <span className="font-semibold">
                  {vuelo.cantidadAsignada} / {vuelo.capacidad}
                </span>
              </div>
              <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                <div
                  className="h-full bg-primary transition-all"
                  style={{ width: `${(vuelo.cantidadAsignada / vuelo.capacidad) * 100}%` }}
                />
              </div>
            </div>
            {vuelo.carga.length > 0 && (
              <div>
                <p className="text-sm font-semibold mb-2">
                  Carga ({vuelo.carga.length} {vuelo.carga.length === 1 ? 'pedido' : 'pedidos'})
                </p>
                <div className="max-h-40 overflow-y-auto space-y-1">
                  {vuelo.carga.map((item, idx) => (
                    <div key={idx} className="flex items-center justify-between text-xs p-2 rounded-lg bg-muted/50">
                      <div className="flex items-center gap-2">
                        <span className="font-mono font-semibold">#{item.pedidoId}</span>
                        {item.esConexion && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200">
                            Conexión
                          </span>
                        )}
                      </div>
                      <div className="text-right">
                        <p className="font-semibold">{item.cantidad} uds</p>
                        <p className="text-muted-foreground text-[10px]">→ {item.destinoFinal}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {pedido && (
        <div className="absolute top-20 right-4 z-50 w-96 max-h-[80vh] overflow-y-auto p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto mb-4">
          <div className="space-y-3">
            <div className="flex items-center justify-between border-b border-border pb-2 sticky top-0 bg-card/90 backdrop-blur-sm">
              <div className="flex items-center gap-2">
                <Package className="w-4 h-4 text-primary" />
                <h3 className="font-semibold text-lg">PED-{pedido.id}</h3>
              </div>
              <button onClick={() => setPedido(null)} className="text-muted-foreground hover:text-foreground">
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="grid grid-cols-2 gap-2 text-sm">
              <div>
                <p className="text-muted-foreground text-xs">Cliente</p>
                <p className="font-mono text-xs">#{pedido.idCliente}</p>
              </div>
              <div>
                <p className="text-muted-foreground text-xs">Destino</p>
                <p className="font-mono text-xs">{pedido.destino}</p>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-muted-foreground">Estado</span>
                <span className={`font-semibold ${
                  pedido.estadoAsignacion === "COMPLETO" ? "text-emerald-600" :
                  pedido.estadoAsignacion === "PARCIAL" ? "text-amber-600" : "text-rose-600"
                }`}>
                  {pedido.estadoAsignacion}
                </span>
              </div>
              <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                <div
                  className={`h-full transition-all ${
                    pedido.estadoAsignacion === "COMPLETO" ? "bg-emerald-600" :
                    pedido.estadoAsignacion === "PARCIAL" ? "bg-amber-600" : "bg-rose-600"
                  }`}
                  style={{ width: `${(pedido.cantidadAsignada / pedido.cantidad) * 100}%` }}
                />
              </div>
              <p className="text-center text-xs text-muted-foreground mt-1">
                {pedido.cantidadAsignada} / {pedido.cantidad} unidades
              </p>
            </div>
            {pedido.fechaCreacion && (
              <div className="text-xs text-muted-foreground">
                <p className="font-semibold">Fecha de creación</p>
                <p className="font-mono">{new Date(pedido.fechaCreacion).toLocaleString('es-PE', { timeZone: 'UTC' })} UTC</p>
              </div>
            )}
            {/* NUEVO: Desglose de rutas de entrega */}
            {pedido.rutas && pedido.rutas.length > 0 && (
              <div className="border-t border-border pt-2">
                <p className="text-sm font-semibold mb-2">
                  Rutas de entrega ({pedido.rutas.length} {pedido.rutas.length === 1 ? 'ruta' : 'rutas'})
                </p>
                <div className="space-y-2 max-h-64 overflow-y-auto">
                  {pedido.rutas.map((ruta, idx) => (
                    <div key={idx} className="p-2 rounded-lg bg-muted/50 border border-border/50">
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-2">
                          <span className="font-semibold text-xs">{ruta.cantidad} uds</span>
                          <span className="text-xs text-muted-foreground">
                            {ruta.origen} → {ruta.destinoFinal}
                          </span>
                        </div>
                      </div>
                      {ruta.vuelos.length > 0 && (
                        <div className="ml-2 space-y-1 border-l-2 border-primary/30 pl-2">
                          {ruta.vuelos.map((vuelo, vIdx) => (
                            <div key={vIdx} className="flex items-center justify-between text-xs bg-card/50 rounded px-2 py-1">
                              <div>
                                <span className="font-mono">{vuelo.origen}→{vuelo.destino}</span>
                              </div>
                              <div className="text-muted-foreground">
                                {new Date(vuelo.salidaUtc).toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' })}
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Selecciones principales (botones) */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4 relative">
        <FlightSelectCard
          label="Vuelo"
          icon={<Plane className="h-4 w-4" />}
          placeholder="Seleccionar vuelo"
          value={vuelo}
          items={vuelosActivos}
          onSelect={setVuelo}
        />
        <SelectCard
          label="Almacén"
          icon={<Building2 className="h-4 w-4" />}
          placeholder="Seleccionar almacén"
          value={almacen}
          items={almacenes}
          onSelect={setAlmacen}
        />
        <OrderSelectCard
          label="Pedido"
          icon={<Package className="h-4 w-4" />}
          placeholder="Seleccionar pedido"
          value={pedido}
          items={pedidosActivos}
          onSelect={setPedido}
        />
      </div>

      {/* Barra de control con glassmorphism “estilo reloj” */}
      <div className={`rounded-2xl ${GLASS}`}>
        {/* encabezado */}
        <div className="flex items-center justify-between px-4 sm:px-5 py-3 border-b border-border">
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-primary" />
            <h3 className="text-foreground font-semibold tracking-wide text-sm">
              Controles de simulación
            </h3>
          </div>
          <button
            className="inline-flex items-center gap-1 text-xs text-foreground/90 hover:opacity-80"
            onClick={() => setShowAdvanced((s) => !s)}
          >
            <MapPin className="h-4 w-4 text-primary" />
            {showAdvanced ? "Ocultar filtros" : "Más filtros"}
            <ChevronDown
              className={`h-4 w-4 transition-transform ${showAdvanced ? "rotate-180" : ""}`}
            />
          </button>
        </div>

        {/* contenido */}
        <div className="p-4 sm:p-5 space-y-4 text-foreground">
          {/* rango de fechas */}
          {(showStart || showEnd) && (
            <div
              className={`grid grid-cols-1 ${showStart && showEnd ? "md:grid-cols-2" : ""} gap-3`}
            >
              {showStart && (
                <Field label="Fecha de inicio">
                  <DateTimePicker
                    value={inicio}
                    onChange={setInicio}
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
                    onChange={setFin}
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
            <FilterChip
              label="Disponible"
              active={niveles.disponible}
              color="emerald"
              onClick={() => toggleNivel("disponible")}
            />
            <FilterChip
              label="Limitado"
              active={niveles.limitado}
              color="amber"
              onClick={() => toggleNivel("limitado")}
            />
            <FilterChip
              label="Saturado"
              active={niveles.saturado}
              color="rose"
              onClick={() => toggleNivel("saturado")}
            />
          </div>

          {/* filtros avanzados */}
          {showAdvanced && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-2">
              <Field label="Región">
                <Select
                  value={region ?? "all"}
                  onValueChange={(v) => setRegion(v === "all" ? undefined : v)}
                >
                  <SelectTrigger className="dense-select">
                    <SelectValue placeholder="Todas" />
                  </SelectTrigger>
                  <SelectContent className={GLASS_SOFT}>
                    <SelectItem value="all">Todas</SelectItem>
                    <SelectItem value="andina">Andina</SelectItem>
                    <SelectItem value="amazonica">Amazónica</SelectItem>
                    <SelectItem value="costa">Costa</SelectItem>
                  </SelectContent>
                </Select>
              </Field>

              <Field label="Ciudad">
                <Select
                  value={ciudad ?? "all"}
                  onValueChange={(v) => setCiudad(v === "all" ? undefined : v)}
                >
                  <SelectTrigger className="dense-select">
                    <SelectValue placeholder="Todas" />
                  </SelectTrigger>
                  <SelectContent className={GLASS_SOFT}>
                    <SelectItem value="all">Todas</SelectItem>
                    <SelectItem value="lima">Lima</SelectItem>
                    <SelectItem value="cusco">Cusco</SelectItem>
                    <SelectItem value="piura">Piura</SelectItem>
                  </SelectContent>
                </Select>
              </Field>
            </div>
          )}

          {/* acciones */}
          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              onClick={handleClear}
              className={`px-3 py-2 text-sm rounded-full ${GLASS_SOFT} hover:brightness-105 transition inline-flex items-center gap-1`}
              title="Restablecer filtros"
            >
              <X className="h-4 w-4" /> Limpiar
            </button>
            <button
              onClick={handleRun}
              disabled={!canApply || loading}
              className={`px-3 py-2 text-sm rounded-full transition inline-flex items-center gap-1
                ${canApply && !loading ? "bg-primary text-primary-foreground hover:brightness-95" : "bg-primary/50 text-primary-foreground/80 cursor-not-allowed"}
              `}
              title={
                loading ? "Iniciando simulación..." : 
                canApply ? "Aplicar filtros" : 
                "Selecciona el rango de fechas"
              }
            >
              <Check className="h-4 w-4" /> 
              {loading ? "Iniciando..." : "Aplicar"}
            </button>
          </div>
        </div>
      </div>

      {/* utilidades de estilo */}
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

function SelectCard({
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
  items: string[];
  onSelect: (val: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");

  const filtered = useMemo(
    () => items.filter((i) => i.toLowerCase().includes(q.toLowerCase())),
    [items, q]
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
                {value ?? placeholder}
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
        <div className="flex items-center gap-2 mb-2">
          <Search className="h-4 w-4 text-muted-foreground" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={`Buscar ${label.toLowerCase()}…`}
            className="h-8 text-sm ring-1 ring-border bg-card/70 supports-[backdrop-filter]:bg-card/20 supports-[backdrop-filter]:backdrop-blur-md"
          />
        </div>
        <div className="max-h-56 overflow-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-muted-foreground px-1 py-2">Sin resultados</p>
          )}
          <ul className="space-y-1">
            {filtered.map((it) => (
              <li key={it}>
                <button
                  onClick={() => {
                    onSelect(it);
                    setOpen(false);
                    setQ("");
                  }}
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-accent/40 text-sm"
                >
                  {it}
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
  value: VueloDTO | null;
  items: VueloDTO[];
  onSelect: (val: VueloDTO) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");

  const filtered = useMemo(
    () => items.filter((v) => v.id.toLowerCase().includes(q.toLowerCase())),
    [items, q]
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
        <div className="flex items-center gap-2 mb-2">
          <Search className="h-4 w-4 text-muted-foreground" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={`Buscar ${label.toLowerCase()}…`}
            className="h-8 text-sm ring-1 ring-border bg-card/70 supports-[backdrop-filter]:bg-card/20 supports-[backdrop-filter]:backdrop-blur-md"
          />
        </div>
        <div className="max-h-56 overflow-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-muted-foreground px-1 py-2">Sin resultados</p>
          )}
          <ul className="space-y-1">
            {filtered.map((v) => (
              <li key={v.id}>
                <button
                  onClick={() => {
                    onSelect(v);
                    setOpen(false);
                    setQ("");
                  }}
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-accent/40 text-sm"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono">{v.id}</span>
                    <span className="text-xs text-muted-foreground">
                      {v.origen} → {v.destino}
                    </span>
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

function OrderSelectCard({
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
  value: PedidoDTO | null;
  items: PedidoDTO[];
  onSelect: (val: PedidoDTO) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");

  const filtered = useMemo(
    () => items.filter((p) => p.id.toString().includes(q)),
    [items, q]
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
        <div className="flex items-center gap-2 mb-2">
          <Search className="h-4 w-4 text-muted-foreground" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={`Buscar ${label.toLowerCase()}…`}
            className="h-8 text-sm ring-1 ring-border bg-card/70 supports-[backdrop-filter]:bg-card/20 supports-[backdrop-filter]:backdrop-blur-md"
          />
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
                    onSelect(p);
                    setOpen(false);
                    setQ("");
                  }}
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-accent/40 text-sm"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono">PED-{p.id}</span>
                    <span className={`text-xs px-1.5 py-0.5 rounded ${
                      p.estadoAsignacion === "COMPLETO" ? "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-200" :
                      p.estadoAsignacion === "PARCIAL" ? "bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200" :
                      "bg-rose-100 text-rose-900 dark:bg-rose-900/30 dark:text-rose-200"
                    }`}>
                      {p.estadoAsignacion}
                    </span>
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
