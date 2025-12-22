import { useMemo } from 'react';
import { useRunSession } from '@/lib/runSession';
import { Plane, Package } from 'lucide-react';

export const SimulationStatusWidget = () => {
  const { status, lastWindow } = useRunSession();

  const stats = useMemo(() => {
    if (!lastWindow) return { vuelos: 0, pedidos: 0 };
    return {
      vuelos: lastWindow.vuelos?.length ?? 0,
      pedidos: lastWindow.pedidos?.length ?? 0 
    };
  }, [lastWindow]);

  // Si no hay ventana de datos (ni siquiera la inicial), no renderizamos nada.
  if (!lastWindow) return null;

  const isRunning = status === 'running';

  // Lógica corregida: Mostramos el widget si hay datos, independientemente del status.
  // Solo aplicamos opacidad 0 si explícitamente queremos ocultarlo (opcional).
  // Aquí siempre será visible si pasó el check de !lastWindow.

  return (
    <div className={`
      fixed top-16 right-6 z-[80] 
      flex items-center gap-4 
      px-4 py-2 
      bg-white/90 dark:bg-slate-900/90 backdrop-blur-md 
      border border-slate-200 dark:border-slate-700 
      rounded-full shadow-lg 
      animate-in fade-in slide-in-from-top-4 duration-500
    `}>
      
      {/* Indicador de Estado */}
      <div className="flex items-center gap-2 border-r border-slate-200 dark:border-slate-700 pr-4">
        <span className="relative flex h-3 w-3">
          {isRunning && (
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
          )}
          <span className={`relative inline-flex rounded-full h-3 w-3 ${
            status === 'finished' ? 'bg-blue-500' :
            status === 'failed' ? 'bg-red-500' :
            isRunning ? 'bg-emerald-500' : 'bg-slate-400'
          }`}></span>
        </span>
        <span className="text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider">
          {status === 'running' ? 'En Vivo' : 
           status === 'finished' ? 'Finalizado' : 'Pausado'}
        </span>
      </div>

      {/* Contadores */}
      <div className="flex items-center gap-4 text-sm">
        
        {/* Vuelos */}
        <div className="flex items-center gap-2 text-blue-600 dark:text-blue-400">
          <Plane size={16} className={isRunning ? "animate-pulse" : ""} />
          <span className="font-mono font-bold tabular-nums">
            {stats.vuelos}
          </span>
        </div>

        {/* Pedidos */}
        <div className="flex items-center gap-2 text-orange-600 dark:text-orange-400">
          <Package size={16} />
          <span className="font-mono font-bold tabular-nums">
            {stats.pedidos}
          </span>
        </div>
      </div>

    </div>
  );
};

export default SimulationStatusWidget;