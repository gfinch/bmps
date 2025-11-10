package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.models.Candle
import bmps.core.models.SwingPoint
import bmps.core.models.OrderStatus
import java.time.Duration
import breeze.numerics.round

object BouncingOrderBlockService {
    final val reboundMinThreshold = 0.30 //min % above recent bounce point
    final val reboundMaxThreshold = 0.40 //max % above recent bounce point
    final val stopLossThreshold = 0.25 - reboundMinThreshold //% below recent bounce point
    final val profitCapThreshold = reboundMinThreshold + 0.5

    var orderCount = 0

    def processState(state: SystemState): SystemState = {
        val newOrder = for {
            contract <- state.contractSymbol
            lastOneMinuteCandle <- state.tradingCandles.lastOption
            lastOneSecondCandle <- state.recentOneSecondCandles.lastOption
            lastSwing <- state.tradingSwingPoints.lastOption
        } yield {
            if (!state.orders.exists(_.timestamp > lastSwing.timestamp)) {//There have been no orders since the last swing
                val candlesSinceLastSwing = state.tradingCandles.filter(_.timestamp > lastSwing.timestamp)
                val extremeSinceLastSwing = lastSwing.direction match {
                    case Direction.Up => candlesSinceLastSwing.map(_.high).max
                    case Direction.Down => candlesSinceLastSwing.map(_.low).min
                    case _ => throw new IllegalStateException("Doji candle on swing point is not supported")
                }
                val magnitudeOfNewSwing = math.abs(extremeSinceLastSwing - lastSwing.level) //how far have we swung since lastSwing
                val reboundAmount = lastSwing.direction match { //how far have we rebounded since new swing
                    case Direction.Down => 
                        lastOneSecondCandle.close - extremeSinceLastSwing
                    case Direction.Up =>
                        extremeSinceLastSwing - lastOneSecondCandle.close
                    case _ => 0.0
                }
                val percentOfNewSwing = reboundAmount / (magnitudeOfNewSwing + 0.0000001) //What % have we rebounded?
                val distanceToOldSwing = math.abs(lastOneSecondCandle.close - lastSwing.level)
                if (percentOfNewSwing >= reboundMinThreshold && percentOfNewSwing <= reboundMaxThreshold) { //Time to place an order!
                    val order = lastSwing.direction match {
                        case Direction.Down if qualityChecksOut(lastSwing, lastOneMinuteCandle, lastOneSecondCandle, candlesSinceLastSwing) => 
                            val low = roundToNearestQuarter(extremeSinceLastSwing - (magnitudeOfNewSwing * stopLossThreshold)).toFloat
                            // val high = roundToNearestQuarter(extremeSinceLastSwing + (magnitudeOfNewSwing * reboundThreshold)).toFloat
                            val high = lastOneSecondCandle.close
                            val profitCap = roundToNearestQuarter(extremeSinceLastSwing + (magnitudeOfNewSwing * profitCapThreshold)).toFloat
                            Some(Order(low, high, lastOneMinuteCandle.timestamp, OrderType.Long, EntryType.BouncingOrderBlock, contract, Some(profitCap), OrderStatus.PlaceNow))
                        case Direction.Up if qualityChecksOut(lastSwing, lastOneMinuteCandle, lastOneSecondCandle, candlesSinceLastSwing) => 
                            // val low = roundToNearestQuarter(extremeSinceLastSwing - (magnitudeOfNewSwing * reboundThreshold)).toFloat
                            val low = lastOneSecondCandle.close
                            val high = roundToNearestQuarter(extremeSinceLastSwing + (magnitudeOfNewSwing * stopLossThreshold)).toFloat
                            val profitCap = roundToNearestQuarter(extremeSinceLastSwing - (magnitudeOfNewSwing * profitCapThreshold)).toFloat
                            Some(Order(low, high, lastOneMinuteCandle.timestamp, OrderType.Short, EntryType.BouncingOrderBlock, contract, Some(profitCap), OrderStatus.PlaceNow))
                        case _ => None
                    }
                    if (order.isDefined) {
                        println(s"Order created:")
                        println(s"  Last Swing: direction=${lastSwing.direction}, level=${lastSwing.level}, timestamp=${lastSwing.timestamp}")
                        println(s"  Extreme since last swing: $extremeSinceLastSwing")
                        println(s"  Magnitude of new swing: $magnitudeOfNewSwing")
                        println(s"  Rebound amount: $reboundAmount")
                        println(s"  Percent of new swing: ${percentOfNewSwing * 100}%")
                        println(s"  Distance to old swing: $distanceToOldSwing")
                        println(s"  Last 1-second candle close: ${lastOneSecondCandle.close}")
                        println(s"  Order: ${order.getOrElse("None")}")
                        orderCount = orderCount + 1
                        // if (orderCount >= 4) throw new IllegalArgumentException
                    }
                    
                    order
                } else None //No order here!
            } else None
        }
        newOrder.flatten.map { order =>
            state.copy(orders = state.orders :+ order)
        }.getOrElse(state)
    }

    def qualityChecksOut(lastSwing: SwingPoint, lastOneMinuteCandle: Candle, lastOneSecondCandle: Candle, candlesSinceLastSwing: List[Candle]) = {
        val itsBeenFiveMinutesSinceSwing = (lastOneMinuteCandle.timestamp - lastSwing.timestamp) > Duration.ofMinutes(5L).toMillis
        val directional = lastSwing.direction match {
            case Direction.Up => 
                lastOneSecondCandle.close < lastOneMinuteCandle.open && //still moving down
                lastOneSecondCandle.close > lastSwing.level && //second is on right side of last swing
                lastOneMinuteCandle.direction == Direction.Down && //last minute moving in the right direction
                !candlesSinceLastSwing.exists(_.low < lastSwing.level) //no swings below last swing
            case Direction.Down => lastOneSecondCandle.close > 
                lastOneMinuteCandle.open && //still moving up
                lastOneSecondCandle.close < lastSwing.level &&
                lastOneMinuteCandle.direction == Direction.Up &&
                !candlesSinceLastSwing.exists(_.high > lastSwing.level) //no swings above last swing
            case _ => false
        }

        itsBeenFiveMinutesSinceSwing && directional
    }

    def roundToNearestQuarter(value: Double): Double = {
        (math.round(value * 4.0) / 4.0)
    }
}
