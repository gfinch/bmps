package bmps.core.strategies.lifecycle

import bmps.core.models.OrderStatus._
import bmps.core.brokers.AccountBroker
import bmps.core.models.Order
import bmps.core.models.Candle
import bmps.core.strategies.rules.OrderLifecycleRules
import bmps.core.utils.TimestampUtils

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
        case Filled if hitStop(order, candle) => leadAccountBroker.exitOrder(order, candle)
        case _ => order
    }

    def profitFilled(order: Order, candle: Candle) = order.status match {
        case Filled if hitProfit(order, candle) => leadAccountBroker.exitOrder(order, candle)
        case _ => order
    }

    def cancelUnfilled(order: Order, candle: Candle) = order.status match {
        case Planned if unfilledTooLong(order, candle, 2) => 
            leadAccountBroker.cancelOrder(order, candle)
        case _ => order
    }

    lazy val lifecycleRules = Seq(
        placeMarketOrder(_,_),
        placeLimitOrder(_,_),
        limitFilled(_,_),
        stopFilled(_,_),
        profitFilled(_,_),
        cancelUnfilled(_,_)
    )

    def manageLifecycle(order: Order, candle: Candle): Order = {
        lifecycleRules.foldLeft(order) { (updatedOrder, next) =>
            next(updatedOrder, candle)
        }
    }

}
