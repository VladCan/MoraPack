import ky, { HTTPError } from "ky";

/** Cliente HTTP único para toda la app */
export const api = ky.create({
  prefixUrl: import.meta.env.VITE_API_BASE_URL, // http://localhost:8080
  timeout: 10000,
  retry: {
    limit: 2,
    methods: ["get", "post", "put", "delete"],
    statusCodes: [408, 500, 502, 503, 504],
  },
});

/** Contrato de error único para la UI */
export interface ApiError {
  status: number;
  message: string;
  code?: string;
  details?: unknown;
}

/** Wrapper: devuelve [data, error] y nunca lanza */
export async function handleApi<T>(
  p: Promise<T>
): Promise<[T | null, ApiError | null]> {
  try {
    const data = await p;
    return [data, null];
  } catch (err) {
    if (err instanceof HTTPError) {
      let body: Record<string, unknown> = {};

      try {
        // Intentar leer como JSON
        body = await err.response.json();
      } catch {
        try {
          // Si no es JSON, leer como texto plano
          const text = await err.response.text();
          body = { message: text };
        } catch {
          body = { message: `Error desconocido ${err.message}` };
        }
      }

      const message = typeof body.message === "string" ? body.message : JSON.stringify(body);

      const apiError: ApiError = {
        status: err.response.status,
        message,
        code: (body.code as string | undefined) ?? undefined,
        details: body.details ?? body,
      };

      return [null, apiError];
    }

    // Error genérico
    const apiError: ApiError = {
      status: 0,
      message: (err as Error).message,
      details: err,
    };

    return [null, apiError];
  }
}

/** Helpers mínimos para JSON */

export const getJson = async <T>(
  url: string,
  searchParams?: Record<string, unknown>
): Promise<T> => {
  const params = searchParams
    ? new URLSearchParams(
        Object.entries(searchParams).reduce<Record<string, string>>((acc, [k, v]) => {
          acc[k] = v != null ? String(v) : "";
          return acc;
        }, {})
      )
    : undefined;

  return api.get(url, { searchParams: params }).json<T>();
};

export const postJson = async <T>(url: string, json?: unknown): Promise<T> =>
  api.post(url, { json }).json<T>();

export const putJson = async <T>(url: string, json?: unknown): Promise<T> =>
  api.put(url, { json }).json<T>();

export const del = async (url: string): Promise<boolean> => {
  await api.delete(url);
  return true;
};

/** get de texto plano (por ahora para el preview del archivo) y BLOB (para descargar archivo)**/

export const getText = async (
  url: string,
  searchParams?: Record<string, unknown>
): Promise<string> => {
  const params = searchParams
    ? new URLSearchParams(
      Object.entries(searchParams).reduce<Record<string, string>>((acc, [k, v]) => {
        acc[k] = v != null ? String(v) : "";
        return acc;
      }, {})
    )
    : undefined;

    return api.get(url, { searchParams: params }).text();
}

export const downloadFile = async (url: string, suggestedName : string) => {
  const blob = await api.get(url).blob();
  const href = URL.createObjectURL(blob);
  const a = document.createElement("a");

  a.href = href;
  a.download = suggestedName || "download";
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(href);

}

/*Por si en algun momento la estructura ya no es con toast, mismo formato de respuesta (por ahora no se usa) */
export const postMultipart = async <T>(
  url: string,
  file: File,
  fieldName = "file"
): Promise<T> => {
  const fd = new FormData();
  fd.append(fieldName, file);
  return api.post(url, { body: fd }).json<T>();
};