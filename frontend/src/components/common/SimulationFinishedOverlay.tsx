// src/components/common/SimulationFinishedOverlay.tsx
import { Download, XCircle, CheckCircle2, AlertTriangle } from "lucide-react";

type StopReason = "FIN_DE_RANGO" | "MANUAL" | "COLAPSO" | "ERROR";

interface Props {
  open: boolean;
  reason: StopReason;
  simStartUtc?: string | null;
  simEndUtc?: string | null;
  wallAnchor?: string | null;
  finishedAt?: number | null;
  onClose: () => void;
  onDownloadReports: () => void;
}

function formatDate(iso?: string | null) {
  if (!iso) return "-";
  const d = new Date(iso);
  return d.toLocaleString("es-PE", {
    weekday: "short",
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function reasonConfig(reason: StopReason) {
  switch (reason) {
    case "FIN_DE_RANGO":
      return {
        title: "Simulación completada",
        subtitle: "La simulación recorrió todo el rango definido.",
        tone: "success" as const,
      };
    case "COLAPSO":
      return {
        title: "Simulación detenida por colapso",
        subtitle: "Se detectó una violación de SLA / condición de colapso.",
        tone: "error" as const,
      };
    case "MANUAL":
      return {
        title: "Simulación cancelada manualmente",
        subtitle: "La ejecución fue finalizada por el usuario.",
        tone: "warn" as const,
      };
    case "ERROR":
    default:
      return {
        title: "Simulación detenida por error",
        subtitle: "Ocurrió un error interno durante la ejecución.",
        tone: "error" as const,
      };
  }
}

function calculateDuration(wallAnchor?: string | null, finishedAt?: number | null): string {
  if (!wallAnchor || !finishedAt) return "--:--:--";

  const startMs = new Date(wallAnchor).getTime();
  const ms = finishedAt - startMs;

  if (isNaN(ms) || ms < 0) return "--:--:--";

  const hh = Math.floor(ms / 3_600_000).toString().padStart(2, "0");
  const mm = Math.floor((ms % 3_600_000) / 60_000).toString().padStart(2, "0");
  const ss = Math.floor((ms % 60_000) / 1000).toString().padStart(2, "0");

  return `${hh}:${mm}:${ss}`;
}


export default function SimulationFinishedOverlay({open,
  reason,
  simStartUtc,
  simEndUtc,
  onClose,
  onDownloadReports,
  wallAnchor,
  finishedAt
}: Props) {
    if (!open) return null;

    const cfg = reasonConfig(reason);
    
    const toneClasses = {
        success: "border-emerald-400/70 bg-emerald-50/80 text-emerald-900",
        warn: "border-amber-400/70 bg-amber-50/80 text-amber-900",
        error: "border-rose-400/70 bg-rose-50/80 text-rose-900",
    } as const;

    const icon =
    cfg.tone === "success" ? (
      <CheckCircle2 className="h-6 w-6 text-emerald-500" />
    ) : cfg.tone === "warn" ? (
      <AlertTriangle className="h-6 w-6 text-amber-500" />
    ) : (
      <XCircle className="h-6 w-6 text-rose-500" />
    );

    return (
    <div className="fixed inset-0 z-[80] flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-xl rounded-3xl border border-white/60 bg-white/80 shadow-2xl backdrop-blur-xl dark:bg-slate-900/80">
        {/* Cabecera */}
        <div className="flex items-center gap-3 border-b border-white/40 px-6 py-4">
          <div
            className={`inline-flex items-center justify-center rounded-full border px-2.5 py-1 text-xs font-semibold uppercase tracking-wide ${toneClasses[cfg.tone]}`}
          >
            {icon}
            <span className="ml-2">{cfg.title}</span>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="ml-auto rounded-full p-1.5 text-slate-500 hover:bg-slate-200/70 hover:text-slate-900 hover:cursor-pointer"
          >
            <XCircle className="h-4 w-4" />
          </button>
        </div>

        {/* Contenido */}
        <div className="space-y-4 px-6 py-5">
          <p className="text-sm text-slate-700 dark:text-slate-200">
            {cfg.subtitle}
          </p>

          <div className="grid gap-3 rounded-2xl bg-white/70 p-4 text-sm shadow-inner dark:bg-slate-900/60">
            <div className="flex justify-between">
              <span className="text-slate-500">Inicio de simulación</span>
              <span className="font-medium text-slate-900 dark:text-slate-100">
                {formatDate(simStartUtc)}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500">Fin de simulación</span>
              <span className="font-medium text-slate-900 dark:text-slate-100">
                {formatDate(simEndUtc)}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500">Duración simulada</span>
              <span className="font-semibold text-slate-900 dark:text-slate-100">
                {calculateDuration(wallAnchor, finishedAt)}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-500">Motivo</span>
              <span className="font-medium">{reason}</span>
            </div>
          </div>

          {/* Acciones */}
          <div className="flex flex-wrap items-center justify-between gap-3 pt-2">
            <button
              type="button"
              onClick={onDownloadReports}
              className="inline-flex items-center gap-2 rounded-full border border-blue-500/70 bg-blue-50/70 px-4 py-2 text-sm font-semibold text-blue-700 shadow-sm hover:bg-blue-100 hover:cursor-pointer"
            >
              <Download className="h-4 w-4" />
              Descargar reportes
            </button>

            <button
              type="button"
              onClick={onClose}
              className="text-sm font-medium text-slate-600 underline-offset-2 hover:underline"
            >
              Cerrar
            </button>
          </div>
        </div>
      </div>
    </div>
  );


}
