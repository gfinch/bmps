package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.EntryType

object EngulfingOrderBlockService {
    //Assume we have a trading direction set for the day: Up or Down
    def processState(state: SystemState): SystemState = {
        require(state.tradingDirection.isDefined, "The direction for trading has not been set")
        require(state.tradingDirection.get != Direction.Doji, "The trading direction cannot be Doji")
    require(state.tradingCandles.nonEmpty, "There must be at least one candle in state.")

        state.tradingDirection.get match {
            case Direction.Up => 
                val test = state.tradingCandles.last
                val subjects = state.tradingCandles.reverse.tail.take(3)
                subjects.find(s => test.engulfs(s) && test.close > s.high).map { subject =>
                    val newOrder = Order.fromCandle(subject, OrderType.Long, EntryType.EngulfingOrderBlock, test.timestamp)
                    val allOrders = state.orders :+ newOrder
                    state.copy(orders = allOrders)
                }.getOrElse(state)
            case Direction.Down => 
                val test = state.tradingCandles.last
                val subjects = state.tradingCandles.reverse.tail.take(3)
                subjects.find(s => test.engulfs(s) && test.close < s.low).map { subject =>
                    val newOrder = Order.fromCandle(subject, OrderType.Short, EntryType.EngulfingOrderBlock, test.timestamp)
                    val allOrders = state.orders :+ newOrder
                    state.copy(orders = allOrders)
                }.getOrElse(state)
            case _ => state
        }
    }
}
