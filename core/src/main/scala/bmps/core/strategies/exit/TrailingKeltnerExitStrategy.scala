package bmps.core.strategies.exit

import bmps.core.models.ExitStrategy
import bmps.core.brokers.AccountBroker
import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.OrderType
import bmps.core.models.OrderStatus
import bmps.core.strategies.zones.Zones

/**
 * TrailingKeltnerExitStrategy combines the GoldDeathCrossExitStrategy exit logic with
 * a trailing stop based on the Keltner Channel.
 * 
 * Behavior:
 * - If order is losing more than $200: use tight exit criteria (MA cross or weak trend)
 * - Otherwise (profit >= -$200): give trade room to work
 *   - For Long orders: trail stop loss to (Keltner lower band - 3 ATRs)
 *     if that value is higher than the current stop loss
 *   - For Short orders: trail stop loss to (Keltner upper band + 3 ATRs)
 *     if that value is lower than the current stop loss
 * 
 * This allows small losers and breakeven trades to potentially turn into winners.
 */
class TrailingKeltnerExitStrategy extends ExitStrategy {
    override def adjustOrder(leadAccountBroker: AccountBroker, state: SystemState, order: Order): Seq[Order] = {
        // Only act on orders that are still actionable
        val isActionable = order.status match {
            case OrderStatus.Planned | OrderStatus.PlaceNow | OrderStatus.Placed | OrderStatus.Filled => true
            case _ => false
        }
        if (!isActionable) return Seq(order)

        if (state.tradingCandles.isEmpty) return Seq(order)

        val lastCandle = state.tradingCandles.last
        val currentProfit = order.profit(lastCandle)

        // If losing more than $200, use tight exit logic (MA cross or weak trend)
        // Otherwise, let the trade run with trailing Keltner stops
        if (currentProfit < -200) {
            return applyGoldDeathCrossLogic(leadAccountBroker, state, order)
        }

        // Profit >= -200: adjust stop loss based on Keltner channel (give trade room to work)
        if (state.recentVolatilityAnalysis.isEmpty) return Seq(order)

        val volatility = state.recentVolatilityAnalysis.last
        val keltner = volatility.keltnerChannels
        val atr = volatility.trueRange.atr

        val adjustedOrder = order.orderType match {
            case OrderType.Long =>
                // For long: new stop = Keltner lower band - 3 ATRs
                val newStopCandidate = keltner.lowerBand - (3 * atr)
                // Only update if new stop is higher than current stop (trailing up)
                if (newStopCandidate > order.stopLoss) {
                    order.copy(stopLoss = newStopCandidate)
                } else {
                    order
                }
            case OrderType.Short =>
                // For short: new stop = Keltner upper band + 3 ATRs
                val newStopCandidate = keltner.upperBand + (3 * atr)
                // Only update if new stop is lower than current stop (trailing down)
                if (newStopCandidate < order.stopLoss) {
                    order.copy(stopLoss = newStopCandidate)
                } else {
                    order
                }
        }

        Seq(adjustedOrder)
    }

    /**
     * GoldDeathCrossExitStrategy logic: exit on MA crossover against position
     */
    private def applyGoldDeathCrossLogic(leadAccountBroker: AccountBroker, state: SystemState, order: Order): Seq[Order] = {
        // Require at least 2 trend analyses to detect a crossover
        if (state.recentTrendAnalysis.size < 2) return Seq(order)
        if (state.tradingCandles.isEmpty) return Seq(order)

        val currentTrend = state.recentTrendAnalysis.last

        // Detect crossover (transition, not just current state)
        val goldenCrossOccurred = currentTrend.isGoldenCross
        val deathCrossOccurred = currentTrend.isDeathCross
        val weakTrendStrength = Zones.trendStrengthNMinutesAgo(state, 0) < 3.0
        val lastCandle = state.tradingCandles.last

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
