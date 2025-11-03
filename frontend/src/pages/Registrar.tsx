import { z } from "zod";
import { useForm, type Resolver } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Form, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Dropzone } from "@/components/common/Dropzone";
import { downloadFile, getJson, getText, handleApi, postJson, type ApiError } from "@/services/api";
import { uploadFile } from "@/services/fileUpload";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import { airportsMap } from "@/types/airportsMap";
import { buildPedidoRequest } from "@/services/buildPedidoRequest";
import type { PedidoResponse } from "@/types/pedidos";
import { useRunSession } from "@/lib/runSession";

/**Esto es para mostrar la información del archivo al cargarlo (nombre, peso, etc.)**/
type Status = {
  exists: boolean;
  filename: string;
  sizeBytes?: number;
  lastModified?: string;
}
type Kind = "vuelos" | "aereopuertos" | "cancelaciones";

const handleFileUpload = async (file: File, endpoint: string) => {
  const [data, error] = await uploadFile(endpoint, file);
  //termina el toast de carga
  if (data) {
    toast.custom((t) => (
      <ToastCustom t={t} message={data.message} type="success" />),
      { duration: Infinity }
    );
  } else if (error) {
    toast.custom((t) => (
      <ToastCustom t={t} message={error.message} type="error" />),
      { duration: Infinity }
    );
    console.error("Detalles del error:", error); // útil para debug
  }
};
const schema = z.object({
  clienteId: z.string().min(1, "Requerido"),
  aeropuerto: z.string().min(1, "Requerido"),
  cantidad: z.coerce.number().int().positive("Debe ser > 0"),
});

