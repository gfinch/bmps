package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus._
import bmps.core.models.Candle
import bmps.core.models.OrderType
import bmps.core.models.CandleDuration
import bmps.core.utils.TimestampUtils
import bmps.core.models.OrderStatus

trait SimulationAccountBroker extends AccountBroker {

    def placeOrder(order: Order, candle: Candle): Order = {
        require(order.status == Planned || order.status == PlaceNow, s"Can't place an order in state ${order.status}.")
        val placedOrder = order.tickAligned.copy(status = Placed, placedTimestamp = Some(candle.endTime))
        println(placedOrder.log(candle))
        if (order.status == PlaceNow) {
            fillOrder(placedOrder, candle)
        } else placedOrder
    }

    def cancelOrder(order: Order, candle: Candle): Order = {
        require(order.status == Placed, s"Can't cancel an order in state ${order.status}")
        val cancelledOrder = order.copy(status = Cancelled, closeTimestamp = Some(candle.endTime))
        println(cancelledOrder.log(candle))
        cancelledOrder
    }
    
    def fillOrder(order: Order, candle: Candle): Order = {
        require(order.status == Placed, s"Can't fill an order in state ${order.status}")
        val filledOrder = order.copy(status = Filled, filledTimestamp = Some(candle.endTime))
        println(filledOrder.log(candle))
        filledOrder
    }

    def exitOrder(order: Order, candle: Candle, exitPrice: Double): Order = {
        require(order.status == Filled, s"Can't exit an order in state ${order.status}")
        val profit = order.profit(candle)
        val status = if (profit >= 0.0) Profit else Loss
        val closedOrder = order.copy(status = status, closeTimestamp = Some(candle.endTime), exitPrice = Some(exitPrice))
        println(closedOrder.log(candle))
        closedOrder
    }

    def resetStop(order: Order, stop: Double, candle: Candle): Order = {
        val resetOrder = order.copy(stopLoss = stop)
        println(resetOrder.log(candle))
        resetOrder
    }

    def reconcileOrder(order: Order): Order = order

}
