package pe.edu.pucp.morapack.airscheduler.api.mapper;

import pe.edu.pucp.morapack.airscheduler.api.controllers.PedidosController;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;

import java.time.LocalDateTime;

public final class PedidoMapper {
    //Estoy colocándolo aquí por efectos de brevedad (para que al crearse el pedido ya tenga su id)
    private static int nextId = 1;

    private PedidoMapper() {}

    //Sé que esto es para DTOs, pero como estamos transformando a Model, tiene que ir acá
    public static Pedido toPedido(PedidosController.PedidoRequest pedidoRequest) {
        if (pedidoRequest == null) return null;
        return new Pedido(
                nextId++,
                pedidoRequest.idCliente,
                pedidoRequest.destino,
                LocalDateTime.parse(pedidoRequest.fecha),
                pedidoRequest.cantidad
        );
    }

}
