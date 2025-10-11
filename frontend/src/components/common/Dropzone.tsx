import { useMemo } from "react"
import { useDropzone, type Accept } from "react-dropzone"
import { UploadCloud, AlertTriangle, CheckCircle2 } from "lucide-react"
import { cn } from "@/lib/utils"

type Props = {
  label: string
  sublabel?: string
  accept?: Record<string, string[]>
  multiple?: boolean
  disabled?: boolean
  className?: string
  onFiles: (files: File[]) => void
}

export function Dropzone({
  label,
  sublabel = "Suelta el archivo o haz clic para seleccionar",
  accept,
  multiple = false,
  disabled = false,
  className,
  onFiles,
}: Props) {
  const normalizedAccept: Accept | undefined = useMemo(
    () => accept ?? { "text/plain": [".txt"], "text/csv": [".csv"] },
    [accept]
  )

  const {
    getRootProps,
    getInputProps,
    isDragActive,
    isDragReject,
    fileRejections,
    acceptedFiles,
  } = useDropzone({
    accept: normalizedAccept,
    multiple,
    disabled,
    onDrop: onFiles,
  })

  const helper =
    fileRejections.length > 0
      ? fileRejections[0].errors[0]?.message ?? "Archivo no permitido"
      : acceptedFiles.length > 0
      ? `${acceptedFiles.length} archivo${acceptedFiles.length > 1 ? "s" : ""} listo(s)`
      : `${sublabel} (.csv, .txt)`

  return (
    <div
      {...getRootProps()}
      aria-disabled={disabled}
      className={cn(
        // --- contenedor glass
        "relative w-full transition-all cursor-pointer",
        // paddings responsivos
        "rounded-2xl p-4 sm:p-5 md:p-6",
        // color/elevación (tema-aware si usas tokens)
        "backdrop-blur-lg bg-background/30",
        "border border-white/20 dark:border-white/10",
        "ring-1 ring-black/5 shadow-[0_10px_30px_rgba(0,0,0,0.15)]",
        // focus visible
        "focus-within:ring-2 focus-within:ring-primary/50 outline-none",
        // estados
        "hover:bg-background/40 hover:border-white/30",
        isDragActive && "border-dashed ring-2 ring-primary/50",
        isDragReject && "border-destructive/60 ring-2 ring-destructive/50",
        disabled && "opacity-60 cursor-not-allowed",
        className
      )}
    >
      {/* gradiente sutil */}
      <span
        aria-hidden
        className={cn(
          "pointer-events-none absolute inset-0 rounded-2xl",
          "bg-gradient-to-br from-white/10 via-transparent to-white/5",
          "opacity-0 transition-opacity",
          (isDragActive || isDragReject) && "opacity-100"
        )}
      />

      <input {...getInputProps()} />

      {/* ====== Layout responsive ======
          - móvil: columna, centrado
          - sm+: fila, alineado a la izquierda */}
      <div className="flex flex-col sm:flex-row sm:items-center gap-3 sm:gap-4 md:gap-5">
        {/* Ícono */}
        <div
          className={cn(
            "grid place-items-center shrink-0 self-center sm:self-auto",
            // tamaño responsive
            "h-10 w-10 sm:h-11 sm:w-11 md:h-12 md:w-12",
            "rounded-xl",
            "border border-white/25 dark:border-white/10",
            "bg-white/20 dark:bg-white/5 backdrop-blur",
            isDragActive && "scale-105",
            isDragReject && "border-destructive/60"
          )}
        >
          {fileRejections.length > 0 ? (
            <AlertTriangle className="h-5 w-5 sm:h-5 sm:w-5 md:h-6 md:w-6" />
          ) : acceptedFiles.length > 0 ? (
            <CheckCircle2 className="h-5 w-5 sm:h-5 sm:w-5 md:h-6 md:w-6" />
          ) : (
            <UploadCloud className="h-5 w-5 sm:h-5 sm:w-5 md:h-6 md:w-6" />
          )}
        </div>

        {/* Textos */}
        <div className="min-w-0 text-center sm:text-left">
          {/* label: tamaño responsive, permite wrap en móvil y truncate en ≥sm */}
          <p className="font-semibold text-foreground break-words text-sm sm:text-base md:text-[1rem]">
            {label.toUpperCase()}
          </p>
          {/* helper: wrap en móvil, truncate en ≥sm */}
          <p
            className={cn(
              "mt-0.5 sm:mt-0 text-xs sm:text-sm",
              "text-pretty sm:truncate",
              fileRejections.length > 0 ? "text-destructive" : "text-muted-foreground"
            )}
            title={helper}
          >
            {helper}
          </p>
        </div>
      </div>
    </div>
  )
}
