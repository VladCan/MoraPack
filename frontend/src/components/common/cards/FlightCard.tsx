import { useMemo } from "react";
import { Plane, X, Box, PlaneLanding } from "lucide-react";

// Interfaz unificada
export interface FlightCardData {
  id: string;
  origen: string;
  destino: string;
  salidaUtc: string;
  llegadaUtc: string;
  capacidad: number;
  cantidadAsignada: number;
  carga: Array<{
    pedidoId: number;
    cantidad: number;
    destinoFinal: string;
    esConexion: boolean;
  }>;
  planeColor?: string;
  pathColor?: string;
  progress?: number; 
}

interface FlightCardProps {
  data: FlightCardData;
  simNowUtc?: string | null;
  onClose: () => void;
  isHover?: boolean;
}

// Helper para obtener el color base según la ocupación
const getStatusColor = (ocupacion: number) => {
  if (ocupacion > 0.8) return "orange"; // Carga Alta
  if (ocupacion > 0.5) return "yellow"; // Carga Media
  return "blue";   // Carga Baja
};

export default function FlightCard({ data, simNowUtc, onClose, isHover }: FlightCardProps) {
  
  const computed = useMemo(() => {
    let progress = data.progress ?? 0;
    
    const cap = data.capacidad || 1;
    const ocupacion = data.cantidadAsignada / cap;
    
    // Si no viene el progreso pre-calculado, lo calculamos aquí
    if (data.progress === undefined && simNowUtc) {
      const now = new Date(simNowUtc).getTime();
      const start = new Date(data.salidaUtc).getTime();
      const end = new Date(data.llegadaUtc).getTime();
      const total = Math.max(1, end - start);
      const elapsed = Math.max(0, Math.min(now, end) - start);
      progress = Math.min(1, elapsed / total);
    }

    return { progress, ocupacion };
  }, [data, simNowUtc]);

  // Determinamos el color base
  const statusColor = getStatusColor(computed.ocupacion);

  // Clases dinámicas de Tailwind según el color
  const colorClasses = {
    iconBg: {
      orange: "bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400 border-orange-200",
      yellow: "bg-yellow-100 text-yellow-600 dark:bg-yellow-900/30 dark:text-yellow-400 border-yellow-200",
      blue:   "bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400 border-blue-200"
    },
    bar: {
      orange: "bg-orange-500",
      yellow: "bg-yellow-500",
      blue:   "bg-blue-500"
    },
    text: {
      orange: "text-orange-600 dark:text-orange-400",
      yellow: "text-yellow-600 dark:text-yellow-400",
      blue:   "text-blue-600 dark:text-blue-400"
    },
    plane: {
      orange: "text-orange-600 fill-orange-600",
      yellow: "text-yellow-600 fill-yellow-600",
      blue:   "text-blue-600 fill-blue-600"
    }
  };

  // Formateador de hora corto (HH:mm)
  const formatTime = (dateStr: string) => {
    try {
      return new Date(dateStr).toLocaleTimeString("es-PE", {
        hour: "2-digit",
        minute: "2-digit",
        timeZone: "UTC",
      });
    } catch { return "--:--"; }
  };

  const originTime = formatTime(data.salidaUtc);
  const destTime = formatTime(data.llegadaUtc);

  const formatFlightId = (id: string): string => {
    const regex = /^([A-Z]{4})-([A-Z]{4})-(\d{4})-(\d{2})-(\d{2})T(\d{2})(\d{2})(\d{2})Z$/;
    const match = id.match(regex);

    if (!match) return id;

    // CORRECCIÓN: Quitamos el "_" pero mantenemos la coma al inicio
    const [, origin, dest, year, month, day, hour, min, sec] = match;

    return `${origin} ➝ ${dest} • ${day}/${month}/${year} ${hour}:${min}:${sec} UTC`;
  };
  return (
    <div className="fixed bottom-6 right-6 z-50 w-80 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl shadow-2xl ring-1 ring-black/5 overflow-hidden animate-in slide-in-from-bottom-4 fade-in duration-300 font-sans">
      
      {/* --- HEADER: ID y Botón Cerrar --- */}
      <div className="bg-slate-50 dark:bg-slate-800/50 px-4 py-2 border-b border-slate-100 dark:border-slate-700 flex justify-between items-center">
        <div className="flex items-center gap-2 overflow-hidden">
          {/* Icono dinámico según carga */}
          <div className={`p-1 rounded-md shadow-sm border ${colorClasses.iconBg[statusColor]}`}>
             <Plane className="w-3 h-3" />
          </div>
          <span 
            className="text-[10px] font-mono text-slate-500 whitespace-nowrap" 
            title={data.id} // El tooltip puede mantener el ID original o el formateado
          >
            {formatFlightId(data.id)}
          </span>
        </div>
        {!isHover && (
          <button 
            onClick={onClose} 
            className="text-slate-400 hover:text-red-500 transition-colors p-1"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* --- BODY: Ruta Visual --- */}
      <div className="p-5">
        <div className="flex justify-between items-center mb-1">
            <div className="text-left">
                <div className="text-2xl font-bold text-slate-800 dark:text-white leading-none">{data.origen}</div>
                <div className="text-xs text-slate-400 font-mono mt-1">{originTime} UTC</div>
            </div>

            {/* Visualización de Trayecto */}
            <div className="flex-1 mx-4 relative flex flex-col items-center justify-center h-8">
                {/* Línea base */}
                <div className="w-full h-0.5 bg-slate-200 dark:bg-slate-700 rounded-full relative overflow-visible">
                    {/* Línea de progreso */}
                    <div 
                        className={`h-full transition-all duration-1000 ease-linear ${colorClasses.bar[statusColor]}`}
                        style={{ width: `${computed.progress * 100}%` }}
                    />
                    
                    {/* Avión superpuesto */}
                    <div 
                        // 1. Quitamos -mt-2
                        // 2. Usamos top-1/2 para ubicarlo al centro vertical
                        // 3. El transform en style se encarga del centrado fino
                        className="absolute top-1/2 z-10 transition-all duration-1000 ease-linear"
                        style={{ 
                            left: `${computed.progress * 100}%`, 
                            // Esto centra el punto medio del icono con la línea
                            transform: 'translate(-50%, -50%)' 
                        }}
                    >
                        <Plane 
                            // Usamos rotate-45 porque el icono de Lucide apunta al Noreste (↗)
                            // Al rotar 45° queda mirando a la derecha (➡)
                            className={`w-4 h-4 drop-shadow-md transform rotate-45 ${colorClasses.plane[statusColor]}`} 
                        />
                    </div>
                </div>
                
                {/* Porcentaje debajo */}
                <div className="mt-2 text-[9px] text-slate-400 font-medium">
                    {Math.round(computed.progress * 100)}%
                </div>
            </div>

            <div className="text-right">
                <div className="text-2xl font-bold text-slate-800 dark:text-white leading-none">{data.destino}</div>
                <div className="text-xs text-slate-400 font-mono mt-1">{destTime} UTC</div>
            </div>
        </div>
      </div>

      {/* --- FOOTER: Carga y Manifiesto --- */}
      <div className="bg-slate-50/50 dark:bg-slate-900/50 border-t border-slate-100 dark:border-slate-800">
        
        {/* Barra de Ocupación */}
        <div className="px-4 py-3">
            <div className="flex justify-between items-end mb-1.5">
                <div className="flex items-center gap-1.5">
                    <Box className="w-3.5 h-3.5 text-slate-400" />
                    <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">Carga</span>
                </div>
                <div className="text-xs font-mono">
                    <span className={`font-bold ${colorClasses.text[statusColor]}`}>
                        {data.cantidadAsignada}
                    </span>
                    <span className="text-slate-400">/{data.capacidad}</span>
                </div>
            </div>
            <div className="h-1.5 w-full bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                <div 
                    className={`h-full transition-all duration-500 ${colorClasses.bar[statusColor]}`}
                    style={{ width: `${Math.min(computed.ocupacion * 100, 100)}%` }}
                />
            </div>
        </div>

        {/* Manifiesto Mini (Scrollable) */}
        {data.carga && data.carga.length > 0 && (
            <div className="border-t border-slate-100 dark:border-slate-800 max-h-[300px] overflow-y-auto scrollbar-thin scrollbar-thumb-slate-200 hover:scrollbar-thumb-slate-300">
                {data.carga.map((item, idx) => (
                    <div 
                        key={idx} 
                        className={`flex items-center justify-between px-4 py-2.5 border-b border-slate-50 dark:border-slate-800/50 last:border-0 transition-colors ${
                            item.esConexion 
                                ? "bg-amber-50 dark:bg-amber-900/10 hover:bg-amber-100 dark:hover:bg-amber-900/20" 
                                : "hover:bg-slate-50 dark:hover:bg-slate-800"
                        }`}
                    >
                        <div className="flex items-center gap-2.5">
                            {/* Indicador visual (punto) */}
                            <div className={`w-1.5 h-1.5 rounded-full ${item.esConexion ? 'bg-amber-500' : 'bg-indigo-400'}`} />
                            <div className="flex flex-col">
                                <span className={`text-[14px] font-bold ${item.esConexion ? 'text-amber-800 dark:text-amber-200' : 'text-slate-700 dark:text-slate-200'}`}>
                                    Pedido #{item.pedidoId}
                                </span>
                                {item.esConexion && (
                                    <span className="text-[9px] text-amber-700 bg-amber-100/80 dark:text-amber-300 dark:bg-amber-900/40 px-1.5 rounded-sm w-fit mt-0.5 font-medium border border-amber-200 dark:border-amber-800/50">
                                        Conexión
                                    </span>
                                )}
                            </div>
                        </div>
                        <div className="text-right">
                            <div className={`text-xs font-bold ${item.esConexion ? 'text-amber-800 dark:text-amber-200' : 'text-slate-600 dark:text-slate-300'}`}>
                                {item.cantidad} <span className="text-[9px] font-normal opacity-70">prod.</span>
                            </div>
                            <div className={`flex items-center gap-0.5 justify-end text-[10px] font-mono mt-0.5 ${item.esConexion ? 'text-amber-600/80 dark:text-amber-400' : 'text-slate-400'}`}>
                                <span><PlaneLanding className="w-3 h-3" strokeWidth={2.5} /></span>
                                <span>{item.destinoFinal}</span>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        )}
      </div>
    </div>
  );
}