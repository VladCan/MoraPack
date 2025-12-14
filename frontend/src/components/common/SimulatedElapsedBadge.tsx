// src/components/common/SimulatedElapsedBadge.tsx
export function SimulatedElapsedBadge({ start, now, finished }: { start: Date; now: Date; finished: boolean }) {
  const diffMs = now.getTime() - start.getTime();
  const days = Math.floor(diffMs / 86_400_000);
  const hours = Math.floor((diffMs % 86_400_000) / 3_600_000);
  const minutes = Math.floor((diffMs % 3_600_000) / 60_000);

  return (
    <span className={["tabular-nums text-xs font-medium", "rounded-lg px-2 py-1 ring-1", finished ? "bg-emerald-50/80 text-emerald-900 ring-emerald-300" : "bg-purple-50/70 text-purple-900 ring-purple-300"].join(" ")} title="Tiempo simulado transcurrido">
      Tiempo simulado: +{days}d {hours}h {minutes}m {finished && " ✓"}
    </span>
  );
}
