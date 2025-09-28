import { useEffect, useState } from "react";
import type { AeropuertoDTO } from "@/types/api";
import type { ApiError } from "@/services/api";
import { listAirports, type ListAirportsParams } from "@/services/airports";

export function useAirports(params?: ListAirportsParams) {
  const [data, setData] = useState<AeropuertoDTO[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    listAirports(params).then(([d, e]) => {
      if (!alive) return;
      setData(d);
      setError(e);
      setLoading(false);
    });
    return () => {
      alive = false;
    };
  }, [params]);

  return { data, error, loading };
}