type FormValues = z.infer<typeof schema>;
const resolver = zodResolver(schema) as Resolver<FormValues>;
export default function Registrar() {

  //console.log("🧠 Componente Registrar montado");

  const qc = useQueryClient();

  const vuelosStatus = useQuery({
  queryKey: ["status", "vuelos"],
  queryFn: async () => {try {
    const res = await getJson<Status>("vuelos/status");
    console.log("📡 /vuelos/status →", res);
    return res;
  } catch (err) {
    console.error("❌ Error en /vuelos/status:", err);
    throw err;
  }
  },
  refetchOnWindowFocus: false,
})

//husos = aeropuertos
const husosStatus  = useQuery({
  queryKey: ["status", "aereopuertos"],
  queryFn: () => getJson<Status>("aereopuertos/status"),
  refetchOnWindowFocus: false,
})

/**Acá hay 2 handlers para subir con confirmación si ya hay archivo + refetch**/

//pero antes: handler de subida con confirmación + refresh (TODO falta agregar cancelaciones)
const onUpload = async (file: File, kind: "vuelos" | "aereopuertos" | "cancelaciones" | "operacionDiaria") => {
  
  //TODO: POR AHORA SOLO TOMA VUELOS Y HUSOS
  //EN EL BACK EL CONTROLLER TIENE QUE TENER EL ENDPOINT '/upload' (VER Línea 98)
  
  const status = kind === "vuelos" ? vuelosStatus.data : husosStatus.data;

  if (status?.exists){
    const ok = window.confirm(
      `Ya existe un archivo (${status.filename}). Esto lo reemplazará. ¿Continuar?`
    );
    if (!ok) return;
  }

  const [data, error] = await uploadFile(`${kind}/upload`, file);
  if (data){
    //Refrescamos 
    qc.invalidateQueries({queryKey: ["status", kind]})
  }
  else {
    console.error(error);
  }
}

//1. handler para ver primeras 50 lineas
const doPreview  = async (kind: "vuelos" | "aereopuertos" | "cancelaciones" ) => {
  const text = await getText(`${kind}/preview`, {lines: 50});
  //Por ahora va como un alert
  alert(text || "(archivo vacío)");
}

//2. handler de descarga
const doDownload = async (kind: "vuelos" | "aereopuertos" | "cancelaciones", filename?: string) => {
  await downloadFile(`${kind}/download`, filename ?? `${kind}.txt`);
}

/**Esto usamos para mostrar el texto de los datos del archivo dentro del Dropzone**/
const renderDropzoneFooter = (
  kind: Kind,
  status?: Status,
  isLoading?: boolean
) => (
  <>
    <div className="text-xs text-muted-foreground">
      {isLoading
        ? "Cargando estado..."
        : status?.exists
          ? <>
              Archivo actual:{" "}
              <button
                className="underline"
                onClick={() => doPreview(kind)}
                title="Ver primeras líneas"
              >
                {status.filename}
              </button>
              {" "}— {status.sizeBytes} bytes — {status.lastModified}
            </>
          : "No hay archivo cargado"}
    </div>

    <div className="flex gap-3 mt-2">
      <button
        className="text-sm underline disabled:opacity-50 hover:cursor-pointer"
        onClick={() => doPreview(kind)}
        disabled={!status?.exists}
      >
        Ver primeras líneas
      </button>
      <button
        className="text-sm underline disabled:opacity-50 hover:cursor-pointer"
        onClick={() => doDownload(kind, status?.filename)}
        disabled={!status?.exists}
      >
        Descargar
      </button>
      {/* si luego se añade DELETE:
      <button
        className="text-sm text-red-600 underline disabled:opacity-50"
        onClick={async () => {
          if (!confirm("¿Eliminar el archivo actual?")) return;
          await del(`/${kind}`);
          qc.invalidateQueries({ queryKey: ["status", kind] });
        }}
        disabled={!status?.exists}
      >
        Eliminar
      </button> */}
    </div>
  </>
);

  const form = useForm<FormValues>({
    resolver,
    defaultValues: { clienteId: "", aeropuerto: "SPIM", cantidad: 1 },
    mode: "onTouched",
  });

  const {begin} = useRunSession();

  const createPedido = useMutation({
    mutationFn: async (v: FormValues) => {
      const req = buildPedidoRequest({
        clienteId: v.clienteId,
        aeropuerto: v.aeropuerto,
        cantidad: v.cantidad,
      });

      const [data, error] = await handleApi(
        postJson<PedidoResponse>("pedidos/crear", req)
      );

      console.log(data);

      if (error){
        //Si hubo error, vamos directamente al onError de más abajo
        throw error;
      }

      //Si todo salió bien, esto llegará como 'data' al onSuccess de abajo
      return data!;


    },
    
    onSuccess: (data) => {
      toast.custom((t) => (
        <ToastCustom
          t={t}
          message={data.message +"✅"}
          type="success"
        />),
      { duration: 5000});
      form.reset({ clienteId: "", aeropuerto: "SPIM", cantidad: 1 });

      //Colocamos lo necesario en el hook
      begin(data.runId);

      //setShowContent(false); //opcional para cerrar el panel
      //navigate("/simulacion"); 

      console.log("🎯 [Registrar] Run iniciado con ID:", data.runId);

    },
    onError: (err: ApiError | Error) => {
      const msg =
        (err as ApiError)?.message ??
        (err as Error)?.message ??
      "Ocurrió un error.";

      toast.custom((t) => (
        <ToastCustom
          t={t}
          message={msg+"❗"}
          type="error"
        />),
      { duration: 5000});
    },
  });

  //console.log("Hola")

  return (
    <div className="mx-auto max-w-6xl px-4 pt-26 grid gap-6 md:grid-cols-2">
      {/* Cargas masivas (glass) */}
      <Card className="backdrop-blur-lg bg-background/30 border-white/40 shadow-lg ring-1 ring-black/5">
        <CardHeader>
          <CardTitle className="text-blue-900">Cargas masivas</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <Dropzone label="Carga masiva de vuelos" onFiles={(fs) => onUpload(fs[0], "vuelos")} 
            footer = {renderDropzoneFooter("vuelos", vuelosStatus.data, vuelosStatus.isLoading)}/>
          <Dropzone label="Carga masiva de husos horarios" onFiles={(fs) => onUpload(fs[0], "aereopuertos")} 
            footer={renderDropzoneFooter("aereopuertos", husosStatus.data, husosStatus.isLoading)}/>
          <Dropzone label="Carga masiva de errores" 
          onFiles={
            (fs) =>toast.custom((t) => (
              <ToastCustom t={t} message={"No implementado archivo: "+fs[0]?.name +" no subido"} type="error" />),
              { duration: Infinity }
            )
            } />
          <Dropzone label="Carga masiva de pedidos" onFiles={(fs) => handleFileUpload(fs[0], "pedidos/upload")} />
        </CardContent>
      </Card>

      {/* Formulario (glass) */}
      <Card className="backdrop-blur-lg bg-background/30 border-white/40 shadow-lg ring-1 ring-black/5">
        <CardHeader>
          <CardTitle className="text-blue-900">Registro de pedido</CardTitle>
        </CardHeader>
        <CardContent>
          <Form {...form}>
            <form
              className="space-y-4"
              onSubmit={form.handleSubmit((v) => createPedido.mutate(v))}
            >
              {/* IdCliente */}
              <FormField
                control={form.control}
                name="clienteId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>ID de cliente:</FormLabel>
                    <Input placeholder="1234" {...field} disabled={createPedido.isPending} />
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Aeropuerto */}
              <FormField
                control={form.control}
                name="aeropuerto"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Destino:</FormLabel>
                    <Select
                      value={field.value}
                      onValueChange={field.onChange}
                      disabled={createPedido.isPending}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Selecciona" />
                      </SelectTrigger>
                      <SelectContent>
                        {airportsMap.map((a) => (
                          <SelectItem key={a.codigo} value={a.codigo}>
                            {a.codigo} – {a.ciudad} ({a.pais})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Cantidad */}
              <FormField
                control={form.control}
                name="cantidad"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Cantidad</FormLabel>
                    <Input
                      type="number"
                      min={1}
                      // permite borrar y que no truene el control
                      value={field.value === 0 ? 0 : field.value ?? ""}
                      onChange={(e) => {
                        const val = e.target.value;
                        field.onChange(val === "" ? "" : Number(val));
                      }}
                      disabled={createPedido.isPending}
                    />
                    <FormMessage />
                  </FormItem>
                )}
              />

              <Button type="submit" className="rounded-full" disabled={createPedido.isPending}>
                {createPedido.isPending ? "Enviando..." : "Enviar"}
              </Button>
            </form>
          </Form>
        </CardContent>
      </Card>

      <div className="md: col-span-2">
        <Card className="w-full backdrop-blur-lg bg-background/30 border-white/40 shadow-lg ring-1 ring-black/5">
          <CardHeader>
            <CardTitle className="text-blue-900">Carga para Operación Diaria</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <Dropzone
              label="Cargar archivo operación diaria"
              onFiles={(fs) => onUpload(fs[0], "operacionDiaria")}
            />
          </CardContent>
        </Card>
      </div>

    </div>
  );
}
