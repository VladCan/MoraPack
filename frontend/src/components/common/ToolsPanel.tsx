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
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../ui/select";

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
// ❶ — añade el tipo Variant y la prop en la firma
type Variant = "simulacion" | "operacion" | "colapso";

export default function ToolsPanel({
  onApply,
  variant = "simulacion",
}: {
  onApply?: (filters: ApplyPayload) => void;
  variant?: Variant;
}) {
  // Estado principal
  const [inicio, setInicio] = useState<Date | undefined>();
  const [fin, setFin] = useState<Date | undefined>();
  const showStart = variant !== "operacion"; // en Operación NO hay inicio
  const showEnd = variant === "simulacion"; // solo Simulación tiene fin

  const [niveles, setNiveles] = useState<Record<NivelCarga, boolean>>({
    disponible: true,
    limitado: false,
    saturado: false,
  });

  const [region, setRegion] = useState<string | undefined>(undefined);
  const [ciudad, setCiudad] = useState<string | undefined>(undefined);

  const [showAdvanced, setShowAdvanced] = useState(false);

  // NUEVO: selecciones desde las 3 tarjetas-botón
  const [vuelo, setVuelo] = useState<string | null>(null);
  const [almacen, setAlmacen] = useState<string | null>(null);
  const [pedido, setPedido] = useState<string | null>(null);

  // Mock data (sustituye por tus fuentes reales luego)
  const vuelos = useMemo(
    () => ["AX123", "AX401", "AX777", "AX920", "AX333"],
    []
  );
  const almacenes = useMemo(
    () => ["WH-LIM01", "WH-BOG02", "WH-MEX03", "WH-SCL04"],
    []
  );
  const pedidos = useMemo(
    () => ["PED-000123", "PED-000301", "PED-000402", "PED-000777"],
    []
  );

  const toggleNivel = (k: NivelCarga) =>
    setNiveles((prev) => ({ ...prev, [k]: !prev[k] }));

  const canApply =
    (showStart ? Boolean(inicio) : true) && (showEnd ? Boolean(fin) : true);

  const handleApply = () => {
    const payload: ApplyPayload = {
      inicio: showStart ? inicio : undefined,
      fin: showEnd ? fin : undefined,
      niveles,
      region,
      ciudad,
      vuelo,
      almacen,
      pedido,
    };
    onApply?.(payload);
  };

  const handleClear = () => {
    setInicio(undefined);
    setFin(undefined);
    setRegion("");
    setCiudad("");
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

      {/* Barra de control con contraste alto (menos “vidrio”) */}
      <div className="rounded-2xl bg-white ring-1 ring-black/10 shadow-xl">
        {/* encabezado */}
        <div className="flex items-center justify-between px-4 sm:px-5 py-3 border-b border-black/10">
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-primary" />
            <h3 className="text-primary font-semibold tracking-wide text-sm">
              Controles de simulación
            </h3>
          </div>
          <button
            className="inline-flex items-center gap-1 text-xs text-primary hover:opacity-80"
            onClick={() => setShowAdvanced((s) => !s)}
          >
            <MapPin className="h-4 w-4" />
            {showAdvanced ? "Ocultar filtros" : "Más filtros"}
            <ChevronDown
              className={`h-4 w-4 transition-transform ${
                showAdvanced ? "rotate-180" : ""
              }`}
            />
          </button>
        </div>

        {/* contenido */}
        <div className="p-4 sm:p-5 space-y-4">
          {/* rango de fechas */}
          {(showStart || showEnd) && (
            <div
              className={`grid grid-cols-1 ${
                showStart && showEnd ? "md:grid-cols-2" : ""
              } gap-3 text-primary`}
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

          {/* nivel de carga (chips con mayor contraste) */}
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-primary mr-1">
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
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-2 text-primary">
              <Field label="Región">
                <Select
                  value={region ?? "all"}
                  onValueChange={(v) => setRegion(v === "all" ? undefined : v)}
                >
                  <SelectTrigger className="dense-select">
                    <SelectValue placeholder="Todas" />
                  </SelectTrigger>
                  <SelectContent>
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
                  <SelectContent>
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
              className="px-3 py-2 text-sm rounded-lg bg-white border border-black/10 text-primary hover:bg-blue-50/60 transition inline-flex items-center gap-1"
              title="Restablecer filtros"
            >
              <X className="h-4 w-4" /> Limpiar
            </button>
            <button
              onClick={handleApply}
              disabled={!canApply}
              className={`px-3 py-2 text-sm rounded-lg transition inline-flex items-center gap-1
                ${
                  canApply
                    ? "bg-primary text-white hover:brightness-95"
                    : "bg-primary/50 text-white/80 cursor-not-allowed"
                }
              `}
              title={
                canApply ? "Aplicar filtros" : "Selecciona el rango de fechas"
              }
            >
              <Check className="h-4 w-4" /> Aplicar
            </button>
          </div>
        </div>
      </div>

      {/* utilidades de estilo */}
      <style>{`
        .picker-trigger {
          @apply bg-white text-primary border border-black/10 hover:bg-blue-50/60;
        }
        .dense-input{
          @apply w-full rounded-lg bg-white text-primary border border-black/10
                 px-3 py-2 text-sm outline-none
                 focus:ring-2 focus:ring-primary/30 transition;
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
          className="
            group w-full text-left relative overflow-hidden
            rounded-2xl bg-white text-primary
            border border-black/10 shadow-md transition
            hover:shadow-lg hover:-translate-y-[1px] focus:outline-none
            focus:ring-2 focus:ring-primary/30 px-4 py-3
          "
          aria-label={`Seleccionar ${label.toLowerCase()}`}
        >
          <div className="flex items-center gap-3">
            <span className="opacity-90">{icon}</span>
            <div className="min-w-0">
              <p className="text-[12px] text-primary/80 leading-tight">
                {label}
              </p>
              <p className="text-[17px] font-semibold leading-tight truncate">
                {value ?? placeholder}
              </p>
            </div>
          </div>
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-[min(320px,90vw)] p-3 bg-white border border-black/10 shadow-xl rounded-xl"
      >
        <div className="flex items-center gap-2 mb-2">
          <Search className="h-4 w-4 text-primary/70" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={`Buscar ${label.toLowerCase()}…`}
            className="h-8 text-sm"
          />
        </div>
        <div className="max-h-56 overflow-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-primary/70 px-1 py-2">Sin resultados</p>
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
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-blue-50/80 text-sm"
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
      <span className="block text-primary text-xs font-semibold mb-1.5">
        {label}
      </span>
      <div className="rounded-lg bg-white p-2 border border-black/10">
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

  // ⬇️ CAMBIA ESTO
  const palette = active
    ? color === "emerald"
      ? "bg-emerald-100/80 text-emerald-900 border-emerald-300"
      : color === "amber"
      ? "bg-amber-100/80 text-amber-900 border-amber-300"
      : "bg-rose-100/80 text-rose-900 border-rose-300"
    : "bg-white text-primary/85 border-black/10 hover:bg-blue-50/50";
  // ⬆️ QUITA cualquier `shadow-[inset...]` o `bg-[inset...]`

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