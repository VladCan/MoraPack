import { useDropzone } from "react-dropzone";

type Props = {
  label: string;
  accept?: Record<string, string[]>;
  onFiles: (files: File[]) => void;
};

export function Dropzone({ label, accept, onFiles }: Props) {
  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    accept: accept ?? { "text/plain": [".txt"], "text/csv": [".csv"] },
    multiple: false,
    onDrop: onFiles,
  });

  return (
    <div
      {...getRootProps()}
      className={[
        "rounded-xl border border-dashed p-4 transition cursor-pointer",
        "backdrop-blur-lg bg-white/40 ring-1 ring-black/5",
        isDragActive ? "border-blue-500 bg-white/60" : "border-slate-300"
      ].join(" ")}
    >
      <input {...getInputProps()} />
      <div className="text-sm font-semibold uppercase tracking-wide text-blue-900">
        {label}
      </div>
      <div className="mt-2 text-xs text-slate-600">
        Suelta el archivo o haz clic para seleccionar (.csv, .txt)
      </div>
    </div>
  );
}
