package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderStatus
import bmps.core.models.Order

object OrderService {

    def placeOrders(state: SystemState): SystemState = {
        require(state.fiveMinCandles.nonEmpty, "placeOrders called before there are candles in Trade state.")
        val timestamp = state.fiveMinCandles.last.timestamp
        val updatedOrders = state.orders.zipWithIndex.map { case (order, i) => 
            if (order.status == OrderStatus.Planned && shouldPlaceOrder(order, state.orders)) {
                placeOrder(order, timestamp)
            } else order
        }
        state.copy(orders = updatedOrders)
    }

    private def shouldPlaceOrder(order: Order, allOrders: List[Order]): Boolean = true //TODO ... logic to decie whether to place order

    private def placeOrder(order: Order, timestamp: Long): Order = {
        require(order.status == OrderStatus.Planned, "Tried to place an order that was not in Planned state.")
        order.copy(status = OrderStatus.Placed, placedTimestamp = Some(timestamp))
    }
}
