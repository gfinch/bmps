package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus._
import bmps.core.models.Candle

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

    def cancelOrder(order: Order, candle: Candle, cancelReason: String): Order = {
        order.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp), cancelReason = Some(cancelReason))
    }

}
