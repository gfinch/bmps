package bmps.core.strategies.entrance

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.EntryStrategy
import bmps.core.strategies.exit.IceInVeinsExitStrategy
import bmps.core.strategies.zones.Zones
import bmps.core.strategies.zones.ZoneId
import bmps.core.utils.TimestampUtils

/**
 * IceInVeinsEntryStrategy detects when a candle body is entirely outside the Keltner Channel.
 * 
 * Entry conditions:
 * - Long: Candle body (open and close) is above the upper Keltner Channel and bullish
 * - Short: Candle body (open and close) is below the lower Keltner Channel and bearish
 * - No entries in the first 30 minutes of trading
 * - No more than 1 significant loss ($100+) today
 * - Stop if total wins for the day >= $2000
 * 
 * This is a PlaceNow (market) order with:
 * - Entry price: candle close price
 * - Stop loss: opposite Keltner channel + (2 * ATR)
 * - Take profit: 100 * ATR from entry
 * - No trailing stop
 */
trait IceInVeinsEntryStrategy {
    private final val exitStrategy = new IceInVeinsExitStrategy()
    private final val ThirtyMinutesMs = 30 * 60 * 1000L

    /**
     * Main trigger method - checks if a candle body is outside the Keltner Channel.
     * Returns Some((OrderType, EntryStrategy, setupFn)) if triggered, None otherwise.
     */
    def isIceInVeinsTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        // Require volatility analysis and candles
        if (state.recentVolatilityAnalysis.isEmpty) return None
        if (state.tradingCandles.isEmpty) return None
        
        val lastCandle = state.tradingCandles.last
        val tradingDay = state.tradingDay
        val marketOpen = TimestampUtils.newYorkOpen(tradingDay)
        
        // Don't enter in the first 30 minutes of trading
        if (lastCandle.endTime < marketOpen + ThirtyMinutesMs) return None
        
        // Don't trigger if there have been 2 significant losses ($100+) already today
        val significantLossesToday = state.orders.count { order =>
            order.status == OrderStatus.Loss && 
            order.closedProfit.exists(profit => profit <= -100.0)
        }
        if (significantLossesToday >= 2) return None
        
        // Don't trigger if total wins for the day >= $2000
        val totalWinsToday = state.orders
            .filter(_.status == OrderStatus.Profit)
            .flatMap(_.closedProfit)
            .sum
        if (totalWinsToday >= 2000.0) return None

        val currentVol = state.recentVolatilityAnalysis.last
        val keltner = currentVol.keltnerChannels

        // Check if candle body is outside Keltner Channel
        // Long: open and close are above the upper band and bullish
        val longSignal = lastCandle.open > keltner.upperBand && lastCandle.isBullish
        
        // Short: open and close are below the lower band and bearish
        val shortSignal = lastCandle.open < keltner.lowerBand && lastCandle.isBearish

        if (longSignal) {
            val entryStrategy = EntryStrategy(s"IceInVeinsEntryStrategy")
            Some((OrderType.Long, entryStrategy, iceInVeinsSetup))
        } else if (shortSignal) {
            val entryStrategy = EntryStrategy(s"IceInVeinsEntryStrategy")
            Some((OrderType.Short, entryStrategy, iceInVeinsSetup))
        } else {
            None
        }
    }

    /**
     * Sets up the order with:
     * - Entry: candle close (PlaceNow)
     * - Stop loss: opposite Keltner channel + (2 * ATR)
     * - Take profit: 100 * ATR from entry
     * - No trailing stop
     */
    def iceInVeinsSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy): Order = {
        val lastCandle = state.tradingCandles.last
        val volatility = state.recentVolatilityAnalysis.last
        val keltner = volatility.keltnerChannels
        val entry = lastCandle.close
        val atr = volatility.trueRange.atr
        val takeProfitDistance = 100 * atr
        val stopBuffer = 2 * atr

        val (stop, profit) = orderType match {
            case OrderType.Long =>
                // Stop loss at lower Keltner channel + 2 ATR buffer (below)
                val longStop = keltner.lowerBand - stopBuffer
                // Take profit is 100 ATRs above entry
                val longTP = entry + takeProfitDistance
                (longStop, longTP)
            case OrderType.Short =>
                // Stop loss at upper Keltner channel + 2 ATR buffer (above)
                val shortStop = keltner.upperBand + stopBuffer
                // Take profit is 100 ATRs below entry
                val shortTP = entry - takeProfitDistance
                (shortStop, shortTP)
        }

        Order(
            timestamp = lastCandle.endTime,
            orderType = orderType,
            status = OrderStatus.PlaceNow,
            contractType = ContractType.ES,
            contract = state.contractSymbol.get,
            contracts = 1,
            entryStrategy = entryStrategy,
            exitStrategy = exitStrategy,
            entryPrice = entry,
            stopLoss = stop,
            trailStop = None,
            takeProfit = profit
        )
    }
}
