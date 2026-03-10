package bmps.core.strategies.exit

import bmps.core.models.ExitStrategy
import bmps.core.brokers.AccountBroker
import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.OrderType
import bmps.core.models.OrderStatus
import bmps.core.strategies.zones.Zones

/**
 * GoldDeathCrossExitStrategy exits positions when a moving average crossover occurs
 * against the position direction.
 * 
 * Exit condition:
 * - For Long orders: exit when a death cross occurs (short MA crosses below long MA)
 * - For Short orders: exit when a golden cross occurs (short MA crosses above long MA)
 * 
 * This provides a momentum-based exit signal that doesn't wait for price to reach
 * specific channel levels.
 */
class GoldDeathCrossExitStrategy extends ExitStrategy {
    override def adjustOrder(leadAccountBroker: AccountBroker, state: SystemState, order: Order): Seq[Order] = {
        // Only act on orders that are still actionable
        val isActionable = order.status match {
            case OrderStatus.Planned | OrderStatus.PlaceNow | OrderStatus.Placed | OrderStatus.Filled => true
            case _ => false
        }
        if (!isActionable) return Seq(order)

        // Require at least 2 trend analyses to detect a crossover
        if (state.recentTrendAnalysis.size < 2) return Seq(order)
        if (state.tradingCandles.isEmpty) return Seq(order)

        val currentTrend = state.recentTrendAnalysis.last

        // Detect crossover (transition, not just current state)
        val goldenCrossOccurred = currentTrend.isGoldenCross
        val deathCrossOccurred = currentTrend.isDeathCross
        val weakTrendStrength = Zones.trendStrengthNMinutesAgo(state, 0) < 3.0
        val lastCandle = state.tradingCandles.last

        val currentProfit = order.profit(lastCandle)

        // Exit when cross goes against position
        val shouldExit = order.orderType match {
            // Long position - exit on death cross (bearish signal)
            case OrderType.Long => deathCrossOccurred || weakTrendStrength
            // Short position - exit on golden cross (bullish signal)
            case OrderType.Short => goldenCrossOccurred || weakTrendStrength
        }

        if (shouldExit) {
            val exitPrice = lastCandle.close
            val exitedOrder = leadAccountBroker.exitOrder(order, lastCandle, exitPrice)
            Seq(exitedOrder)
        } else {
            Seq(order)
        }
    }
}
