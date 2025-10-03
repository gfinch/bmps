package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.models.Candle

object EngulfingOrderBlockService {

    def processState(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "There must be at least one candle in state.")

        val test = state.tradingCandles.last
        val subjects = state.tradingCandles.reverse.tail.take(3)
        val newOrder = subjects.find(s => test.engulfs(s) && test.isOpposite(s)).flatMap { subject =>
            test.direction match {
                case Direction.Up if test.close > subject.high && precededBySteepChange(test, subject, state) && areSubstantialCandles(test, subject) => 
                    Some(Order.fromCandle(subject, OrderType.Long, EntryType.EngulfingOrderBlock, test.timestamp))
                case Direction.Down if test.close < subject.low && precededBySteepChange(test, subject, state) && areSubstantialCandles(test, subject) =>
                    Some(Order.fromCandle(subject, OrderType.Short, EntryType.EngulfingOrderBlock, test.timestamp))
                case _ => None
            }
        }

        newOrder.map { order =>
            state.copy(orders = state.orders :+ order)
        }.getOrElse(state)
    }

    def shouldPlaceOrder(order: Order, candle: Candle): Boolean = {
        require(order.entryType == EntryType.EngulfingOrderBlock, s"Called EngulfingOrderBlock.shouldPlace order with ${order.entryType}")
        order.orderType match {
            case OrderType.Long => candle.close.value >= order.entryPoint && candle.timestamp > order.timestamp
            case OrderType.Short => candle.close.value <= order.entryPoint && candle.timestamp > order.timestamp
            case _ => false
        }
    }

    private def areSubstantialCandles(test: Candle, subject: Candle): Boolean = {
        test.bodyHeight.value > 1.5 && test.bodyHeight.value - subject.bodyHeight.value > 1.5
    }

    private def precededBySteepChange(test: Candle, subject: Candle, state: SystemState): Boolean = {
        val idx = state.tradingCandles.indexOf(subject)
        if (idx > 8) {
            val eightMinutesAgo = state.tradingCandles(idx - 8)
            subject.direction match {
                case Direction.Down => 
                    val minDecline = 3 * subject.bodyHeight.value
                    val decline = eightMinutesAgo.high.value - test.close.value
                    decline >= minDecline
                case Direction.Up =>
                    val minIncline = 3 * subject.bodyHeight.value
                    val incline = test.close.value - eightMinutesAgo.low.value
                    incline >= minIncline
                case _ => false
            }
        } else false
    }
}
