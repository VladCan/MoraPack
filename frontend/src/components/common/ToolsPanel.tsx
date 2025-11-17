import * as React from "react";
import { useEffect, useMemo, useState, useCallback } from "react";
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
  Calendar,
  Trash2,
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
import { useAirports } from "@/hooks/useAirports";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import type { VueloDTO, PedidoDTO } from "@/hooks/useRunSSE";
import { useFlightsSSE } from "@/hooks/useFlightsSSE";
import type { CancelarVueloRequest, CancelarVueloResponse } from "@/types/vuelos";

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

  const { begin, simNow: simNowUtc, windows, selectedAirportId, setSelectedAirport, runId: currentRunId } = useRunSession();
  const { data: airportsData } = useAirports();

  // Obtener vuelos planificados del día siguiente (solo en modo simulacion/operacion semanal)
  // IMPORTANTE: Solo consumir el endpoint si estamos en modo simulacion
  // Si no estamos en simulacion, no consumir ningún endpoint (evitar consumo innecesario)
  const shouldFetchScheduled = variant === "simulacion" && !!currentRunId;
  
  // Construir el endpoint con el runId si está disponible
  const scheduledEndpoint = useMemo(() => {
    if (!shouldFetchScheduled) return null;
    const endpoint = "vuelos/scheduled/next-day";
    // Si hay un runId activo, pasarlo como parámetro
    return `${endpoint}?runId=${currentRunId}`;
  }, [shouldFetchScheduled, currentRunId]);
  
  const { data: scheduledFlightsRaw } = useFlightsSSE(scheduledEndpoint);
  
  // Debug desactivado
  
  const vuelosProgramados = useMemo<VueloDTO[]>(() => {
    // Solo procesar si estamos en modo simulacion (operacion semanal)
    if (!shouldFetchScheduled) {
      return [];
    }
    
    if (!scheduledFlightsRaw || !Array.isArray(scheduledFlightsRaw)) {
      return [];
    }
    
    // Convertir los datos del endpoint a VueloDTO
    const result = scheduledFlightsRaw.map((v: any) => ({
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
    })).filter((v: VueloDTO) => v.id && v.origen && v.destino);
    
    return result;
  }, [scheduledFlightsRaw, shouldFetchScheduled]);

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

  useEffect(() => {
    setAlmacen(selectedAirportId ?? null);
  }, [selectedAirportId]);

  const handleSelectAlmacen = (codigo: string) => {
    setAlmacen(codigo);
    setSelectedAirport(codigo);
  };

  //Fijar fecha de fin automáticamente al elegir fecha de inicio
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

  // Obtener SOLO vuelos que están EN EL AIRE en este momento
  const vuelosActivos = useMemo<VueloDTO[]>(() => {
    if (windows.length === 0) return [];
    
    // Si aún no tenemos TICK (simNowUtc), mostramos los vuelos de la última ventana.
    if (!simNowUtc) {
      const lastWindow = windows[windows.length - 1];
      return lastWindow?.vuelos ?? [];
    }

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
    if (resultado.length === 0) {
      const lastWindow = windows[windows.length - 1];
      return lastWindow?.vuelos ?? [];
    }
    
    return resultado;
  }, [windows, simNowUtc]);

  // Obtener SOLO pedidos que están en vuelos activos
  const pedidosActivos = useMemo<PedidoDTO[]>(() => {
    if (windows.length === 0) return [];

    if (!simNowUtc) {
      const lastWindow = windows[windows.length - 1];
      return lastWindow?.pedidos ?? [];
    }
    
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
    if (resultado.length === 0) {
      return lastWindow?.pedidos ?? [];
    }

    return resultado;
  }, [windows, simNowUtc]);

  // Calcular cantidad EN EL AIRE del pedido seleccionado
  const cantidadEnVuelo = useMemo(() => {
    if (!pedido || !pedido.rutas || !simNowUtc) return 0;
    
    const now = new Date(simNowUtc).getTime();
    return pedido.rutas.reduce((sum, ruta) => {
      // Solo contar si la ruta tiene AL MENOS un vuelo en el aire
      const tieneVueloEnAire = ruta.vuelos.some(vuelo => {
        const salida = new Date(vuelo.salidaUtc).getTime();
        const llegada = new Date(vuelo.llegadaUtc).getTime();
        return now >= salida && now <= llegada;
      });
      return tieneVueloEnAire ? sum + ruta.cantidad : sum;
    }, 0);
  }, [pedido, simNowUtc]);

  const toggleNivel = (k: NivelCarga) =>
    setNiveles((prev) => ({ ...prev, [k]: !prev[k] }));

  const canApply =
    (showStart ? Boolean(inicio) : true) && (showEnd ? Boolean(fin) : true);


  const [loading, setLoading] = useState(false);

  const handleRun = async () => {
    setLoading(true);
    try {
      const req = buildStartRunRequest(variant, {inicio, fin});

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
    setSelectedAirport(null);
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
                  cantidadEnVuelo >= pedido.cantidad ? "text-emerald-600" :
                  cantidadEnVuelo > 0 ? "text-amber-600" : "text-rose-600"
                }`}>
                  {cantidadEnVuelo >= pedido.cantidad ? "COMPLETO" :
                   cantidadEnVuelo > 0 ? "PARCIAL" : "PENDIENTE"}
                </span>
              </div>
              <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                <div
                  className={`h-full transition-all ${
                    cantidadEnVuelo >= pedido.cantidad ? "bg-emerald-600" :
                    cantidadEnVuelo > 0 ? "bg-amber-600" : "bg-rose-600"
                  }`}
                  style={{ width: `${(cantidadEnVuelo / pedido.cantidad) * 100}%` }}
                />
              </div>
              <p className="text-center text-xs text-muted-foreground mt-1">
                {cantidadEnVuelo} / {pedido.cantidad} unidades
              </p>
            </div>
            {pedido.fechaCreacion && (
              <div className="text-xs text-muted-foreground">
                <p className="font-semibold">Fecha de creación</p>
                <p className="font-mono">{new Date(pedido.fechaCreacion).toLocaleString('es-PE', { timeZone: 'UTC' })} UTC</p>
              </div>
            )}
            {/* NUEVO: Desglose de rutas de entrega */}
            {pedido.rutas && pedido.rutas.length > 0 && (() => {
              // Filtrar rutas que tienen vuelos EN EL AIRE AHORA
              const now = new Date(simNowUtc || Date.now()).getTime();
              const rutasVisibles = pedido.rutas.filter(ruta => {
                return ruta.vuelos.some(vuelo => {
                  const salida = new Date(vuelo.salidaUtc).getTime();
                  const llegada = new Date(vuelo.llegadaUtc).getTime();
                  return now >= salida && now <= llegada;
                });
              });
              
              return rutasVisibles.length > 0 && (
                <div className="border-t border-border pt-2">
                  <p className="text-sm font-semibold mb-2">
                    Rutas activas ({rutasVisibles.length} {rutasVisibles.length === 1 ? 'ruta' : 'rutas'})
                  </p>
                  <div className="space-y-2 max-h-64 overflow-y-auto">
                    {rutasVisibles.map((ruta, idx) => {
                      // Filtrar vuelos de esta ruta que están EN EL AIRE
                      const vuelosActivos = ruta.vuelos.filter(vuelo => {
                        const salida = new Date(vuelo.salidaUtc).getTime();
                        const llegada = new Date(vuelo.llegadaUtc).getTime();
                        return now >= salida && now <= llegada;
                      });
                      
                      return (
                        <div key={idx} className="p-2 rounded-lg bg-muted/50 border border-border/50">
                          <div className="flex items-center justify-between mb-2">
                            <div className="flex items-center gap-2">
                              <span className="font-semibold text-xs">{ruta.cantidad} uds</span>
                              <span className="text-xs text-muted-foreground">
                                {ruta.origen} → {ruta.destinoFinal}
                              </span>
                            </div>
                          </div>
                          {vuelosActivos.length > 0 && (
                            <div className="ml-2 space-y-1 border-l-2 border-primary/30 pl-2">
                              {vuelosActivos.map((vuelo, vIdx) => (
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
                      );
                    })}
                  </div>
                </div>
              );
            })()}
          </div>
        </div>
      )}

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
          scheduledFlights={variant === "simulacion" ? (vuelosProgramados || []) : undefined}
          showScheduledToggle={variant === "simulacion"}
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
          items={pedidosActivos}
          onSelect={setPedido}
          simNowUtc={simNowUtc}
        />
      </div>

      {/* Barra de control con glassmorphism "estilo reloj" */}
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
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [originFilter, setOriginFilter] = useState<string>("all");
  const [destFilter, setDestFilter] = useState<string>("all");
  const [showScheduled, setShowScheduled] = useState(false);
  
  // Determinar qué lista de vuelos usar
  const hasScheduledFlights = showScheduledToggle || (scheduledFlights !== undefined);
  const currentItems = showScheduled && scheduledFlights ? scheduledFlights : items;

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

    try {
      const req : CancelarVueloRequest = {
        origen: vuelo.origen,
        destino: vuelo.destino,
        salidaUtc: vuelo.salidaUtc,
        llegadaUtc: vuelo.llegadaUtc
      }

      //Porseaca xd
      if (runId == null) return;

      const path = `vuelos/${runId}/cancelar`;

      const [data, error] = await handleApi(
        postJson<CancelarVueloResponse>(path, req)
      )

      if (error) {
        // aquí tu toast o UI de error
        console.error("❌ [ToolsPanel] Error al cancelar el vuelo:", error);
        
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={error+"❗"}
            type="error"
          />),
        { duration: 5000});

      } else if (data) {
        // éxito
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={"Vuelo cancelado exitosamente!"+"✅"}
            type="success"
          />),
        { duration: 5000});
      }

    }
    catch(err) {
      console.error("💥 [ToolsPanel] Error inesperado:", err);
    }



    // TODO: Implementar cancelación de vuelo (acciones adicionales si aplica)
  };

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
          {/* Toggle para alternar entre vuelos activos y programados */}
          {hasScheduledFlights && (
            <div className="flex items-center gap-2 pb-2 border-b border-border/50">
              <button
                onClick={() => {
                  setShowScheduled(false);
                  setQ("");
                }}
                className={`flex-1 px-2 py-1.5 text-xs font-medium rounded-md transition-colors ${
                  !showScheduled
                    ? "bg-primary text-primary-foreground"
                    : "bg-card/50 text-muted-foreground hover:bg-card/70"
                }`}
              >
                Activos
              </button>
              <button
                onClick={() => {
                  setShowScheduled(true);
                  setQ("");
                }}
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
                    // Vista de vuelos programados con botón cancelar
                    <div className="flex items-start justify-between gap-2">
                      <button
                        onClick={() => {
                          onSelect(v);
                          setOpen(false);
                          setQ("");
                        }}
                        className="flex-1 text-left min-w-0"
                      >
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
                            hour: '2-digit',
                            minute: '2-digit',
                            timeZone: 'UTC'
                          })} UTC
                        </div>
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
                    // Vista normal de vuelos activos
                    <button
                      onClick={() => {
                        onSelect(v);
                        setOpen(false);
                        setQ("");
                      }}
                      className="w-full text-left"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-mono">{v.id}</span>
                        <span className="text-xs text-muted-foreground">
                          {v.origen} → {v.destino}
                        </span>
                      </div>
                    </button>
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
  simNowUtc,
}: {
  label: string;
  icon: React.ReactNode;
  placeholder: string;
  value: PedidoDTO | null;
  items: PedidoDTO[];
  onSelect: (val: PedidoDTO) => void;
  simNowUtc?: string | null;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [estadoFilter, setEstadoFilter] = useState<string>("all");
  const [destinoFilter, setDestinoFilter] = useState<string>("all");

  // Función para calcular el estado basado en cantidadEnVuelo
  const calcularEstado = useCallback((pedido: PedidoDTO): string => {
    if (!pedido.rutas || !simNowUtc) {
      return pedido.estadoAsignacion;
    }

    const now = new Date(simNowUtc).getTime();
    const cantidadEnVuelo = pedido.rutas.reduce((sum, ruta) => {
      const tieneVueloEnAire = ruta.vuelos.some((vuelo) => {
        const salida = new Date(vuelo.salidaUtc).getTime();
        const llegada = new Date(vuelo.llegadaUtc).getTime();
        return now >= salida && now <= llegada;
      });
      return tieneVueloEnAire ? sum + ruta.cantidad : sum;
    }, 0);

    return cantidadEnVuelo >= pedido.cantidad
      ? "COMPLETO"
      : cantidadEnVuelo > 0
      ? "PARCIAL"
      : "PENDIENTE";
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
                    onSelect(p);
                    setOpen(false);
                    setQ("");
                  }}
                  className="w-full text-left px-2 py-2 rounded-md hover:bg-accent/40 text-sm"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono">PED-{p.id}</span>
                    {(() => {
                      const estado = calcularEstado(p);
                      return (
                        <span className={`text-xs px-1.5 py-0.5 rounded ${
                          estado === "COMPLETO" ? "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-200" :
                          estado === "PARCIAL" ? "bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200" :
                          "bg-rose-100 text-rose-900 dark:bg-rose-900/30 dark:text-rose-200"
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
