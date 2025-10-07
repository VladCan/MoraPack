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
  fetcher?: () => Promise<Date>;
  /** Cada cuánto refrescar desde el backend (ms). Si no hay fetcher, solo hace tick local. */
  refreshInterval?: number;
};

export default function NavClock({
  className = "",
  use24h = true,
  showDate = true,
  locale = "es-ES",
  fetcher,
  refreshInterval = 60_000, // 1 min
}: NavClockProps) {
  const [now, setNow] = useState<Date>(new Date());

  // tick local cada segundo (suave para el navbar)
  useEffect(() => {
    const id = setInterval(() => setNow((d) => new Date(d.getTime() + 1000)), 1000);
    return () => clearInterval(id);
  }, []);

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

  return (
    <div
      className={[
        "select-none",
        "rounded-full bg-white/10 border border-black/10 shadow-sm",
        "backdrop-blur-sm",
        "px-3.5 py-1.5",
        "flex items-center gap-2",
        "text-primary",
        "font-medium",
        className,
      ].join(" ")}
      title="Hora del sistema"
    >
      <Clock className="h-4 w-4 opacity-80" />
      <div className="leading-tight">
        <div className="tabular-nums tracking-wide">{time}</div>
        {showDate && (
          <div className="text-[10px] text-primary/70 uppercase">{date}</div>
        )}
      </div>
    </div>
  );
}
