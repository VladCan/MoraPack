import { getJson, handleApi } from "@/services/api";
import type { ApiError } from "@/services/api";
import type { AeropuertoDTO } from "@/types/api";
import { apiWithLoadingToast } from "./apiWithLoadingToast";

export type ListAirportsParams = {
  codes?: string[];
  continente?: string;
  minCapacidad?: number;
  bbox?: [number, number, number, number];
  soloSedes?: boolean;
};

function toSearchParams(
  p?: ListAirportsParams
): Record<string, string> | undefined {
  if (!p) return undefined;
  const sp: Record<string, string> = {};
  if (p.codes?.length) sp["codes"] = p.codes.join(",");
  if (p.continente) sp["continente"] = p.continente;
  if (p.minCapacidad != null) sp["minCapacidad"] = String(p.minCapacidad);
  if (p.bbox?.length === 4) sp["bbox"] = p.bbox.join(",");
  if (p.soloSedes != null) sp["soloSedes"] = String(p.soloSedes);
  return sp;
}

/** ✅ Correcto: `apiWithLoadingToast` recibe una FUNCIÓN que retorna la tupla */
export function listAirports(
  params?: ListAirportsParams
): Promise<[AeropuertoDTO[] | null, ApiError | null]> {
  return apiWithLoadingToast<AeropuertoDTO[]>(
    () =>
      handleApi(
        getJson<AeropuertoDTO[]>("aereopuertos", toSearchParams(params))
      ),
    "Cargando aeropuertos..."
  );
}
