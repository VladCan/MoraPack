package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.util.List;

public class PedidoDTO {
    public int id;
    public int idCliente;
    public String destino;
    public int cantidad;

    // opcionales / defaults
    public Integer numPaquetes;         // null si no existe
    public Object origen;               // en tu front es union (string | string[] | null). Aquí mandamos null.
    public int cantidadAsignada;        // 0
    public String estadoAsignacion;     // "PENDIENTE" para que el front lo acepte (pero luego lo mostraremos como "EN BASE")
    public String fechaCreacion;        // UTC del archivo (string ISO)
    public String fechaLocal;           // opcional (puede ser null)
    public String continenteDestino;    // opcional (null)

    public List<Object> rutas;          // [] para no romper tu UI
    public List<Object> recojos;        // [] para no romper tu UI

    public PedidoDTO() {}
}