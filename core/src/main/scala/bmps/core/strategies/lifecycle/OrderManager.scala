package bmps.core.strategies.lifecycle

import bmps.core.brokers.AccountBroker
import bmps.core.models.SystemState
import bmps.core.models.Candle

class OrderManager(val leadAccountBroker: AccountBroker) extends OrderLifecycle with OrderBuilder {
    def manageOrders(state: SystemState, candle: Candle): SystemState = {
        val updatedOrders = state.orders.map(o => manageLifecycle(o, candle))
        state.copy(orders = updatedOrders)
    }

    def buildAndPlaceOrders(state: SystemState, candle: Candle): SystemState = {
        buildOrders(state) match {
            case Some(order) => 
                val placedOrder = placeMarketOrder(order, state.tradingCandles.last)
                state.copy(orders = state.orders :+ order)
            case _ => state
        }
    }
}
