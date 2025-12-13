import { X, Plane, Package, ArrowDown, ArrowUp } from "lucide-react";

// --- Tipos ---
type FlightType = {
  id: string;
  cantidad: number;
  origen?: string;
  destino?: string;
  salidaUtc: string;
  llegadaUtc: string;
  isPickup?: boolean;
  pedidoId?: number;
};

interface AirportCardProps {
  airportId: string;
  airportName: string;
  data: {
    porcentaje: number;
    ocupacionActual: number;
    capacidadTotal: number;
    disponible: number;
    cargaLlegando?: number;
    cargaSaliendo?: number;
  };
  isSede: boolean;
  flights: {
    llegadas: FlightType[];
    salidas: FlightType[];
  };
  onClose: () => void;
}

export default function AirportCard({
  airportName,
  data,
  isSede,
  flights,
  onClose,
}: AirportCardProps) {

  const formatTime = (isoString: string) => {
    try {
      if (!isoString) return "??:??";
      const d = new Date(isoString);
      const hh = String(d.getUTCHours()).padStart(2, "0");
      const mm = String(d.getUTCMinutes()).padStart(2, "0");
      return `${hh}:${mm}`;
    } catch { return "??:??"; }
  };

  const formatDateShort = (isoString: string) => {
    try {
      if (!isoString) return "";
      const d = new Date(isoString);
      const day = String(d.getUTCDate()).padStart(2, "0");
      const month = String(d.getUTCMonth() + 1).padStart(2, "0");
      return `${day}/${month}`;
    } catch { return ""; }
  };

  const color = data.porcentaje > 0.8 ? "#ef4444" : data.porcentaje > 0.5 ? "#f97316" : "#3b82f6";

  const flightDepartures = flights.salidas.filter(f => !f.isPickup);
  const clientPickups = flights.salidas.filter(f => f.isPickup);
  const flightArrivals = flights.llegadas;

  // --- Componente de Fila Unificado ---
  const FlightItem = ({ flight, type }: { flight: FlightType; type: "arrival" | "departure" | "pickup" }) => {
    const timeStart = formatTime(flight.salidaUtc);
    const timeEnd = formatTime(flight.llegadaUtc);
    const dateStr = formatDateShort(flight.salidaUtc);

    let icon, badgeClass, textClass, label;
    let subLabel: string | null = null; // Nuevo campo para el subtítulo (Cliente)
    let cantidadPrefix;

    switch (type) {
      case "arrival":
        icon = <Plane className="w-4 h-4 text-emerald-500 rotate-90" />;
        badgeClass = "bg-emerald-100 text-emerald-700 border-emerald-200";
        textClass = "text-emerald-900 dark:text-emerald-100";
        label = flight.origen || "Origen";
        cantidadPrefix = "+";
        break;
      case "departure":
        icon = <Plane className="w-4 h-4 text-red-500 -rotate-45" />;
        badgeClass = "bg-red-100 text-red-700 border-red-200";
        textClass = "text-red-900 dark:text-red-100";
        label = flight.destino || "Destino";
        cantidadPrefix = "-";
        break;
      case "pickup":
        icon = <Package className="w-4 h-4 text-indigo-500" />;
        badgeClass = "bg-indigo-100 text-indigo-700 border-indigo-200";
        textClass = "text-indigo-900 dark:text-indigo-100";
        
        // --- LÓGICA VISUAL MEJORADA ---
        if (flight.pedidoId) {
            // Si hay pedido ID, lo mostramos grande y el cliente ("destino") abajo pequeño
            label = `Pedido #${flight.pedidoId}`;
            subLabel = flight.destino || "Cliente";
        } else {
            // Fallback por si acaso
            label = flight.destino || "Cliente";
        }
        
        cantidadPrefix = "-";
        break;
    }

    return (
      <div className="py-2 px-3 border-b border-slate-100 dark:border-slate-800 last:border-0 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
        {/* Fila Superior: Hora y Fecha */}
        <div className="flex justify-between items-center mb-1">
           <div className="flex items-center gap-1.5 text-[10px] text-slate-400 font-mono">
              <span className="font-semibold text-slate-500">{dateStr}</span>
              <span>•</span>
              <span>{timeStart} <span className="text-slate-300">→</span> {timeEnd}</span>
           </div>
           {type !== 'pickup' && (
             <span className="text-[9px] font-bold text-slate-300 bg-slate-50 px-1 rounded border border-slate-100">UTC</span>
           )}
        </div>

        {/* Fila Inferior: Info Principal y Cantidad */}
        <div className="flex justify-between items-center">
           <div className="flex items-center gap-2 overflow-hidden">
              {/* Icono fijo */}
              <div className="shrink-0">{icon}</div>
              
              {/* Contenedor de Textos (Flex Columna) */}
              <div className="flex flex-col truncate max-w-[110px]">
                 {/* Título (Origen/Destino/Pedido) */}
                 <span className={`text-xs font-bold ${textClass} truncate`} title={label}>
                   {label}
                 </span>
                 
                 {/* Subtítulo (Cliente) - Solo aparece si existe subLabel */}
                 {subLabel && (
                    <span className="text-[9px] text-slate-400 font-medium truncate leading-none mt-0.5" title={subLabel}>
                        {subLabel}
                    </span>
                 )}
              </div>
           </div>

           {/* Badge de Cantidad */}
           <div className={`text-xs font-bold px-1.5 py-0.5 rounded border ${badgeClass} min-w-[36px] text-center`}>
              {cantidadPrefix}{flight.cantidad}
           </div>
        </div>
      </div>
    );
  };

  return (
    <div className="fixed bottom-6 right-6 z-50 w-[500px] bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl shadow-2xl animate-in slide-in-from-bottom-4 fade-in duration-300 overflow-hidden font-sans ring-1 ring-black/5">
      
      {/* HEADER: Nombre y Stats */}
      <div className="p-4 border-b border-slate-100 dark:border-slate-800 bg-white dark:bg-slate-900">
         <div className="flex justify-between items-start mb-3">
            <div>
               <h2 className="text-lg font-bold text-slate-800 dark:text-white flex items-center gap-2 leading-tight">
                  {airportName}
                  {isSede && <span className="bg-blue-600 text-white text-[10px] px-1.5 py-0.5 rounded font-bold uppercase tracking-wider shadow-sm shadow-blue-200">Sede</span>}
               </h2>
            </div>
            <button 
              onClick={onClose} 
              className="text-slate-400 hover:text-slate-600 hover:bg-slate-100 p-1.5 rounded-full transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
         </div>

         {!isSede && (
           <div className="space-y-3">
              <div className="flex justify-between items-end">
                 <div className="flex gap-6">
                    <div>
                       <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider block mb-0.5">Ocupación</span>
                       <span className="text-xl font-bold text-slate-700 dark:text-slate-200">
                          {data.ocupacionActual}<span className="text-sm text-slate-400 font-normal">/{data.capacidadTotal}</span>
                       </span>
                    </div>
                    <div>
                       <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider block mb-0.5">Disponible</span>
                       <span className="text-xl font-bold text-slate-700 dark:text-slate-200">{data.disponible}</span>
                    </div>
                 </div>
                 <span className="text-sm font-bold bg-slate-50 px-2 py-1 rounded border border-slate-100" style={{ color }}>
                    {(data.porcentaje * 100).toFixed(0)}%
                 </span>
              </div>
              
              <div className="h-1.5 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                 <div 
                    className="h-full transition-all duration-500 rounded-full"
                    style={{ width: `${Math.min(data.porcentaje * 100, 100)}%`, backgroundColor: color }}
                 />
              </div>
           </div>
         )}
      </div>

      {/* BODY: Dos Columnas Claras */}
      <div className="grid grid-cols-2 h-[350px] divide-x divide-slate-100 dark:divide-slate-800 bg-slate-50/50 dark:bg-slate-900/50">
         
         {/* COLUMNA IZQUIERDA: VUELOS (Llegadas y Salidas) */}
         <div className="flex flex-col h-full overflow-hidden bg-white/50 dark:bg-slate-900/50">
            {/* Sección Llegadas */}
            <div className="flex-1 flex flex-col min-h-0 border-b border-slate-100 dark:border-slate-800">
                <div className="px-3 py-2 bg-emerald-50/50 dark:bg-emerald-900/10 border-b border-emerald-100/50 flex items-center justify-between sticky top-0 z-10 backdrop-blur-sm">
                    <div className="flex items-center gap-1.5">
                        <ArrowDown className="w-3.5 h-3.5 text-emerald-600" />
                        <span className="text-[11px] font-bold text-emerald-800 dark:text-emerald-200 uppercase tracking-wide">Llegadas</span>
                    </div>
                    <span className="text-[10px] font-bold text-emerald-600 bg-white px-1.5 rounded-full border border-emerald-100 shadow-sm">{flightArrivals.length}</span>
                </div>
                <div className="overflow-y-auto scrollbar-thin scrollbar-thumb-slate-200 hover:scrollbar-thumb-slate-300">
                    {flightArrivals.length > 0 ? (
                        flightArrivals.map((v) => <FlightItem key={v.id} flight={v} type="arrival" />)
                    ) : (
                        <div className="h-full flex items-center justify-center text-slate-300 text-xs italic p-4">Sin llegadas</div>
                    )}
                </div>
            </div>

            {/* Sección Salidas */}
            <div className="flex-1 flex flex-col min-h-0">
                <div className="px-3 py-2 bg-red-50/50 dark:bg-red-900/10 border-b border-red-100/50 flex items-center justify-between sticky top-0 z-10 backdrop-blur-sm">
                    <div className="flex items-center gap-1.5">
                        <ArrowUp className="w-3.5 h-3.5 text-red-600" />
                        <span className="text-[11px] font-bold text-red-800 dark:text-red-200 uppercase tracking-wide">Salidas</span>
                    </div>
                    <span className="text-[10px] font-bold text-red-600 bg-white px-1.5 rounded-full border border-red-100 shadow-sm">{flightDepartures.length}</span>
                </div>
                <div className="overflow-y-auto scrollbar-thin scrollbar-thumb-slate-200 hover:scrollbar-thumb-slate-300">
                    {flightDepartures.length > 0 ? (
                        flightDepartures.map((v) => <FlightItem key={v.id} flight={v} type="departure" />)
                    ) : (
                        <div className="h-full flex items-center justify-center text-slate-300 text-xs italic p-4">Sin salidas aéreas</div>
                    )}
                </div>
            </div>
         </div>

         {/* COLUMNA DERECHA: RECOJOS (Columna Completa) */}
         <div className="flex flex-col h-full overflow-hidden bg-indigo-50/30 dark:bg-indigo-900/10">
            <div className="px-3 py-2 bg-indigo-50/80 dark:bg-indigo-900/20 border-b border-indigo-100/50 flex items-center justify-between sticky top-0 z-10 backdrop-blur-sm">
                <div className="flex items-center gap-1.5">
                    <Package className="w-3.5 h-3.5 text-indigo-600" />
                    <span className="text-[11px] font-bold text-indigo-800 dark:text-indigo-200 uppercase tracking-wide">Recojos</span>
                </div>
                <span className="text-[10px] font-bold text-indigo-600 bg-white px-1.5 rounded-full border border-indigo-100 shadow-sm">{clientPickups.length}</span>
            </div>
            
            <div className="flex-1 overflow-y-auto scrollbar-thin scrollbar-thumb-indigo-100 hover:scrollbar-thumb-indigo-200 p-1">
                {clientPickups.length > 0 ? (
                    <div className="space-y-1">
                        {clientPickups.map((v) => <FlightItem key={v.id} flight={v} type="pickup" />)}
                    </div>
                ) : (
                    <div className="h-full flex flex-col items-center justify-center text-indigo-300/60 p-4">
                        <Package className="w-8 h-8 mb-2 opacity-50" />
                        <span className="text-xs italic text-center">No hay clientes<br/>recogiendo carga</span>
                    </div>
                )}
            </div>
         </div>

      </div>
    </div>
  );
}