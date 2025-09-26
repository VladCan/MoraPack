// src/services/api.ts
import ky, { HTTPError } from "ky";

/** Cliente HTTP único para toda la app */
export const api = ky.create({
  prefixUrl: import.meta.env.VITE_API_BASE_URL, // e.g. http://localhost:8080/api/v1
  timeout: 10000,
  retry: {
    limit: 2,
    methods: ["get", "post", "put", "delete"],
    statusCodes: [408, 500, 502, 503, 504],
  },
});

/** Contrato de error único para la UI */
export type ApiError = {
  status: number;
  message: string;
  code?: string;
  details?: unknown;
};

/** Wrapper: devuelve [data, error] y nunca lanza */
export async function handleApi<T>(p: Promise<T>): Promise<[T | null, ApiError | null]> {
  try {
    const data = await p;
    return [data, null];
  } catch (err) {
    if (err instanceof HTTPError) {
      const body = await err.response.json().catch(() => ({}));
      return [
        null,
        {
          status: err.response.status,
          message: body?.message ?? err.message,
          code: body?.code,
          details: body?.details,
        },
      ];
    }
    return [null, { status: 0, message: (err as Error).message, details: err }];
  }
}

/** Helpers mínimos para JSON */
export const getJson = <T>(url: string, searchParams?: Record<string, unknown>) =>
  api.get(url, {
    searchParams: searchParams
      ? new URLSearchParams(
          Object.entries(searchParams).reduce<Record<string, string>>((acc, [key, value]) => {
            acc[key] = value != null ? String(value) : "";
            return acc;
          }, {})
        )
      : undefined,
  }).json<T>();
export const postJson = <T>(url: string, json?: unknown) =>
  api.post(url, { json }).json<T>();
export const putJson = <T>(url: string, json?: unknown) =>
  api.put(url, { json }).json<T>();
export const del = (url: string) => api.delete(url).then(() => true);
