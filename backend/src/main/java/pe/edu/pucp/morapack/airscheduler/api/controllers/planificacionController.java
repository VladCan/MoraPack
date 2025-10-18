package pe.edu.pucp.morapack.airscheduler.api.controllers;

public class planificacionController {
    //TODO -> jamil acá tienes que crear un par de endpoints que le manden al front toda la info
    //este es el plan
    //TODO -> RELOJ SSE <--- 
    /*
     * 
FRONT:

SE GENERÓ TODO RESULTADO ====> solucionAnterior

vuelos{
    string idVuelo //origen+destino+fechaSalida LIMEEUU20250930150000
    string salida
    string llega
    date horaUTCsalida
    date horaUTCllegada
}
vueloConPedido{
    Vuelo vuelo
    List<string> idsPedidos
    List<double> cantidadEntragadaPorPedido
    double cargatotal
}


vuelosConPedidos => List<vueloConPedido>

pedidos= sacarTodosLosPedidos(solucionAnterior) //lista que no cambia está estático 10am 4pm
    pedidosActivos
    pedidosCompletados //tiene el historial
vuelosConPedidos= convertirSolucion(solucionAnterior) //lista que no cambia está estático
    vuelosActivos
    vuelosCompletados //tiene el historial
aereopuertos= aereopuertos //lista que no cambia está estático
    aereopuertoEstadoActual
    estadoAntiguo (on demand) //tiene el historial

SSE --> conexion con el back (verifica si la info del front está actualizada)
    pedidosActivos
    pedidosCompletados
    vuelosActivos
    vuelosCompletados
    aereopuertoEstadoActual

cola colaDeEventos= new cola(solucionAnterior)
    2025-09-30-22-06 avion despega 202509302206LIMEEUU
    2025-09-30-22-10 aereopuerto brucelas +50 productos
    2025-09-31-06-15 avion llega 202509302215LIMEEUU

for(;;){// si ocurre algo le a avisar al front
    if(reloj=colaDeEventos.ultimoEvento().tiempo()){
        actualizar(colaDeEventos){
            pedidosActivos
            pedidosCompletados
            vuelosActivos
            vuelosCompletados
            aereopuertoEstadoActual
        }
    }
    if(pedidosActivos.vacio())break;
}
*/
/*
 * linea de tiempo entre ejecuciones
 * 200 pedidos
 * 10:00am                                  solucionAnterior1
 * 300 pedidos                              solucionAnterior1
 * 4:00pm se ejecuta el algoritmo           solucionAnterior1
 * aterrizo un avion
 * se recogio un pedido
 * 4:04pm se sigue ejecutando               solucionAnterior1
 * 4:04pm                                   solucionAnterior1 (estado10002693)
 * 4:04:01pm                                solucionAnterior2 (estado10002693)
 * 
 * 10:00 pm
 * 
 * 
 * 
 */
}
