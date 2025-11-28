export type CancelarVueloRequest = {
    origen: string;
    destino: string;
    salidaUtc: string;
    llegadaUtc: string;
}

export type CancelarVueloResponse = {
  cancelled: boolean;
  message: string;
};