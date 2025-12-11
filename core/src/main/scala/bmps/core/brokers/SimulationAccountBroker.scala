package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus._
import bmps.core.models.Candle
import bmps.core.models.OrderType
import bmps.core.models.CandleDuration

trait SimulationAccountBroker extends AccountBroker {

    def placeOrder(order: Order, candle: Candle): Order = {
        val ts = snapToOneMinute(candle)
        order.copy(status = Placed, placedTimestamp = Some(ts))
    }
    
    def fillOrder(order: Order, candle: Candle): Order = {
        val ts = snapToOneMinute(candle)
        order.copy(status = Filled, filledTimestamp = Some(ts))
    }

    def takeProfit(order: Order, candle: Candle): Order = {
        val ts = snapToOneMinute(candle)
        order.copy(status = Profit, closeTimestamp = Some(ts))
    }

    def takeLoss(order: Order, candle: Candle): Order = {
        val ts = snapToOneMinute(candle)
        order.copy(status = Loss, closeTimestamp = Some(ts))
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
        val ts = snapToOneMinute(candle)
        order.copy(status = Cancelled, closeTimestamp = Some(ts), cancelReason = Some(cancelReason))
    }

    private def snapToOneMinute(candle: Candle): Long = {
        if (candle.duration == CandleDuration.OneSecond) {
            val oneMinuteMillis = 60000L
            val remainder = candle.timestamp % oneMinuteMillis
            if (remainder == 0) candle.timestamp
            else candle.timestamp + (oneMinuteMillis - remainder)
        } else candle.timestamp
    }

}
