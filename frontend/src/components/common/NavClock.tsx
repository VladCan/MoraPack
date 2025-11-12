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

  value?: Date;

  variant?: "system" | "run"; 

  //Para evitar que se corran las horas:
  timeZone?: string;

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
  timeZone,
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
    timeZone
  });

  const date = showDate
    ? now.toLocaleDateString(locale, {
        weekday: "short",
        year: "numeric",
        month: "short",
        day: "2-digit",
        timeZone
      })
    : "";
    

    // Paleta sutil distinta para “run”
  const color =
    variant === "run"
      ? "bg-blue-50/70 text-blue-900 ring-1 ring-blue-300 shadow-blue-100/50"
      : "bg-card/40 text-foreground ring-1 ring-border shadow-lg";

  return (
    <div
      className={[
        "select-none",
        "rounded-full gap-2 px-3.5 py-1.5",
        "backdrop-blur-md backdrop-saturate-150",
        "flex items-center text-base font-medium",
        color, // ← se aplica color aquí
        className,
      ].join(" ")}
      title={variant === "run" ? "Hora de simulación" : "Hora del sistema"}
    >
      <small>{variant === "run" ? "Simulación" : "Sistema"}</small>
      <Clock
        className={[
          "h-4 w-4 opacity-80",
          variant === "run" ? "text-blue-700" : "text-foreground",
        ].join(" ")}
      />
      <div className="leading-tight">
        <div className="tabular-nums tracking-wide">{time}</div>
        {showDate && (
          <div className="text-[10px] uppercase opacity-80">{date}</div>
        )}
      </div>
    </div>
  );
}
