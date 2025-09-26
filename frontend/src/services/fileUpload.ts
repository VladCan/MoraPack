import { api } from "@/services/api";
import toast from "react-hot-toast";

type ApiResponse = {
    status: string;
    message: string;
    filePath?: string;  // Si 'filePath' es opcional
};
export const uploadFile = async (endpoint: string, file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    try {
        const response = await api.post(endpoint, { body: formData });

        // Verifica si la respuesta es JSON
        const responseBody: ApiResponse = await response.json();
        return responseBody.message; // Puedes retornar la respuesta completa si lo necesitas

    } catch (error) {
        console.error("Error al subir el archivo:", error);
        toast.error("Error al subir el archivo"); // Muestra un error genérico si algo falla
        return ''; // Retorna una cadena vacía o el mensaje de error si es necesario
    }
};
