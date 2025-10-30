type PedidoRequest = {
    idCliente: number;
    destino: string;
    fecha: string;
    cantidad: number;
}

type PedidoUI = {
    clienteId: string | number;
    aeropuerto: string;   // código seleccionado en el <Select>
    cantidad: number;
    now?: Date;
}

// util: ISO local sin zona (ej: "2025-10-19T10:17:33")
function toLocalIsoNoTZ(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  const yyyy = d.getFullYear();
  const MM   = pad(d.getMonth() + 1);
  const dd   = pad(d.getDate());
  const HH   = pad(d.getHours());
  const mm   = pad(d.getMinutes());
  const ss   = pad(d.getSeconds());
  return `${yyyy}-${MM}-${dd}T${HH}:${mm}:${ss}`;
}

export function buildPedidoRequest(
    ui: PedidoUI
): PedidoRequest {
    if (!ui.clienteId && ui.clienteId !== 0) throw new Error("Falta idCliente");
    if (!ui.aeropuerto) throw new Error("Falta destino");
    if (!ui.cantidad || ui.cantidad <= 0) throw new Error("Cantidad inválida");

    const fecha = toLocalIsoNoTZ(ui.now ?? new Date());

    return {
        idCliente: Number(ui.clienteId),
        destino: ui.aeropuerto,
        fecha,
        cantidad: ui.cantidad,
    }

}