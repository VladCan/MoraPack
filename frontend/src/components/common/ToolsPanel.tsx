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

  const [vuelo, setVuelo] = useState<string | null>(null);
  const [almacen, setAlmacen] = useState<string | null>(null);
  const [pedido, setPedido] = useState<string | null>(null);

  const vuelos = useMemo(() => ["AX123", "AX401", "AX777", "AX920", "AX333"], []);
  const almacenes = useMemo(() => ["WH-LIM01", "WH-BOG02", "WH-MEX03", "WH-SCL04"], []);
  const pedidos = useMemo(() => ["PED-000123", "PED-000301", "PED-000402", "PED-000777"], []);

  const toggleNivel = (k: NivelCarga) =>
    setNiveles((prev) => ({ ...prev, [k]: !prev[k] }));

  const canApply =
    (showStart ? Boolean(inicio) : true) && (showEnd ? Boolean(fin) : true);


  const [loading, setLoading] = useState(false);
  const {begin} = useRunSession();

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
      {/* Selecciones principales (botones) */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4">
        <SelectCard
          label="Vuelo"
          icon={<Plane className="h-4 w-4" />}
          placeholder="Seleccionar vuelo"
          value={vuelo}
          items={vuelos}
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
        <SelectCard
          label="Pedido"
          icon={<Package className="h-4 w-4" />}
          placeholder="Seleccionar pedido"
          value={pedido}
          items={pedidos}
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
