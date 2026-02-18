package bmps.core.strategies.rules

import bmps.core.models.SystemState
import bmps.core.models.Candle
import bmps.core.models.OrderStatus._
import bmps.core.models.Order
import bmps.core.models.OrderType
import java.time.Duration

trait OrderEntryRules {
    def noActiveOrder(state: SystemState, candle: Candle): Boolean = {
        !state.orders.exists(_.isActive)
    }
}

trait OrderLifecycleRules {
    def readyToPlaceLimitOrder(order: Order, candle: Candle): Boolean = {
        if (order.status == Planned) order.orderType match {
            case OrderType.Long if candle.close > order.entryPrice => true
            case OrderType.Short if candle.close < order.entryPrice => true
            case _ => false
        } else false
    }

    def hitLimit(order: Order, candle: Candle): Boolean = {
        if (order.status == Placed) order.orderType match {
            case OrderType.Long if candle.low <= order.entryPrice => true
            case OrderType.Short if candle.high >= order.entryPrice => true
            case _ => false
        } else false
    }

    def hitStop(order: Order, candle: Candle): Boolean = {
        val didHitStop = hitStopSimple(order, candle)
        val didHitProfit = hitProfitSimple(order, candle)
        if (didHitStop && didHitProfit) order.orderType match {
            case OrderType.Long if candle.isBullish => true
            case OrderType.Short if candle.isBearish => true
            case _ => false
        } else didHitStop
    }

    def hitProfit(order: Order, candle: Candle): Boolean = {
        val didHitStop = hitStopSimple(order, candle)
        val didHitProfit = hitProfitSimple(order, candle)
        if (didHitStop && didHitProfit) order.orderType match {
            case OrderType.Long if candle.isBearish || candle.isDoji => true
            case OrderType.Short if candle.isBullish || candle.isDoji => true
            case _ => false
        } else didHitProfit
    }

    def unfilledTooLong(order: Order, candle: Candle, minutes: Int): Boolean = {
        if(order.status == Planned || order.status == Placed) {
            val plus = Duration.ofMinutes(minutes).toMillis()
            laterTimestamp(order.placedTimestamp, candle, plus)
        } else false
    }

    private def hitStopSimple(order: Order, candle: Candle): Boolean = {
        if (order.status == Filled && 
            laterTimestamp(order.filledTimestamp, candle)
        ) order.orderType match {
            case OrderType.Long if candle.low <= order.stopLoss => true
            case OrderType.Short if candle.high >= order.stopLoss => true
            case _ => false
        } else false
    }

    private def hitProfitSimple(order: Order, candle: Candle): Boolean = {
        if (order.status == Filled &&
            laterTimestamp(order.filledTimestamp, candle)
        ) order.orderType match {
            case OrderType.Long if candle.high >= order.takeProfit => true
            case OrderType.Short if candle.low <= order.takeProfit => true
            case _ => false
        } else false
    }

    private def laterTimestamp(orderTimestamp: Option[Long], candle: Candle, plus: Long = 0L): Boolean = {
        candle.endTime >= (orderTimestamp.getOrElse(0L) + plus)
    }
}

trait OrderExitRules {

}
