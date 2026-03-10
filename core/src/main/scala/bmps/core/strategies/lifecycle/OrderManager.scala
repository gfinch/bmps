package bmps.core.strategies.lifecycle

import bmps.core.brokers.AccountBroker
import bmps.core.models.SystemState
import bmps.core.models.Candle

class OrderManager(val leadAccountBroker: AccountBroker) extends OrderLifecycle with OrderBuilder {
    def manageOrdersOneSecond(state: SystemState, candle: Candle): SystemState = {
        val updatedOrders = state.orders.map(o => manageLifecycle(o, candle))
        state.copy(orders = updatedOrders)
    }

    def manageOrdersOneMinute(state: SystemState, candle: Candle): SystemState = {
        executeExitStrategies(state, candle)
    }

    def buildAndPlaceOrders(state: SystemState, candle: Candle): SystemState = {
        buildOrders(state) match {
            case Some(order) => 
                println(order.log(candle))
                val placedOrder = placeMarketOrder(order, state.tradingCandles.last)
                state.copy(orders = state.orders :+ order)
            case _ => state
        }
    }
}
