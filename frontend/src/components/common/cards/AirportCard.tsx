import { X, Calendar, Clock, Plane } from "lucide-react";

// --- Esquemas Zod ---
type FlightType = {
  id: string;
  cantidad: number;
  origen?: string;
  destino?: string;
};

interface AirportCardProps {
  airportId: string;
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
  airportId,
  data,
  isSede,
  flights,
  onClose,
}: AirportCardProps) {

  // Función simplificada: Extrae la hora literal sin conversión de zona horaria
  const formatFlightInfo = (idString: string) => {
    try {
      const parts = idString.split("-");
      // idString esperado: "SBBR-SUAA-2025-12-10T082500Z"
      
      if (parts.length < 5) return { date: "N/A", time: "N/A" };

      //const year = parts[2];
      const month = parts[3];
      // parts[4] es "10T082500Z"
      const [day, timeRaw] = parts[4].split("T"); 

      // timeRaw es "082500Z". Extraemos los caracteres directamente.
      // Esto asegura que si el ID dice "08", mostremos "08" (UTC).
      const hour = timeRaw.substring(0, 2);
      const minute = timeRaw.substring(2, 4);

      return {
        date: `${day}/${month}`,     // Ejemplo: 10/12
        time: `${hour}:${minute}`,   // Ejemplo: 08:25
      };

    } catch {
      return { date: "Invalid", time: "Invalid" };
    }
  };

  const color =
    data.porcentaje > 0.8
      ? "#f97316"
      : data.porcentaje > 0.5
      ? "#facc15"
      : "#38bdf8";

  const FlightRow = ({
    flight,
    type,
  }: {
    flight: FlightType;
    type: "llegada" | "salida";
  }) => {
    const { date, time } = formatFlightInfo(flight.id);
    const isLlegada = type === "llegada";

    return (
      <div className="bg-muted/30 rounded-md p-2 border border-border/50">
        {/* Fila superior: Fecha y Hora */}
        <div className="flex items-center gap-3 text-[10px] text-muted-foreground mb-1.5 border-b border-border/40 pb-1">
          <div className="flex items-center gap-1">
            <Calendar className="w-3 h-3" />
            <span>{date}</span>
          </div>
          <div className="flex items-center gap-1">
            <Clock className="w-3 h-3" />
            <span className="font-mono">{time}</span>
            {/* Indicador UTC explícito */}
            <span className="bg-blue-500/10 text-blue-600 dark:text-blue-400 px-1 rounded text-[9px] font-semibold border border-blue-500/20">
              UTC
            </span>
          </div>
        </div>

        {/* Fila inferior: Ruta y Cantidad */}
        <div className="flex justify-between items-center text-xs">
          <div className="flex items-center gap-1.5">
            <Plane
              className={`w-3 h-3 ${isLlegada ? "rotate-90" : "-rotate-45"}`}
            />
            <span className="text-foreground/80">
              {isLlegada ? `De: ${flight.origen || "?"}` : `A: ${flight.destino || "?"}`}
            </span>
          </div>
          <span
            className={`font-bold ${
              isLlegada
                ? "text-green-600 dark:text-green-400"
                : "text-orange-600 dark:text-orange-400"
            }`}
          >
            {isLlegada ? "+" : "-"}
            {flight.cantidad}
          </span>
        </div>
      </div>
    );
  };

  return (
    <div className="fixed bottom-4 right-4 z-50 w-96 p-4 rounded-xl shadow-2xl ring-1 ring-border backdrop-blur-xl backdrop-saturate-150 bg-card/90 pointer-events-auto animate-in slide-in-from-bottom-2 fade-in duration-300">
      <div className="space-y-3">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border pb-2">
          <div className="flex items-center gap-2">
            {!isSede && (
              <div
                className="w-3 h-3 rounded-full"
                style={{ backgroundColor: color }}
              ></div>
            )}
            <h3 className="font-semibold text-lg">{airportId}</h3>
          </div>
          <div className="flex items-center gap-2">
            {!isSede && (
              <span className="text-xs text-muted-foreground">
                {Math.round(data.porcentaje * 100)}%
              </span>
            )}
            <button
              onClick={onClose}
              className="text-muted-foreground hover:text-foreground transition-colors"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        {/* Capacidad */}
        {!isSede && (
          <div>
            <div className="flex justify-between text-sm mb-1">
              <span className="text-muted-foreground">Ocupación</span>
              <span className="font-semibold">
                {data.ocupacionActual} / {data.capacidadTotal}
              </span>
            </div>
            <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
              <div
                className="h-full transition-all"
                style={{
                  width: `${data.porcentaje * 100}%`,
                  backgroundColor: color,
                }}
              />
            </div>
          </div>
        )}

        {/* Disponible */}
        {!isSede && (
          <div>
            <p className="text-muted-foreground text-xs">Disponible</p>
            <p className="font-semibold text-lg">{data.disponible} uds</p>
          </div>
        )}

        {/* Eventos en tiempo real */}
        {((data.cargaLlegando && data.cargaLlegando > 0) ||
          (data.cargaSaliendo && data.cargaSaliendo > 0)) && (
          <div className="border-t border-border pt-3 mt-3">
            <p className="text-xs font-semibold mb-2 text-muted-foreground">
              En este momento
            </p>
            <div className="grid grid-cols-2 gap-2 text-xs">
              {data.cargaLlegando !== undefined && data.cargaLlegando > 0 && (
                <div className="bg-green-500/10 rounded p-2 border border-green-500/20">
                  <p className="text-green-600 dark:text-green-400 font-semibold">
                    Llegando
                  </p>
                  <p className="text-sm font-bold text-green-700 dark:text-green-300">
                    +{data.cargaLlegando} uds
                  </p>
                </div>
              )}
              {data.cargaSaliendo !== undefined && data.cargaSaliendo > 0 && (
                <div className="bg-orange-500/10 rounded p-2 border border-orange-500/20">
                  <p className="text-orange-600 dark:text-orange-400 font-semibold">
                    Saliendo
                  </p>
                  <p className="text-sm font-bold text-orange-700 dark:text-orange-300">
                    -{data.cargaSaliendo} uds
                  </p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Estadísticas futuras (24h) */}
        <div className="border-t border-border pt-3 mt-3">
          {isSede ? (
            <div className="text-sm">
              <p className="text-muted-foreground mb-2 font-medium">
                Próximas Salidas
              </p>
              {flights.salidas.length > 0 ? (
                <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
                  {flights.salidas.map((v, idx) => (
                    <FlightRow key={idx} flight={v} type="salida" />
                  ))}
                </div>
              ) : (
                <p className="text-xs text-muted-foreground">
                  0 vuelos programados
                </p>
              )}
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <p className="text-muted-foreground mb-2 font-medium text-xs uppercase tracking-wider">
                  Llegadas
                </p>
                {flights.llegadas.length > 0 ? (
                  <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
                    {flights.llegadas.map((v, idx) => (
                      <FlightRow key={idx} flight={v} type="llegada" />
                    ))}
                  </div>
                ) : (
                  <p className="text-xs text-muted-foreground">0 vuelos</p>
                )}
              </div>
              <div>
                <p className="text-muted-foreground mb-2 font-medium text-xs uppercase tracking-wider">
                  Salidas
                </p>
                {flights.salidas.length > 0 ? (
                  <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
                    {flights.salidas.map((v, idx) => (
                      <FlightRow key={idx} flight={v} type="salida" />
                    ))}
                  </div>
                ) : (
                  <p className="text-[10px] text-muted-foreground">0 vuelos</p>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}