import { z } from "zod";
import { useForm, type Resolver } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Form, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Dropzone } from "@/components/common/Dropzone";
import { postJson } from "@/services/api";
import { uploadFile } from "@/services/fileUpload";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";

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
  const form = useForm<FormValues>({
    resolver,
    defaultValues: { clienteId: "", aeropuerto: "LIM", cantidad: 1 },
    mode: "onTouched",
  });

  const createPedido = useMutation({
    mutationFn: (v: FormValues) => postJson("/pedidos", v),
    onSuccess: (message) => {
      toast.custom((t) => (
        <ToastCustom
          t={t}
          message={message+"✅"}
          type="success"
        />),
      { duration: Infinity });
      form.reset({ clienteId: "", aeropuerto: "LIM", cantidad: 1 });
    },
    onError: (error) => {
      toast.custom((t) => (
        <ToastCustom
          t={t}
          message={error+"❗"}
          type="error"
        />),
      { duration: Infinity });
    },
  });

  return (
    <div className="mx-auto max-w-6xl px-4 pt-26 grid gap-6 md:grid-cols-2">
      {/* Cargas masivas (glass) */}
      <Card className="backdrop-blur-lg bg-background/30 border-white/40 shadow-lg ring-1 ring-black/5">
        <CardHeader>
          <CardTitle className="text-blue-900">Cargas masivas</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <Dropzone label="Carga masiva de vuelos" onFiles={(fs) => handleFileUpload(fs[0], "vuelos/upload")} />
          <Dropzone label="Carga masiva de husos horarios" onFiles={(fs) => handleFileUpload(fs[0], "aereopuertos/upload")} />
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
                    <FormLabel>IdCliente</FormLabel>
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
                    <FormLabel>Aeropuerto</FormLabel>
                    <Select
                      value={field.value}
                      onValueChange={field.onChange}
                      disabled={createPedido.isPending}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Selecciona" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="LIM">LIM – Lima</SelectItem>
                        <SelectItem value="MAD">MAD – Madrid</SelectItem>
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
    </div>
  );
}
