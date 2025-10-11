package bmps.core.services

import bmps.core.models.EntryType
import bmps.core.models.Direction
import bmps.core.models.SystemState
import bmps.core.models.Candle
import bmps.core.models.Order
import bmps.core.models.OrderType

object FairValueGapOrderBlockService {
    private final val tickSize = 0.25
    
    def processState(state: SystemState): SystemState = {
        require(state.tradingDirection.isDefined, "The direction for trading has not been set")
        require(state.tradingDirection.get != Direction.Doji, "The trading direction cannot be Doji")
        require(state.tradingCandles.nonEmpty, "There must be at least one candle in state.")

        if (state.tradingCandles.size < 3) state else {
            state.tradingDirection.get match {
                case Direction.Down => //Look for a bullish fair value gap
                    val (firstCandle, middleCandle, lastCandle) = findThreeCandles(state)
                    if ((lastCandle.low - firstCandle.high) >= tickSize * 2) { //two ticks minimum gap
                        val newOrder = Order.fromGapCandles(firstCandle, middleCandle, OrderType.Short, EntryType.FairValueGapOrderBlock, lastCandle.timestamp)
                        val allOrders = state.orders :+ newOrder
                        state.copy(orders = allOrders)
                    } else state
                case Direction.Up => //Look for bearish fair value gap
                    val (firstCandle, middleCandle, lastCandle) = findThreeCandles(state)
                    if ((firstCandle.low - lastCandle.high) >= tickSize * 2) { //two ticks minimum gap
                        val newOrder = Order.fromGapCandles(firstCandle, middleCandle, OrderType.Long, EntryType.FairValueGapOrderBlock, lastCandle.timestamp)
                        val allOrders = state.orders :+ newOrder
                        state.copy(orders = allOrders)
                    } else state
                case _ => state
            }
        }
    }

    def shouldPlaceOrder(order: Order, candle: Candle): Boolean = {
        require(order.entryType == EntryType.FairValueGapOrderBlock, s"Using FiarValueGapOrderBlockService with ${order.entryType}.")
        order.orderType match {
            case OrderType.Short => candle.close < order.entryPoint
            case OrderType.Long => candle.close > order.entryPoint
        }
    }

    private def findThreeCandles(state: SystemState): (Candle, Candle, Candle) = {
        // val lastThreeCandles = state.tradingCandles.takeRight(3)
        val lastThreeCandles = state.tradingCandles.reverse.take(3).reverse
        val firstCandle = lastThreeCandles.head
        val middleCandle = lastThreeCandles.tail.head
        val lastCandle = lastThreeCandles.last
        (firstCandle, middleCandle, lastCandle)
    }
}
