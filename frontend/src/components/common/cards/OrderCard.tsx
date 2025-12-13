import { useMemo } from "react";
import { Package, X, MapPin, Truck, ArrowRight } from "lucide-react";
import type { PedidoDTO } from "@/hooks/useRunSSE";

interface OrderCardProps {
  pedido: PedidoDTO;
  simNowUtc?: string | null;
  onClose: () => void;
  variant?: "simulacion" | "operacion" | "colapso";
}

export default function OrderCard({ pedido, simNowUtc, onClose, variant = "simulacion" }: OrderCardProps) {
  
  const pedidoState = useMemo(() => {
    if (!pedido || !pedido.rutas || !simNowUtc) {
      return { cantidadEnCurso: 0, estado: "PENDIENTE", progreso: 0 };
    }

    const now = new Date(simNowUtc).getTime();
    let cantidadEntregada = 0;
    let cantidadEnCamino = 0;

    // Recorremos las rutas para ver el estado real de la carga
    pedido.rutas.forEach(ruta => {
        if (!ruta.vuelos || ruta.vuelos.length === 0) return;

        // Obtenemos el inicio absoluto y el fin absoluto de esta ruta (cadena de vuelos)
        const primerVuelo = ruta.vuelos[0];
        const ultimoVuelo = ruta.vuelos[ruta.vuelos.length - 1];

        const horaSalidaInicial = new Date(primerVuelo.salidaUtc).getTime();
        const horaLlegadaFinal = new Date(ultimoVuelo.llegadaUtc).getTime();

        // 1. ¿Ya llegó al destino final?
        if (now >= horaLlegadaFinal) {
            cantidadEntregada += ruta.cantidad;
        } 
        // 2. ¿Ya salió pero no ha llegado (vuelo o escala)?
        else if (now >= horaSalidaInicial) {
            cantidadEnCamino += ruta.cantidad;
        }
        // 3. Si no, está esperando en origen (no sumamos nada a los contadores de progreso)
    });

    const cantidadTotalProcesada = cantidadEntregada + cantidadEnCamino;

    // Calcular estado global
    let estado = "PENDIENTE";
    
    // Lógica unificada para todos los modos (Operación y Simulación)
    // Usamos tolerancias simples: Si todo lo entregado iguala al total -> Completo
    if (cantidadEntregada >= pedido.cantidad) {
        estado = "COMPLETO";
    } 
    else if (cantidadTotalProcesada > 0) {
        estado = "EN CAMINO"; // Cubre vuelo y escalas
    } 
    else {
        // Si hay rutas asignadas pero aún no salen -> Programado
        if (pedido.rutas.length > 0) estado = "PROGRAMADO";
        else estado = "PENDIENTE";
    }

    return {
      cantidadEnCurso: cantidadTotalProcesada, // Para la barra visual (incluye lo entregado y lo que viaja)
      estado,
      progreso: (cantidadTotalProcesada / pedido.cantidad) * 100,
    };
  }, [pedido, simNowUtc, variant]);

  // Colores según estado
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
    <div className="fixed bottom-6 right-6 z-50 w-80 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl shadow-2xl ring-1 ring-black/5 overflow-hidden animate-in slide-in-from-bottom-4 fade-in duration-300 font-sans">
      
      {/* HEADER */}
      <div className="bg-slate-50 dark:bg-slate-800/50 px-4 py-3 border-b border-slate-100 dark:border-slate-700 flex justify-between items-start">
        <div className="flex gap-3">
           <div className={`p-2 rounded-lg bg-white dark:bg-slate-700 shadow-sm border border-slate-100 dark:border-slate-600`}>
              <Package className="w-5 h-5 text-indigo-600 dark:text-indigo-400" />
           </div>
           <div>
              <h3 className="font-bold text-slate-800 dark:text-white leading-tight">Pedido #{pedido.id}</h3>
              <p className="text-xs text-slate-400">Cliente {pedido.idCliente}</p>
           </div>
        </div>
        <button 
          onClick={onClose} 
          className="text-slate-400 hover:text-red-500 transition-colors -mt-1 -mr-1 p-1"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* BODY */}
      <div className="p-4 space-y-4">
        
        {/* Info Principal */}
        <div className="flex justify-between items-center">
            <div className="flex flex-col">
                <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Destino</span>
                <div className="flex items-center gap-1 text-lg font-bold text-slate-700 dark:text-slate-200">
                    <MapPin className="w-4 h-4 text-red-500" />
                    {pedido.destino}
                </div>
            </div>
            
            <div className={`px-2.5 py-1 rounded-full text-[10px] font-bold border ${statusColors[pedidoState.estado as keyof typeof statusColors] || statusColors.PENDIENTE}`}>
                {pedidoState.estado}
            </div>
        </div>

        {/* Barra de Progreso */}
        <div>
            <div className="flex justify-between text-xs mb-1.5">
                <span className="font-medium text-slate-500">Progreso de envío</span>
                <span className="font-bold text-slate-700 dark:text-slate-300">{pedidoState.cantidadEnCurso} / {pedido.cantidad} uds</span>
            </div>
            <div className="h-2 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                <div 
                    className={`h-full transition-all duration-700 ease-out ${progressColors[pedidoState.estado as keyof typeof progressColors] || "bg-slate-400"}`}
                    style={{ 
                        // CORRECCIÓN VISUAL: Si es 0 real, width es 0%. Si es > 0, mínimo 5% para que se note.
                        width: `${pedidoState.progreso <= 0 ? 0 : Math.max(5, pedidoState.progreso)}%` 
                    }} 
                />
            </div>
        </div>

        {/* Lista de Rutas */}
        {pedido.rutas && pedido.rutas.length > 0 && (
            <div className="pt-2 border-t border-slate-100 dark:border-slate-800">
                <div className="flex items-center gap-1.5 mb-2">
                    <Truck className="w-3.5 h-3.5 text-slate-400" />
                    <span className="text-xs font-semibold text-slate-500">Detalle de Transporte</span>
                </div>
                
                <div className="max-h-[140px] overflow-y-auto scrollbar-thin scrollbar-thumb-slate-200 pr-1 space-y-2">
                    {pedido.rutas.map((ruta, idx) => (
                        <div key={idx} className="bg-slate-50 dark:bg-slate-800/30 rounded-lg p-2.5 border border-slate-100 dark:border-slate-700/50">
                            {/* Cabecera Ruta */}
                            <div className="flex justify-between items-center mb-2">
                                <div className="flex items-center gap-1.5 text-xs font-bold text-slate-700 dark:text-slate-200">
                                    <span>{ruta.origen}</span>
                                    <ArrowRight className="w-3 h-3 text-slate-400" />
                                    <span>{ruta.destinoFinal}</span>
                                </div>
                                <span className="text-[10px] font-bold bg-white dark:bg-slate-700 px-1.5 py-0.5 rounded border border-slate-200 dark:border-slate-600 text-slate-500">
                                    {ruta.cantidad} uds
                                </span>
                            </div>

                            {/* Tramos (Vuelos) */}
                            <div className="relative pl-3 space-y-3 border-l-2 border-slate-200 dark:border-slate-700 ml-1">
                                {ruta.vuelos.map((v, vIdx) => {
                                    // Verificamos si este tramo específico ya ocurrió
                                    const tramoCompletado = simNowUtc && new Date(simNowUtc).getTime() >= new Date(v.llegadaUtc).getTime();
                                    const tramoEnCurso = simNowUtc && new Date(simNowUtc).getTime() >= new Date(v.salidaUtc).getTime() && new Date(simNowUtc).getTime() < new Date(v.llegadaUtc).getTime();

                                    return (
                                        <div key={vIdx} className="relative group">
                                            {/* Bolita de timeline (Color dinámico según estado del tramo) */}
                                            <div className={`absolute -left-[17px] top-1.5 w-2 h-2 rounded-full border-2 border-white dark:border-slate-900 transition-colors ${
                                                tramoCompletado ? "bg-emerald-500" : 
                                                tramoEnCurso ? "bg-blue-500 animate-pulse" : "bg-slate-300 dark:bg-slate-600"
                                            }`} />
                                            
                                            <div className="flex justify-between items-start text-[10px]">
                                                <div className="flex flex-col">
                                                    <span className={`font-mono font-semibold transition-colors ${tramoCompletado ? "text-emerald-700" : tramoEnCurso ? "text-blue-600" : "text-slate-600 dark:text-slate-300"}`}>
                                                        {v.origen}-{v.destino}
                                                    </span>
                                                </div>
                                                <div className="text-right text-slate-400 font-mono">
                                                    {new Date(v.salidaUtc).toLocaleTimeString("es-PE", { hour: "2-digit", minute: "2-digit", timeZone: "UTC" })}
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