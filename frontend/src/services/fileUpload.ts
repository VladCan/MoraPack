// src/services/uploadFile.ts
import { api, handleApi } from "@/services/api";
import type { ApiError } from "@/services/api";
import { apiWithLoadingToast } from "./apiWithLoadingToast";


export type ApiResponse = {
  status: string;
  message: string;
  filePath?: string;
};

export async function uploadFile(
  endpoint: string,
  file: File
): Promise<[ApiResponse | null, ApiError | null]> {
  const formData = new FormData();
  formData.append("file", file);

  // Ky necesita { body: formData } (sin json)
  return apiWithLoadingToast(() =>
    handleApi(api.post(endpoint, { body: formData,timeout: false }).json<ApiResponse>())
  );
}
