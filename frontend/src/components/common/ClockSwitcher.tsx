// src/components/common/ClockSwitcher.tsx
import NavClock from "./NavClock";

type Props = {
  className?: string;
  running: boolean;       // ¿hay simulación activa?
  runNow?: Date | null;   // tick que llega del backend/contexto
};

export default function ClockSwitcher({ className = "", running, runNow }: Props) {
    return (
    <div className={["fixed top-2 right-3 z-[50] shrink-0 whitespace-nowrap", // posición original
         className].join(" ")}>
      {/* Reloj del sistema */}
      <NavClock
        variant="system"
        className={[
          "absolute right-0 top-0 transition-all duration-300 ease-out",
          running ? "translate-x-[110%] opacity-0" : "translate-x-0 opacity-100",
        ].join(" ")}
      />

      {/* Reloj de simulación (controlado por runNow) */}
      <NavClock
        variant="run"
        value={runNow ?? undefined}
        className={[
          "absolute right-0 top-0 transition-all duration-300 ease-out",
          running ? "translate-x-0 opacity-100" : "translate-x-[110%] opacity-0",
        ].join(" ")}
      />
    </div>
  );
} 