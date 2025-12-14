// src/components/common/ClockSwitcher.tsx
import { useEffect, useRef, useState } from "react";
import NavClock from "./NavClock";

type Props = {
  className?: string;
  running: boolean;       // ¿hay simulación activa?
  finished: boolean;      // ¿terminó la simulación?
  runNow?: Date | null;   // tick que llega del backend/contexto
  runStart?: Date | null; // inicio exacto de la simulación (del back viene en UTC)
  simTimeZone?: string // ZonaHoraria de la simulación (por defecto es UTC)
  onCancel?: () => void; //Para pasar la función que gestiona la cancelación
};

export default function ClockSwitcher({ className = "", running, finished, runNow, 
  runStart, simTimeZone="UTC", onCancel}: Props) {
    
  ///Esto es para contar el tiempo real de la simulación transcurrido

  const [elapsedMs, setElapsedMs] = useState(0);
  const frozenEnd = useRef<number | null>(null);

  //Apenas llega el runStart (el wallAnchor o el tiempo real en el que comienza la sim), recalcula
  useEffect(() => {
    if (!runStart) {
      setElapsedMs(0);
      frozenEnd.current = null;
      return;
    }
    setElapsedMs(Date.now() - runStart.getTime());
  }, [runStart]);

  //1s aumentamos al contador mientras se ejecute.
  useEffect(() => {
    if (!runStart) return;
    if (finished) {
      // congelar si no viene hora de fin del backend
      if (frozenEnd.current == null) frozenEnd.current = Date.now();
      setElapsedMs(frozenEnd.current - runStart.getTime());
      return;
    }
    const id = setInterval(() => {
      setElapsedMs(Date.now() - runStart.getTime());
    }, 1000);
    return () => clearInterval(id);
  }, [runStart, finished]);
  
  return (
  <div
    className={[
      "fixed top-2 right-3 z-[50] shrink-0 whitespace-nowrap", // posición original
      className,
    ].join(" ")}>

    {/* Reloj del sistema */}
    <NavClock
      variant="system"
      className={[
        "absolute right-0 top-0 transition-all duration-300 ease-out",
      ].join(" ")}
    />

    {/* Reloj de simulación + tiempo transcurrido */}
    <div
      className={[
        "absolute right-0 top-0 transition-all duration-300 ease-out -mt-8",
        running ? "translate-y-[110%] opacity-100" : "translate-y-0 opacity-0",
      ].join(" ")}>

      <div className="flex flex-col items-center gap-2 -mt-4">
        <NavClock
          variant="run"
          value={runNow ?? undefined}
          timeZone={simTimeZone}
          className=""/>
        
        {/*Tiempo transcurrido*/}
        {runStart && <ElapsedBadge ms={elapsedMs} finished={finished} />}
      
        {/*Cancelar simulación*/}
        {onCancel && running && !finished && (
            <button
              type="button"
              onClick={onCancel}
              className="mt-1 rounded-full border border-red-500/40 bg-white/80 px-3 py-1 text-xs font-semibold text-red-600 shadow-sm backdrop-blur hover:bg-red-50 disabled:opacity-50"
            >
              Terminar simulación
            </button>
        )}

      </div>
    </div>
  </div>
);
}

function ElapsedBadge({ ms, finished }: { ms: number; finished: boolean }) {
  const hh = String(Math.floor(ms / 3_600_000)).padStart(2, "0");
  const mm = String(Math.floor((ms % 3_600_000) / 60_000)).padStart(2, "0");
  const ss = String(Math.floor((ms % 60_000) / 1000)).padStart(2, "0");
  return (
    <span
      className={[
        "tabular-nums text-xs font-medium",
        "rounded-lg px-2 py-1 ring-1",
        finished
          ? "bg-emerald-50/80 text-emerald-900 ring-emerald-300"
          : "bg-blue-50/70 text-blue-900 ring-blue-300",
      ].join(" ")}
      title="Tiempo real transcurrido de la ejecución"
    >
      Tiempo transcurrido:
      +{hh}:{mm}:{ss}
      {finished && " ✓"}
    </span>
  );
}