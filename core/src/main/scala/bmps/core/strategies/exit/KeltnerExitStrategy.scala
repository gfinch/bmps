package bmps.core.strategies.exit

import bmps.core.models.ExitStrategy
import bmps.core.brokers.AccountBroker
import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.OrderType
import bmps.core.models.OrderStatus

/**
 * KeltnerExitStrategy exits positions when price wicks across the opposite Keltner Channel.
 * 
 * Exit condition:
 * - For Long orders (entered above upper channel): exit when candle wicks below lower channel
 * - For Short orders (entered below lower channel): exit when candle wicks above upper channel
 * 
 * As long as price stays within the channel, we're likely still trending in our direction.
 */
class KeltnerExitStrategy extends ExitStrategy {
    override def adjustOrder(leadAccountBroker: AccountBroker, state: SystemState, order: Order): Seq[Order] = {
        // Only act on orders that are still actionable
        val isActionable = order.status match {
            case OrderStatus.Planned | OrderStatus.PlaceNow | OrderStatus.Placed | OrderStatus.Filled => true
            case _ => false
        }
        if (!isActionable) return Seq(order)

        // Require volatility analysis
        if (state.recentVolatilityAnalysis.isEmpty) return Seq(order)
        if (state.tradingCandles.isEmpty) return Seq(order)

        val lastCandle = state.tradingCandles.last
        val volatility = state.recentVolatilityAnalysis.last
        val keltner = volatility.keltnerChannels

        // Exit when wick crosses the opposite channel from entry
        val shouldExit = order.orderType match {
            // Long entered above upper channel - exit if wick goes below lower channel
            case OrderType.Long => lastCandle.low < keltner.lowerBand
            // Short entered below lower channel - exit if wick goes above upper channel
            case OrderType.Short => lastCandle.high > keltner.upperBand
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
