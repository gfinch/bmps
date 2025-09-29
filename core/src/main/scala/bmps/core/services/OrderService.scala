package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderStatus
import bmps.core.models.Order
import bmps.core.models.Event
import bmps.core.models.PlanZoneType
import bmps.core.models.Direction

object OrderService {

    def determineTradingDirection(state: SystemState): Direction = {
        val supplyCount = state.planZones.count(z => z.isActive && z.planZoneType == PlanZoneType.Supply)
        val demandCount = state.planZones.count(z => z.isActive && z.planZoneType == PlanZoneType.Demand)
        if (demandCount >= supplyCount) Direction.Up else Direction.Down
    }

    def buildOrders(state: SystemState): SystemState = {
        //TODO ... need to determine order direction
        //TODO ... need to add other entry models
        if (state.orders.size < 5) {
            require(state.tradingCandles.nonEmpty, "buildOrders called before there are candles in Trade state.")
            EngulfingOrderBlockService.processState(state)
        } else state
    }
    
    def placeOrders(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "placeOrders called before there are candles in Trade state.")
        val timestamp = state.tradingCandles.last.timestamp
        val updatedOrders = state.orders.zipWithIndex.map { case (order, i) => 
            if (order.status == OrderStatus.Planned && shouldPlaceOrder(order, state)) {
                placeOrder(order, timestamp)
            } else order
        }
        state.copy(orders = updatedOrders)
    }

    private def shouldPlaceOrder(order: Order, state: SystemState): Boolean = {
        val activeOrders = state.orders.count(_.isActive)
        val isRightDirection = state.tradingDirection.exists(_ == order.direction)
        //TODO more sophisticated logic
        (activeOrders == 0 && isRightDirection)
    }

    private def placeOrder(order: Order, timestamp: Long): Order = {
        require(order.status == OrderStatus.Planned, "Tried to place an order that was not in Planned state.")
        order.copy(status = OrderStatus.Placed, placedTimestamp = Some(timestamp))
    }
}
