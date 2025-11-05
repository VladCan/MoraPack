// src/components/nav/NavClock.tsx
import { useEffect, useState } from "react";
import { Clock } from "lucide-react";

type NavClockProps = {
  /** Clase extra para posicionar/ajustar desde el navbar */
  className?: string;
  /** 24h (true) o 12h (false). Default: 24h */
  use24h?: boolean;
  /** Muestra fecha corta debajo de la hora */
  showDate?: boolean;
  /** Locale para formateo (ej: 'es-PE') */
  locale?: string;
  /** Si lo pasas, se usará para obtener hora del backend; debe devolver un Date “server”. */

  /// ESTO ES NUEVO: ///

  value?: Date;

  variant?: "system" | "run"; 

  /// ESTO ES NUEVO ///

  fetcher?: () => Promise<Date>;
  /** Cada cuánto refrescar desde el backend (ms). Si no hay fetcher, solo hace tick local. */
  refreshInterval?: number;
};

export default function NavClock({
  className = "",
  use24h = true,
  showDate = true,
  locale = "es-ES",
  value,
  variant = "system",
  fetcher,
  refreshInterval = 60_000, // 1 min
}: NavClockProps) {
  const [now, setNow] = useState<Date>(new Date());

  // Si NO hay value controlado, hacemos tick local
  useEffect(() => {
    if (value) return; // controlado desde afuera
    const id = setInterval(() => setNow((d) => new Date(d.getTime() + 1000)), 1000);
    return () => clearInterval(id);
  }, [value]);

  // Si llega un value controlado, lo reflejamos
  useEffect(() => {
    if (value) setNow(value);
  }, [value]);

  // si hay fetcher, sincroniza periódicamente con backend
  useEffect(() => {
    if (!fetcher) return;
    let stop = false;
    const sync = async () => {
      try {
        const serverDate = await fetcher();
        if (!stop) setNow(serverDate);
      } catch {
        /* silencioso: mantenemos reloj local */
      }
    };
    sync();
    const id = setInterval(sync, refreshInterval);
    return () => {
      stop = true;
      clearInterval(id);
    };
  }, [fetcher, refreshInterval]);

  const time = now.toLocaleTimeString(locale, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: !use24h,
  });

  const date = showDate
    ? now.toLocaleDateString(locale, {
        weekday: "short",
        year: "numeric",
        month: "short",
        day: "2-digit",
      })
    : "";

    // Paleta sutil distinta para “run”
  const color =
    variant === "run"
      ? "bg-blue-50/70 ring-blue-300 text-blue-900"
      : "bg-card/40 ring-border text-foreground";

  return (
    <div
      className={[
        "select-none",
        // Glassmorphism similar a tu <ul>
        "rounded-full gap-2 px-3.5 py-1.5",
        "bg-card/40 shadow-lg ring-1 ring-border",
        "backdrop-blur-md backdrop-saturate-150",
        // Tipografía/colores
        "flex items-center text-base font-medium",
        className,
      ].join(" ")}
      title={variant === "run" ? "Hora de simulación" : "Hora del sistema"}
    >
      <Clock className="h-4 w-4 opacity-80" />
      <div className="leading-tight">
        <div className="tabular-nums tracking-wide">{time}</div>
        {showDate && (
          <div className="text-[10px] text-base uppercase">{date}</div>
        )}
      </div>
    </div>
  );
}
