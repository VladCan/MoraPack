import { useMemo } from "react";
import { Plane, X } from "lucide-react";

// Interfaz unificada para lo que la tarjeta necesita mostrar
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
  // Opcionales para efectos visuales (usados en hover)
  planeColor?: string;
  pathColor?: string;
  progress?: number; 
}

interface FlightCardProps {
  data: FlightCardData;
  simNowUtc?: string | null; // Para calcular progreso si no viene en data
  onClose: () => void;
  isHover?: boolean; // <--- Ahora sí lo usaremos
}

const getCargaColor = (ocupacion: number) => {
  if (ocupacion > 0.8) return "#f97316";
  if (ocupacion > 0.5) return "#facc15";
  return "#38bdf8";
};

export default function FlightCard({ data, simNowUtc, onClose, isHover }: FlightCardProps) {
  
  // Calcular progreso y color si no vienen pre-calculados (caso onClick desde panel)
  const computedState = useMemo(() => {
    let progress = data.progress ?? 0;
    let color = data.pathColor;

    const cap = data.capacidad || 1;
    const ocupacion = data.cantidadAsignada / cap;

    if (!color) {
      color = getCargaColor(ocupacion);
    }

    if (data.progress === undefined && simNowUtc) {
      const now = new Date(simNowUtc).getTime();
      const start = new Date(data.salidaUtc).getTime();
      const end = new Date(data.llegadaUtc).getTime();
      const total = Math.max(1, end - start);
      const elapsed = Math.max(0, Math.min(now, end) - start);
      progress = Math.min(1, elapsed / total);
    }

    return { progress, color, ocupacion };
  }, [data, simNowUtc]);

  return (
    <div className="fixed bottom-4 right-4 z-50 w-80 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto animate-in slide-in-from-bottom-2 fade-in duration-300">
      <div className="space-y-3">
        <div className="flex items-center justify-between border-b border-border pb-2">
          <div className="flex items-center gap-2">
            {/* Si viene planeColor úsalo, sino icono genérico */}
            {data.planeColor ? (
               <div className="w-3 h-3 rounded-full" style={{ backgroundColor: data.planeColor }}></div>
            ) : (
               <Plane className="w-4 h-4 text-primary" />
            )}
            <h3 className="font-semibold text-lg">{data.id}</h3>
          </div>
          
          <div className="flex items-center gap-2">
             {/* Mostrar porcentaje de vuelo completado */}
             <span className="text-xs text-muted-foreground">
                {Math.round(computedState.progress * 100)}%
             </span>
             
             {/* CORRECCIÓN: Solo mostramos el botón de cerrar si NO es hover */}
             {!isHover && (
               <button onClick={onClose} className="text-muted-foreground hover:text-foreground">
                  <X className="h-4 w-4" />
               </button>
             )}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 text-sm">
          <div>
            <p className="text-muted-foreground text-xs">Origen</p>
            <p className="font-mono text-xs">{data.origen}</p>
          </div>
          <div>
            <p className="text-muted-foreground text-xs">Destino</p>
            <p className="font-mono text-xs">{data.destino}</p>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-2 text-sm">
          <div>
            <p className="text-muted-foreground text-xs">Salida</p>
            <p className="font-mono text-xs">
              {new Date(data.salidaUtc).toLocaleTimeString("es-PE", {
                hour: "2-digit",
                minute: "2-digit",
                timeZone: "UTC",
              })}{" "}
              UTC
            </p>
          </div>
          <div>
            <p className="text-muted-foreground text-xs">Llegada</p>
            <p className="font-mono text-xs">
              {new Date(data.llegadaUtc).toLocaleTimeString("es-PE", {
                hour: "2-digit",
                minute: "2-digit",
                timeZone: "UTC",
              })}{" "}
              UTC
            </p>
          </div>
        </div>

        {/* Barra de Carga */}
        <div>
          <div className="flex justify-between text-sm mb-1">
            <span className="text-muted-foreground">Carga</span>
            <span className="font-semibold">
              {data.cantidadAsignada} / {data.capacidad}
            </span>
          </div>
          <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
            <div
              className="h-full transition-all"
              style={{
                width: `${computedState.ocupacion * 100}%`,
                backgroundColor: getCargaColor(computedState.ocupacion),
              }}
            ></div>
          </div>
        </div>

        {/* Lista de carga */}
        {data.carga && data.carga.length > 0 && (
          <div className="max-h-32 overflow-y-auto space-y-1 mt-2 border-t border-border pt-2">
            <p className="text-xs font-semibold text-muted-foreground mb-1">
              Contenido ({data.carga.length}{" "}
              {data.carga.length === 1 ? "pedido" : "pedidos"})
            </p>
            {data.carga.map((item, idx) => (
              <div
                key={idx}
                className="flex items-center justify-between text-xs p-1.5 rounded bg-muted/50"
              >
                <div className="flex items-center gap-2">
                  <span className="font-mono font-semibold">
                    #{item.pedidoId}
                  </span>
                  {item.esConexion && (
                    <span className="px-1.5 py-0.5 rounded text-[10px] bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200">
                      Conexión
                    </span>
                  )}
                </div>
                <div className="text-right">
                  <p className="font-semibold">{item.cantidad} uds</p>
                  <p className="text-muted-foreground text-[10px]">
                    → {item.destinoFinal}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}