import { useMemo } from "react";
import { Package, X, MapPin, Truck, ArrowRight, PlaneTakeoff, PlaneLanding } from "lucide-react";
import type { PedidoDTO } from "@/hooks/useRunSSE";
import { useAirports } from "@/hooks/useAirports";

interface OrderCardProps {
  pedido: PedidoDTO;
  simNowUtc?: string | null;
  onClose: () => void;
  variant?: "simulacion" | "operacion" | "colapso";
}

export default function OrderCard({ pedido, simNowUtc, onClose, variant = "simulacion" }: OrderCardProps) {
  
  // 1. Obtener datos de aeropuertos para enriquecer la UI
  const { data: airportsData } = useAirports();
  
  // Mapa rápido Código -> Ciudad/Nombre
  const airportsMap = useMemo(() => {
      const map = new Map<string, string>();
      if (airportsData) {
          airportsData.forEach((a) => {
              // Formato: "Lima (SPIM)"
              const nombre = a.ciudad ? `${a.ciudad} (${a.codigo})` : a.codigo;
              map.set(a.codigo, nombre);
          });
      }
      return map;
  }, [airportsData]);

  // Helper para formatear código de aeropuerto (Usado en el detalle y tooltips)
  const formatAirport = (code: string) => airportsMap.get(code) || code;

  const pedidoState = useMemo(() => {
    if (!pedido || !pedido.rutas || !simNowUtc) {
      return { cantidadEnCurso: 0, estado: "PENDIENTE", progreso: 0 };
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
        } 
        else if (now >= horaSalidaInicial) {
            cantidadEnCamino += ruta.cantidad;
        }
    });

    const cantidadTotalProcesada = cantidadEntregada + cantidadEnCamino;

    let estado = "PENDIENTE";
    
    if (cantidadEntregada >= pedido.cantidad) {
        estado = "COMPLETO";
    } 
    else if (cantidadTotalProcesada > 0) {
        estado = "EN CAMINO"; 
    } 
    else {
        if (pedido.rutas.length > 0) estado = "PROGRAMADO";
        else estado = "PENDIENTE";
    }

    return {
      cantidadEnCurso: cantidadTotalProcesada,
      estado,
      progreso: (cantidadTotalProcesada / pedido.cantidad) * 100,
    };
  }, [pedido, simNowUtc, variant]);

  const statusColors = {
      COMPLETO: "bg-emerald-100 text-emerald-700 border-emerald-200",
      "EN CAMINO": "bg-blue-100 text-blue-700 border-blue-200",
      PROGRAMADO: "bg-slate-100 text-slate-700 border-slate-200",
      PENDIENTE: "bg-amber-100 text-amber-700 border-amber-200"
  };

  const progressColors = {
      COMPLETO: "bg-emerald-500",
      "EN CAMINO": "bg-blue-500",
      PROGRAMADO: "bg-slate-400",
      PENDIENTE: "bg-amber-400"
  };

  return (
    <div className="fixed bottom-6 right-6 z-50 w-96 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl shadow-2xl ring-1 ring-black/5 overflow-hidden animate-in slide-in-from-bottom-4 fade-in duration-300 font-sans">
      
      {/* HEADER */}
      <div className="bg-slate-50 dark:bg-slate-800/50 px-5 py-4 border-b border-slate-100 dark:border-slate-700 flex justify-between items-start">
        <div className="flex gap-3 items-center">
           <div className={`p-2.5 rounded-lg bg-white dark:bg-slate-700 shadow-sm border border-slate-100 dark:border-slate-600`}>
             <Package className="w-6 h-6 text-indigo-600 dark:text-indigo-400" />
           </div>
           <div>
             <h3 className="font-bold text-lg text-slate-800 dark:text-white leading-tight">Pedido #{pedido.id}</h3>
             <p className="text-sm text-slate-500">Cliente {pedido.idCliente}</p>
           </div>
        </div>
        <button 
          onClick={onClose} 
          className="text-slate-400 hover:text-red-500 transition-colors -mt-1 -mr-1 p-2"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* BODY */}
      <div className="p-5 space-y-5">
        
        {/* Info Principal */}
        <div className="flex justify-between items-center">
            <div className="flex flex-col max-w-[65%]">
                <span className="text-xs font-bold text-slate-400 uppercase tracking-wider mb-1">Destino Final</span>
                <div className="flex items-center gap-1.5 text-base font-bold text-slate-700 dark:text-slate-200 truncate" title={formatAirport(pedido.destino)}>
                    <MapPin className="w-4 h-4 text-red-500 shrink-0" />
                    <span className="truncate">{formatAirport(pedido.destino)}</span>
                </div>
            </div>
            
            <div className={`px-3 py-1.5 rounded-full text-xs font-bold border ${statusColors[pedidoState.estado as keyof typeof statusColors] || statusColors.PENDIENTE}`}>
                {pedidoState.estado}
            </div>
        </div>

        {/* Barra de Progreso */}
        <div>
            <div className="flex justify-between text-sm mb-2">
                <span className="font-medium text-slate-500">Progreso de envío</span>
                <span className="font-bold text-slate-700 dark:text-slate-300">{pedidoState.cantidadEnCurso} / {pedido.cantidad} productos</span>
            </div>
            <div className="h-2.5 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                <div 
                    className={`h-full transition-all duration-700 ease-out ${progressColors[pedidoState.estado as keyof typeof progressColors] || "bg-slate-400"}`}
                    style={{ 
                        width: `${pedidoState.progreso <= 0 ? 0 : Math.max(5, pedidoState.progreso)}%` 
                    }} 
                />
            </div>
        </div>

        {/* Lista de Rutas */}
        {pedido.rutas && pedido.rutas.length > 0 && (
            <div className="pt-2 border-t border-slate-100 dark:border-slate-800">
                <div className="flex items-center gap-2 mb-3">
                    <Truck className="w-4 h-4 text-slate-400" />
                    <span className="text-sm font-semibold text-slate-600">Ruta de Transporte</span>
                </div>
                
                <div className="max-h-[220px] overflow-y-auto scrollbar-thin scrollbar-thumb-slate-200 pr-1 space-y-3">
                    {pedido.rutas.map((ruta, idx) => (
                        <div key={idx} className="bg-slate-50 dark:bg-slate-800/30 rounded-lg p-3.5 border border-slate-100 dark:border-slate-700/50">
                            
                            {/* === CAMBIO AQUÍ: CABECERA SOLO SIGLAS === */}
                            <div className="flex justify-between items-center mb-3 pb-2 border-b border-slate-200 dark:border-slate-700">
                                <div className="flex items-center gap-2 text-base font-bold text-slate-700 dark:text-slate-200 w-full overflow-hidden">
                                    {/* Usamos font-mono para que parezcan tickets de avión y SOLO la propiedad .origen (el código) */}
                                    <span className="truncate max-w-[40%] font-mono" title={formatAirport(ruta.origen)}>
                                        {ruta.origen}
                                    </span>
                                    
                                    <ArrowRight className="w-3.5 h-3.5 text-slate-400 shrink-0" />
                                    
                                    {/* Solo el código de destino */}
                                    <span className="truncate max-w-[40%] font-mono" title={formatAirport(ruta.destinoFinal)}>
                                        {ruta.destinoFinal}
                                    </span>
                                </div>
                                <span className="text-s font-bold bg-white dark:bg-slate-700 px-2 py-1 rounded border border-slate-200 dark:border-slate-600 text-slate-600 whitespace-nowrap ml-2">
                                    {ruta.cantidad} prod.
                                </span>
                            </div>

                            {/* Tramos (Vuelos) Detallados - AQUÍ SÍ MANTENEMOS EL NOMBRE COMPLETO */}
                            <div className="space-y-4 relative">
                                <div className="absolute left-[5px] top-1 bottom-1 w-0.5 bg-slate-200 dark:bg-slate-700" />

                                {ruta.vuelos.map((v, vIdx) => {
                                    const tramoCompletado = simNowUtc && new Date(simNowUtc).getTime() >= new Date(v.llegadaUtc).getTime();
                                    const tramoEnCurso = simNowUtc && new Date(simNowUtc).getTime() >= new Date(v.salidaUtc).getTime() && new Date(simNowUtc).getTime() < new Date(v.llegadaUtc).getTime();

                                    return (
                                        <div key={vIdx} className="relative pl-5">
                                            {/* Bolita de estado */}
                                            <div className={`absolute left-0 top-1.5 w-3 h-3 rounded-full border-2 border-white dark:border-slate-900 z-10 transition-colors ${
                                                tramoCompletado ? "bg-emerald-500" : 
                                                tramoEnCurso ? "bg-blue-500 animate-pulse" : "bg-slate-300 dark:bg-slate-600"
                                            }`} />
                                            
                                            {/* Info del Vuelo */}
                                            <div className="bg-white dark:bg-slate-800 rounded border border-slate-100 dark:border-slate-700 p-2.5 text-xs shadow-sm">
                                                <div className="flex justify-between items-start mb-1.5 gap-2">
                                                    {/* AQUÍ SÍ usamos formatAirport para dar el detalle completo (Ciudad + Código) */}
                                                    <span className={`font-semibold leading-snug ${tramoEnCurso ? "text-blue-700" : "text-slate-700"}`}>
                                                        {formatAirport(v.origen)} <span className="text-slate-300 mx-0.5">→</span> {formatAirport(v.destino)}
                                                    </span>
                                                    {tramoEnCurso && <span className="text-[10px] bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded font-bold tracking-wider">VOLANDO</span>}
                                                </div>
                                                
                                                {/* Horarios */}
                                                <div className="grid grid-cols-2 gap-3 mt-2 text-slate-600">
                                                    <div className="flex flex-col">
                                                        <div className="flex items-center gap-1 text-[10px] text-slate-400 mb-0.5 uppercase font-bold">
                                                            <PlaneTakeoff className="w-3 h-3" /> Salida
                                                        </div>
                                                        <span className="font-mono bg-slate-50 px-1 rounded w-fit">
                                                            {new Date(v.salidaUtc).toLocaleTimeString("es-PE", {day: "2-digit",month: "2-digit",year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "UTC" })} UTC
                                                        </span>
                                                    </div>
                                                    <div className="flex flex-col text-right items-end">
                                                        <div className="flex items-center justify-end gap-1 text-[10px] text-slate-400 mb-0.5 uppercase font-bold">
                                                            Llegada <PlaneLanding className="w-3 h-3" />
                                                        </div>
                                                        <span className="font-mono bg-slate-50 px-1 rounded w-fit">
                                                            {new Date(v.llegadaUtc).toLocaleTimeString("es-PE", {day: "2-digit",month: "2-digit",year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "UTC" })} UTC
                                                        </span>
                                                    </div>
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        )}
      </div>
    </div>
  );
}