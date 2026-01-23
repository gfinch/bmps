package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus._
import bmps.core.models.Candle
import bmps.core.models.OrderType
import bmps.core.models.CandleDuration
import bmps.core.utils.TimestampUtils

trait SimulationAccountBroker extends AccountBroker {

    private def roundToTickSize(price: Float, tickSize: Float = 0.25f): Float = {
        // Use integer arithmetic to avoid floating point precision errors
        // For 0.25 tick: multiply by 4, round, then divide by 4
        val multiplier = (1.0 / tickSize).toInt
        (math.round(price * multiplier).toFloat / multiplier)
    }

    def placeOrder(order: Order, candle: Candle): Order = {
        val ts = snapToOneMinute(candle)
        // Round order prices to tick size
        val roundedOrder = order.copy(
            low = roundToTickSize(order.low),
            high = roundToTickSize(order.high),
            profitCap = order.profitCap.map(roundToTickSize(_))
        )
        println(s"${TimestampUtils.toNewYorkTimeString(candle.timestamp)} - Place ${order.orderType} - ${candle.close} - ${order.entryPoint}.")
        roundedOrder.copy(status = Placed, placedTimestamp = Some(ts))
    }
    
    def fillOrder(order: Order, candle: Candle): Order = {
        println(s"${TimestampUtils.toNewYorkTimeString(candle.timestamp)} - Fill ${order.orderType} - ${candle.low} < ${order.entryPoint} < ${candle.high}. (${(candle.timestamp - order.placedTimestamp.getOrElse(0L)) / 60000.0}m)")
        val ts = snapToOneMinute(candle)
        order.copy(status = Filled, filledTimestamp = Some(ts))
    }

    def takeProfit(order: Order, candle: Candle): Order = {
        println(s"${TimestampUtils.toNewYorkTimeString(candle.timestamp)} - Profit ${order.orderType} - ${candle.low} < ${order.takeProfit} < ${candle.high} (${(candle.timestamp - order.placedTimestamp.getOrElse(0L)) / 60000.0}m)")
        val ts = snapToOneMinute(candle)
        val closedAt = if (TimestampUtils.isNearTradingClose(candle.timestamp)) Some(candle.close) else None
        order.copy(status = Profit, closeTimestamp = Some(ts), closedAt = closedAt)
    }

    def takeLoss(order: Order, candle: Candle): Order = {
        println(s"${TimestampUtils.toNewYorkTimeString(candle.timestamp)} - Loss ${order.orderType} - ${candle.low} < ${order.stopLoss} < ${candle.high}. (${(candle.timestamp - order.placedTimestamp.getOrElse(0L)) / 60000.0}m)")
        val ts = snapToOneMinute(candle)
        val closedAt = if (TimestampUtils.isNearTradingClose(candle.timestamp)) Some(candle.close) else None
        order.copy(status = Loss, closeTimestamp = Some(ts), closedAt = closedAt)
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
        println(s"${TimestampUtils.toNewYorkTimeString(candle.timestamp)} - Cancel ${order.orderType} - $cancelReason. (${(candle.timestamp - order.placedTimestamp.getOrElse(0L)) / 60000.0}m)")
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
