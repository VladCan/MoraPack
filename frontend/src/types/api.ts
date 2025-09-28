export interface AeropuertoDTO {
  codigo: string;
  ciudad: string;
  pais: string;
  gmt: number | null;
  capacidad: number | null;
  lat: number | null;
  lon: number | null;
  continente: string | null;
  sede: boolean; // <- importante
}

export interface FlightLiveDTO {
  id: string;
  origen: string;
  destino: string;
  originLat: number;
  originLon: number;
  destLat: number;
  destLon: number;
  progress: number;   // 0..1
  pathColor: string;
  planeColor: string;
}
