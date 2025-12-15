import { useState, useEffect } from "react";
import { X, Package, Calendar, Warehouse, PlaneTakeoff, PlaneLanding } from "lucide-react";
// Mantenemos tu importación del servicio real
import { listAirports } from "@/services/airports";

// --- Tipos de la API ---
export interface AeropuertoDTO {
  codigo: string;
  ciudad: string;
  pais: string;
  gmt: number | null;
  capacidad: number | null;
  lat: number | null;
  lon: number | null;
  continente: string | null;
  sede: boolean; 
}

// --- Tipos Locales ---
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
  // Estado de las pestañas
  const [activeTab, setActiveTab] = useState<'salidas' | 'llegadas' | 'pedidos'>('salidas');
  const [airportMap, setAirportMap] = useState<Record<string, string>>({});

  // --- 1. Cargar Aeropuertos ---
  useEffect(() => {
    const fetchAirports = async () => {
      try {
        const [response] = await listAirports();
        if (response) {
          const map: Record<string, string> = {};
          response.forEach((aero: AeropuertoDTO) => {
            map[aero.codigo] = aero.ciudad; 
          });
          setAirportMap(map);
        }
      } catch (e) {
        console.error("Error al cargar aeropuertos", e);
      }
    };
    fetchAirports();
  }, []);

  // Helper para obtener nombre ciudad
  const getCityName = (code?: string) => {
    if (!code) return "Desconocido";
    return airportMap[code] || "---"; 
  };

  // --- Formatters ---
  const formatTimeAMPM = (isoString: string) => {
    try {
      if (!isoString) return "--:--";
      const d = new Date(isoString);
      let hours = d.getUTCHours();
      const minutes = d.getUTCMinutes();
      const ampm = hours >= 12 ? "p.m." : "a.m.";
      hours = hours % 12;
      hours = hours ? hours : 12;
      const strMinutes = minutes < 10 ? "0" + minutes : minutes;
      return `${hours}:${strMinutes} ${ampm}`;
    } catch { return "--:--"; }
  };

  const formatDateFull = (isoString: string) => {
    try {
      if (!isoString) return "";
      const d = new Date(isoString);
      const day = String(d.getUTCDate()).padStart(2, "0");
      const month = String(d.getUTCMonth() + 1).padStart(2, "0");
      const year = d.getUTCFullYear();
      return `${day}/${month}/${year}`;
    } catch { return ""; }
  };

  // Lógica de colores de capacidad
  const color = data.porcentaje > 0.8 ? "#ef4444" : data.porcentaje > 0.5 ? "#f97316" : "#3b82f6";
  const ocupacionLabel = data.porcentaje > 0.8 ? "Crítico" : data.porcentaje > 0.5 ? "Alto" : "Normal";

  // --- Filtrado de Datos ---
  const flightDepartures = flights.salidas.filter(f => !f.isPickup);
  const clientPickups = flights.salidas.filter(f => f.isPickup);
  const flightArrivals = flights.llegadas;

  // --- Componente de Fila ---
  const FlightItem = ({ flight, type }: { flight: FlightType; type: "arrival" | "departure" | "pickup" }) => {
    const timeStart = formatTimeAMPM(flight.salidaUtc);
    const timeEnd = formatTimeAMPM(flight.llegadaUtc);
    const dateStr = formatDateFull(flight.salidaUtc);

    let icon, badgeClass, textClass;
    let cantidadPrefix;
    let displayText = "";

    switch (type) {
      case "arrival":
        icon = <PlaneLanding className="w-4 h-4 text-emerald-600" />;
        badgeClass = "bg-emerald-50 text-emerald-700 border-emerald-200";
        textClass = "text-emerald-900 dark:text-emerald-100";
        // FORMATO SOLICITADO: Ciudad (CODIGO)
        displayText = `${getCityName(flight.origen)} (${flight.origen || '---'})`;
        cantidadPrefix = "+";
        break;
      case "departure":
        icon = <PlaneTakeoff className="w-4 h-4 text-indigo-600" />;
        badgeClass = "bg-indigo-50 text-indigo-700 border-indigo-200";
        textClass = "text-indigo-900 dark:text-indigo-100";
        // FORMATO SOLICITADO: Ciudad (CODIGO)
        displayText = `${getCityName(flight.destino)} (${flight.destino || '---'})`;
        cantidadPrefix = "-";
        break;
      case "pickup":
        // RESTAURADO: Color Indigo original
        icon = <Package className="w-4 h-4 text-indigo-600" />;
        badgeClass = "bg-indigo-50 text-indigo-700 border-indigo-200";
        textClass = "text-indigo-900 dark:text-indigo-100";
        displayText = `${flight.destino || 'Cliente Final'} (PED #${flight.pedidoId})`;
        cantidadPrefix = "-";
        break;
    }

    return (
      <div className="py-3 px-4 border-b border-slate-300 dark:border-slate-600 last:border-0 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors group">
        <div className="flex justify-between items-start mb-2">
           <div className="flex items-center gap-3 overflow-hidden">
              <div className="shrink-0 bg-white dark:bg-slate-800 p-1.5 rounded-md border border-slate-200 dark:border-slate-700 shadow-sm">
                {icon}
              </div>
              <div className="flex flex-col truncate">
                 {/* Nombre en formato solicitado: Bruselas (EBCI) */}
                 <span className={`text-sm font-bold ${textClass} truncate`} title={displayText}>
                    {displayText}
                 </span>
                 {type !== 'pickup' && (
                   <span className="text-[10px] text-slate-400 font-medium truncate">
                      {type === 'arrival' ? 'Origen Vuelo' : 'Destino Vuelo'}
                   </span>
                 )}
                 {type === 'pickup' && (
                    <span className="text-[10px] text-slate-400 font-medium truncate">
                      Recojo Cliente
                    </span>
                 )}
              </div>
           </div>
           
           <div className={`text-xs font-bold px-2 py-1 rounded border ${badgeClass} shadow-sm whitespace-nowrap`}>
              {cantidadPrefix}{flight.cantidad} <span className="text-[9px] font-normal opacity-80">prod.</span>
           </div>
        </div>

        {/* Bloque de Hora */}
        <div className="bg-slate-50 dark:bg-slate-800/50 rounded-md p-2 text-[10px] text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700">
           <div className="flex items-center gap-2 mb-1.5 pb-1.5 border-b border-slate-200/50 dark:border-slate-700/50">
              <Calendar className="w-3 h-3 text-slate-400" />
              <span className="font-semibold text-slate-700 dark:text-slate-300">{dateStr}</span>
              <span className="ml-auto text-[11px] font-bold text-black border border-slate-200 rounded px-1">UTC</span>
           </div>
           
           <div className="flex justify-between items-center font-mono text-[11px]">
             {type === 'pickup' ? (
                 <>
                   <span className="text-slate-500">Lapso de recojo:</span>
                   <div className="flex gap-1 font-medium">
                     <span>{timeStart}</span>
                     <span className="text-slate-300">➜</span>
                     <span>{timeEnd}</span>
                   </div>
                 </>
             ) : (
               <>
                 <div className="grid grid-cols-2 gap-4 w-full">
                  {/* COLUMNA IZQUIERDA: SALIDA */}
                  <div className="flex flex-col items-start gap-1">
                    {/* Título: Flex para alinear ícono + texto perfectamente */}
                    <div className="flex items-center gap-1.5 text-slate-500">
                      <PlaneTakeoff className="w-4 h-4" strokeWidth={2.5} /> {/* Ícono a la izquierda */}
                      <span className="text-[10px] uppercase font-bold tracking-wide leading-none">
                        Salida
                      </span>
                    </div>
                    {/* Hora: Con fondo gris suave y bordes redondeados */}
                    <div className="bg-slate-100 dark:bg-slate-800 px-2 py-1 rounded text-slate-700 dark:text-slate-300 font-mono text-sm font-medium">
                      {timeStart}
                    </div>
                  </div>

                  {/* COLUMNA DERECHA: LLEGADA */}
                  <div className="flex flex-col items-end gap-1">
                    {/* Título: Flex para alinear texto + ícono */}
                    <div className="flex items-center gap-1.5 text-slate-500">
                      <span className="text-[10px] uppercase font-bold tracking-wide leading-none">
                        Llegada
                      </span>
                      <PlaneLanding className="w-4 h-4" strokeWidth={2.5} /> {/* Ícono a la derecha */}
                    </div>
                    {/* Hora: Alineada a la derecha con fondo */}
                    <div className="bg-slate-100 dark:bg-slate-800 px-2 py-1 rounded text-slate-700 dark:text-slate-300 font-mono text-sm font-medium">
                      {timeEnd}
                    </div>
                  </div>

                </div>
               </>
             )}
           </div>
        </div>
      </div>
    );
  };

  return (
    // CORREGIDO: Altura restaurada a h-[550px]
    <div className="fixed bottom-6 right-6 z-50 w-[360px] h-[500px] max-h-[90vh] flex flex-col bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl shadow-2xl animate-in slide-in-from-bottom-4 fade-in duration-300 font-sans ring-1 ring-black/5">
      
      {/* HEADER */}
      <div className="shrink-0 bg-white dark:bg-slate-900 rounded-t-xl z-20">
         <div className="px-4 py-3 flex justify-between items-center border-b border-slate-100 dark:border-slate-800">
            <h2 className="text-base font-bold text-slate-800 dark:text-white flex items-center gap-2 truncate pr-2">
               {airportName}
               {isSede && <span className="bg-blue-600 text-white text-[9px] px-1.5 py-0.5 rounded font-bold uppercase tracking-wide">Sede</span>}
            </h2>
            <button 
              onClick={onClose} 
              className="text-slate-400 hover:text-red-500 hover:bg-red-50 p-1.5 rounded-full transition-all shrink-0"
            >
              <X className="w-4 h-4" />
            </button>
         </div>

         {!isSede && (
            <div className="px-5 py-2 bg-slate-50/50 dark:bg-slate-800/30 border-b border-slate-100 dark:border-slate-800">
               <div className="flex items-center justify-between">
                  <div className="flex items-center gap-0.5">
                    <Warehouse className="w-3.5 h-3.5 text-slate-400" />
                    <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Estado del Almacén</span>
                  </div>
                  <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-white border border-slate-200" style={{ color }}>{ocupacionLabel}</span>
               </div>
               
               <div className="flex justify-between items-end mb-1">
                  <div className="flex flex-col">
                     <span className="text-3xl font-bold text-slate-800 dark:text-white leading-none tracking-tight">{data.ocupacionActual}</span>
                     <span className="text-[10px] font-medium text-slate-400 mt-1 uppercase tracking-wide">Prod. Almacenados</span>
                  </div>
                  <div className="flex flex-col items-end">
                     <span className="text-xl font-bold text-slate-400 dark:text-slate-500 leading-none">{data.capacidadTotal}</span>
                     <span className="text-[10px] font-medium text-slate-400 mt-1 uppercase tracking-wide">Capacidad max.</span>
                  </div>
               </div>

               <div className="relative">
                  <div className="flex justify-between text-[9px] font-bold text-slate-400 mb-1">
                     <span>Ocupación</span>
                     <span>{(data.porcentaje * 100).toFixed(0)}%</span>
                  </div>
                  <div className="h-2 w-full bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                     <div className="h-full transition-all duration-500 rounded-full" style={{ width: `${Math.min(data.porcentaje * 100, 100)}%`, backgroundColor: color }} />
                  </div>
               </div>
            </div>
         )}

         {/* SELECTOR DE TABS */}
         <div className="px-2 pt-2 pb-2 bg-white dark:bg-slate-900 border-b border-slate-100 dark:border-slate-800">
            <div className="p-1 bg-slate-100 dark:bg-slate-800 rounded-lg grid grid-cols-3 gap-1">
                <button
                    onClick={() => setActiveTab('salidas')}
                    className={`text-[10px] font-bold py-1.5 rounded-md transition-all flex flex-col items-center justify-center gap-1 leading-none ${
                    activeTab === 'salidas' 
                    ? "bg-white dark:bg-slate-700 text-indigo-600 shadow-sm ring-1 ring-black/5" 
                    : "text-slate-500 hover:text-slate-700 hover:bg-slate-200/50"
                    }`}
                >
                    <PlaneTakeoff className="w-3.5 h-3.5" />
                    <span>Salidas ({flightDepartures.length})</span>
                </button>
                
                <button
                    onClick={() => setActiveTab('llegadas')}
                    className={`text-[10px] font-bold py-1.5 rounded-md transition-all flex flex-col items-center justify-center gap-1 leading-none ${
                    activeTab === 'llegadas' 
                    ? "bg-white dark:bg-slate-700 text-emerald-600 shadow-sm ring-1 ring-black/5" 
                    : "text-slate-500 hover:text-slate-700 hover:bg-slate-200/50"
                    }`}
                >
                    <PlaneLanding className="w-3.5 h-3.5" />
                    <span>Llegadas ({flightArrivals.length})</span>
                </button>

                {/* CORREGIDO: Color restaurado a Indigo para Pedidos */}
                <button
                    onClick={() => setActiveTab('pedidos')}
                    className={`text-[10px] font-bold py-1.5 rounded-md transition-all flex flex-col items-center justify-center gap-1 leading-none ${
                    activeTab === 'pedidos' 
                    ? "bg-white dark:bg-slate-700 text-indigo-600 shadow-sm ring-1 ring-black/5" 
                    : "text-slate-500 hover:text-slate-700 hover:bg-slate-200/50"
                    }`}
                >
                    <Package className="w-3.5 h-3.5" />
                    <span>Pedidos ({clientPickups.length})</span>
                </button>
            </div>
         </div>
      </div>

      {/* CONTENIDO PRINCIPAL */}
      <div className="flex-1 overflow-y-auto scrollbar-thin scrollbar-thumb-slate-200 dark:scrollbar-thumb-slate-700 bg-slate-50/30">
         
         {activeTab === 'salidas' && (
            <div className="animate-in fade-in slide-in-from-right-2 duration-200">
               {flightDepartures.length > 0 ? (
                  flightDepartures.map((v) => <FlightItem key={v.id} flight={v} type="departure" />)
               ) : (
                  <EmptyState text="No hay salidas programadas" icon={<PlaneTakeoff className="w-8 h-8 opacity-20" />} />
               )}
            </div>
         )}

         {activeTab === 'llegadas' && (
            <div className="animate-in fade-in slide-in-from-left-2 duration-200">
               {flightArrivals.length > 0 ? (
                  flightArrivals.map((v) => <FlightItem key={v.id} flight={v} type="arrival" />)
               ) : (
                  <EmptyState text="No hay llegadas programadas" icon={<PlaneLanding className="w-8 h-8 opacity-20" />} />
               )}
            </div>
         )}

         {activeTab === 'pedidos' && (
            <div className="animate-in fade-in zoom-in-95 duration-200">
               {clientPickups.length > 0 ? (
                  clientPickups.map((v) => <FlightItem key={v.id} flight={v} type="pickup" />)
               ) : (
                  <EmptyState text="Sin pedidos pendientes" icon={<Package className="w-8 h-8 opacity-20" />} />
               )}
            </div>
         )}

      </div>
    </div>
  );
}

// Componente simple para estado vacío
function EmptyState({ text, icon }: { text: string; icon: React.ReactNode }) {
  return (
    <div className="h-64 flex flex-col items-center justify-center text-slate-400 gap-3">
       {icon}
       <span className="text-xs font-medium">{text}</span>
    </div>
  );
}