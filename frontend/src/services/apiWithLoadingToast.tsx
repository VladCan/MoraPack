import { toast } from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import type {ApiError } from "./api";

/**
 * Envuelve cualquier llamada async al backend con:
 * - toast de loading infinito
 * - transformación automática a success/error
 */
export async function apiWithLoadingToast<T>(
  fn: () => Promise<[T | null, ApiError | null]>,
  loadingMessage = "Cargando..."
): Promise<[T | null, ApiError | null]> {
  // Mostrar toast de loading infinito
  const toastId = toast.custom(
    (t) => <ToastCustom t={t} message={loadingMessage} type="loading" />,
    { duration: Infinity }
  );

  try {
    // Ejecutar la función async
    const [data, error] = await fn();

    // Cerrar toast de loading y mostrar resultado
    toast.dismiss(toastId);
    return [data, error];
  } catch (err) {
    // En caso de error inesperado
    toast.dismiss(toastId);
    throw err;
  }
}
