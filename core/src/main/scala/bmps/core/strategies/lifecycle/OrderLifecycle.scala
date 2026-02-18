package bmps.core.strategies.lifecycle

import bmps.core.models.OrderStatus._
import bmps.core.brokers.AccountBroker
import bmps.core.models.Order
import bmps.core.models.Candle
import bmps.core.strategies.rules.OrderLifecycleRules
import bmps.core.utils.TimestampUtils
import bmps.core.models.OrderType

trait OrderLifecycle extends OrderLifecycleRules {

    val leadAccountBroker: AccountBroker
    
    def placeLimitOrder(order: Order, candle: Candle): Order = order.status match {
        case Planned if readyToPlaceLimitOrder(order, candle) => 
            leadAccountBroker.placeOrder(order, candle)
        case _ => order
    }

    def placeMarketOrder(order: Order, candle: Candle): Order = order.status match {
        case PlaceNow => 
            leadAccountBroker.placeOrder(order, candle)
        case _ => order
    }

    def limitFilled(order: Order, candle: Candle): Order = order.status match {
        case Placed if hitLimit(order, candle) => leadAccountBroker.fillOrder(order, candle)
        case _ => order
    }

    def stopFilled(order: Order, candle: Candle) = order.status match {
        case Filled if hitStop(order, candle) => leadAccountBroker.exitOrder(order, candle, order.stopLoss)
        case _ => order
    }

    def profitFilled(order: Order, candle: Candle) = order.status match {
        case Filled if hitProfit(order, candle) => leadAccountBroker.exitOrder(order, candle, order.takeProfit)
        case _ => order
    }

    def cancelUnfilled(order: Order, candle: Candle) = order.status match {
        case Planned if unfilledTooLong(order, candle, 5) => 
            leadAccountBroker.cancelOrder(order, candle)
        case Placed if unfilledTooLong(order, candle, 5) => 
            leadAccountBroker.cancelOrder(order, candle)
        case _ => order
    }

    def cancelLateInDay(order: Order, candle: Candle) = order.status match {
        case Placed if TimestampUtils.isNearTradingClose(candle.endTime) =>
            leadAccountBroker.cancelOrder(order, candle)
        case Filled if TimestampUtils.isNearTradingClose(candle.endTime) =>
            leadAccountBroker.exitOrder(order, candle, candle.close)
        case _ => order
    }

    def resetStop(order: Order, candle: Candle) = order.status match {
        case Filled if order.trailStop.isDefined =>
            val stopGap = order.trailStop.get
            order.orderType match {
                case OrderType.Long => 
                    if (candle.high - order.stopLoss > stopGap) {
                        val newStop = candle.high - stopGap
                        leadAccountBroker.resetStop(order, newStop, candle)
                    } else order
                case OrderType.Short => 
                    if (order.stopLoss - candle.low > stopGap) {
                        val newStop = candle.low + stopGap
                        leadAccountBroker.resetStop(order, newStop, candle)
                    } else order
            }
        case _ => order
    }

    lazy val lifecycleRules = Seq(
        limitFilled(_,_),
        stopFilled(_,_),
        profitFilled(_,_),
        placeMarketOrder(_,_),
        placeLimitOrder(_,_),
        resetStop(_,_),
        cancelUnfilled(_,_),
        cancelLateInDay(_,_)
    )

    def manageLifecycle(order: Order, candle: Candle): Order = {
        lifecycleRules.foldLeft(order) { (updatedOrder, next) =>
            next(updatedOrder, candle)
        }
    }

}
