package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus._
import bmps.core.models.Candle
import bmps.core.models.OrderType

trait SimulationAccountBroker extends AccountBroker {

    def placeOrder(order: Order, candle: Candle): Order = {
        order.copy(status = Placed, placedTimestamp = Some(candle.timestamp))
    }
    
    def fillOrder(order: Order, candle: Candle): Order = {
        order.copy(status = Filled, filledTimestamp = Some(candle.timestamp))
    }

    def takeProfit(order: Order, candle: Candle): Order = {
        order.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
    }

    def takeLoss(order: Order, candle: Candle): Order = {
        order.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
    }

    def exitOrder(order: Order, candle: Candle): Order = {
        require(order.status == Filled, s"Can't exit an order in state ${order.status}")
        order.orderType match {
            case OrderType.Long =>
                if (candle.close >= order.entryPoint) takeProfit(order, candle)
                else takeLoss(order, candle)
            case OrderType.Short =>
                if (candle.close <= order.entryPoint) takeProfit(order, candle)
                else takeLoss(order, candle)
        }
    }

    def cancelOrder(order: Order, candle: Candle, cancelReason: String): Order = {
        order.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp), cancelReason = Some(cancelReason))
    }

}
