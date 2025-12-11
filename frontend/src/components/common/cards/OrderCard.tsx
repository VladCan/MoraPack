import { useMemo } from "react";
import { Package, X } from "lucide-react";
import type { PedidoDTO } from "@/hooks/useRunSSE";

interface OrderCardProps {
  pedido: PedidoDTO;
  simNowUtc?: string | null;
  onClose: () => void;
  variant?: "simulacion" | "operacion" | "colapso";
}

export default function OrderCard({ pedido, simNowUtc, onClose, variant = "simulacion" }: OrderCardProps) {
  
  // Lógica de cálculo de estado encapsulada en la tarjeta
  const pedidoState = useMemo(() => {
    if (!pedido || !pedido.rutas || !simNowUtc) {
      return { cantidadEnVuelo: 0, estado: "PENDIENTE", progreso: 0 };
    }

    const now = new Date(simNowUtc).getTime();

    // Calcular cantidad actualmente en el aire
    const cantidadEnVuelo = pedido.rutas.reduce((sum, ruta) => {
      const cantidadRutaEnVuelo = ruta.vuelos.reduce((sumVuelos, vuelo) => {
        const salida = new Date(vuelo.salidaUtc).getTime();
        const llegada = new Date(vuelo.llegadaUtc).getTime();
        // Si está en el aire ahora
        if (now >= salida && now <= llegada) {
          return sumVuelos + vuelo.cantidad;
        }
        return sumVuelos;
      }, 0);
      return sum + cantidadRutaEnVuelo;
    }, 0);

    // Calcular estado
    let estado = "PENDIENTE";
    
    if (variant === "operacion") {
        // Lógica específica para operación diaria
        let todosVuelosLlegaron = true;
        let tieneVuelosEnAire = false;
        
        pedido.rutas.forEach(ruta => {
            ruta.vuelos.forEach(vuelo => {
                const salida = new Date(vuelo.salidaUtc).getTime();
                const llegada = new Date(vuelo.llegadaUtc).getTime();
                if (now <= llegada) {
                    todosVuelosLlegaron = false;
                    if (now >= salida && now <= llegada) tieneVuelosEnAire = true;
                }
            });
        });

        if (todosVuelosLlegaron) estado = "COMPLETO";
        else if (tieneVuelosEnAire) estado = "EN_VUELO";
        else if (pedido.rutas.length > 0) estado = "PROGRAMADO";

    } else {
        // Lógica estándar (simulación/colapso)
        if (cantidadEnVuelo >= pedido.cantidad) estado = "COMPLETO";
        else if (cantidadEnVuelo > 0) estado = "EN_VUELO";
    }

    return {
      cantidadEnVuelo,
      estado,
      progreso: (cantidadEnVuelo / pedido.cantidad) * 100,
    };
  }, [pedido, simNowUtc, variant]);

  return (
    <div className="fixed bottom-4 right-4 z-50 w-80 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto animate-in slide-in-from-bottom-2 fade-in duration-300">
      <div className="space-y-3">
        <div className="flex items-center justify-between border-b border-border pb-2 sticky top-0 bg-card/90">
          <div className="flex items-center gap-2">
            <Package className="w-4 h-4 text-primary" />
            <h3 className="font-semibold text-lg">PED-{pedido.id}</h3>
          </div>
          <button
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="grid grid-cols-2 gap-2 text-sm">
          <div>
            <p className="text-muted-foreground text-xs">Cliente</p>
            <p className="font-mono text-xs">#{pedido.idCliente}</p>
          </div>
          <div>
            <p className="text-muted-foreground text-xs">Destino Final</p>
            <p className="font-mono text-xs">{pedido.destino}</p>
          </div>
        </div>

        {/* Barra de progreso */}
        <div>
          <div className="flex justify-between text-sm mb-1">
            <span className="text-muted-foreground">Estado</span>
            <span
              className={`font-semibold text-xs px-2 py-0.5 rounded ${
                pedidoState.estado === "COMPLETO"
                  ? "bg-emerald-100 text-emerald-800"
                  : pedidoState.estado === "EN_VUELO"
                  ? "bg-purple-100 text-purple-800"
                  : pedidoState.estado === "PROGRAMADO"
                  ? "bg-blue-100 text-blue-800"
                  : "bg-rose-100 text-rose-800"
              }`}
            >
              {pedidoState.estado}
            </span>
          </div>
          <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
            <div
              className={`h-full transition-all ${
                pedidoState.estado === "COMPLETO"
                  ? "bg-emerald-500"
                  : pedidoState.estado === "EN_VUELO"
                  ? "bg-purple-500"
                  : pedidoState.estado === "PROGRAMADO" 
                  ? "bg-blue-500"
                  : "bg-rose-500"
              }`}
              style={{ width: `${pedidoState.progreso}%` }}
            />
          </div>
          <p className="text-center text-xs text-muted-foreground mt-1">
            {pedidoState.cantidadEnVuelo} / {pedido.cantidad} unidades en camino
          </p>
        </div>

        {/* Rutas asociadas */}
        {pedido.rutas && pedido.rutas.length > 0 && (
          <div className="max-h-40 overflow-y-auto space-y-2 mt-2 border-t border-border pt-2">
            <p className="text-xs font-semibold text-muted-foreground">
              Rutas de entrega
            </p>
            {pedido.rutas.map((ruta, idx) => (
              <div
                key={idx}
                className="p-2 rounded bg-muted/40 border border-border/50 text-xs"
              >
                <div className="flex justify-between mb-1">
                  <span className="font-semibold">
                    {ruta.origen} → {ruta.destinoFinal}
                  </span>
                  <span>{ruta.cantidad} uds</span>
                </div>
                {/* Pequeña lista de vuelos de la ruta */}
                <div className="space-y-0.5">
                  {ruta.vuelos.map((v, vIdx) => (
                    <div
                      key={vIdx}
                      className="flex justify-between text-[10px] text-muted-foreground pl-2 border-l border-primary/20"
                    >
                      <span>
                        {v.origen}-{v.destino}
                      </span>
                      <span>
                        {new Date(v.salidaUtc).toLocaleTimeString("es-PE", {
                          hour: "2-digit",
                          minute: "2-digit",
                          timeZone: "UTC",
                        })}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}