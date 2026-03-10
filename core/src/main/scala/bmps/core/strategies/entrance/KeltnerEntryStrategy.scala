package bmps.core.strategies.entrance

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.EntryStrategy
import bmps.core.strategies.exit.GoldDeathCrossExitStrategy
import bmps.core.strategies.zones.Zones
import bmps.core.strategies.zones.ZoneId
import bmps.core.strategies.exit.KeltnerExitStrategy
import bmps.core.strategies.exit.TrailingKeltnerExitStrategy
import bmps.core.utils.TimestampUtils

/**
 * KeltnerEntryStrategy detects when a full one-minute candle is entirely outside the Keltner Channel.
 * 
 * Entry conditions:
 * - Long: Entire candle (both high and low) is above the upper Keltner Channel
 * - Short: Entire candle (both high and low) is below the lower Keltner Channel
 * - ADX > 25 (strong trend required to avoid choppy markets)
 * - ADX is increasing (trend strengthening, not leveling off or declining)
 * - Trend Strength > 10 (MA spread is meaningful)
 * - DI Spread >= 5 (directional conviction - |plusDI - minusDI| >= 5)
 * - ATR NOT Decreasing (volatility environment supports trend development)
 * - ATR >= 3.5 (sufficient volatility for trend to develop - avoids low-vol chop)
 * - NOT in first 15 minutes of trading (9:30-9:45 ET) - historically unprofitable window
 * 
 * This is a PlaceNow (market) order with:
 * - Entry price: candle close price
 * - Stop loss: opposite Keltner channel line
 * - Take profit: same distance in the other direction (1:1 risk/reward)
 */
trait KeltnerEntryStrategy {
    // private final val exitStrategy = new GoldDeathCrossExitStrategy()
    // private final val exitStrategy = new KeltnerExitStrategy()
    private final val exitStrategy = new TrailingKeltnerExitStrategy()

    /**
     * Main trigger method - checks if a full candle is outside the Keltner Channel.
     * Returns Some((OrderType, EntryStrategy, setupFn)) if triggered, None otherwise.
     */
    def isKeltnerTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        // Require volatility analysis, trend analysis (at least 2 for ADX comparison), and candles
        if (state.recentVolatilityAnalysis.isEmpty) return None
        if (state.recentTrendAnalysis.size < 2) return None
        if (state.tradingCandles.isEmpty) return None
        
        val lastCandle = state.tradingCandles.last
        
        // Filter 0: Skip first 15 minutes of trading (9:30-9:45 ET)
        if (TimestampUtils.isInFirstFifteenMinutes(lastCandle.timestamp)) return None

        val currentVol = state.recentVolatilityAnalysis.last
        val currentTrend = state.recentTrendAnalysis.last
        val priorTrend = state.recentTrendAnalysis.init.last
        // lastCandle already assigned above for time check
        val keltner = currentVol.keltnerChannels

        // Filter 1: DI Spread >= 5 (directional conviction required)
        val diSpread = math.abs(currentTrend.plusDI - currentTrend.minusDI)
        val hasDiSpread = diSpread >= 5
        
        // Filter 2: ATR NOT Decreasing (volatility should support trend development)
        val hasIncreasingATR = currentVol.trueRange.atrTrend != "Decreasing"
        
        // Filter 3: ATR >= 3.5 (sufficient volatility for trend to develop)
        val hasHighEnoughATR = currentVol.trueRange.atr >= 3.5

        val hasGoodMetrics = hasDiSpread && hasIncreasingATR && hasHighEnoughATR

        if (!hasGoodMetrics) return None

        val zones = Zones.fromState(state, useMinutes = Some(140))
        // val (longZone, shortZone) = zones.zoneId(lastCandle.close) match {
        //     case ZoneId.NewLow => (true, false) //Don't ride with new lows?
        //     case ZoneId.Long => (true, false)
        //     case ZoneId.MidLow => (true, true)
        //     case ZoneId.MidHigh => (true, true)
        //     case ZoneId.Short => (false, true)
        //     case ZoneId.NewHigh => (true, true) //Ride with new highs?
        // }

        val (longZone, shortZone) = (true, true)

        // Check if entire candle is outside Keltner Channel
        // Long: open and close are above the upper band
        val longSignal = (longZone || hasGoodMetrics) && lastCandle.open > keltner.upperBand && lastCandle.isBullish
        
        // Short: open and close are below the lower band
        val shortSignal = (shortZone || hasGoodMetrics) && lastCandle.open < keltner.lowerBand && lastCandle.isBearish

        if (longSignal) {
            val entryStrategy = EntryStrategy(s"KeltnerEntryStrategy")
            Some((OrderType.Long, entryStrategy, keltnerSetup))
        } else if (shortSignal) {
            val entryStrategy = EntryStrategy(s"KeltnerEntryStrategy")
            Some((OrderType.Short, entryStrategy, keltnerSetup))
        } else {
            None
        }
    }

    /**
     * Sets up the order with Keltner channel-based stops and 1:1 risk/reward targets.
     * 
     * For long:
     * - Entry: candle close
     * - Stop loss: lower Keltner channel
     * - Take profit: entry + (entry - stopLoss) for 1:1 R:R
     * 
     * For short:
     * - Entry: candle close
     * - Stop loss: upper Keltner channel
     * - Take profit: entry - (stopLoss - entry) for 1:1 R:R
     */
    def keltnerSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy): Order = {
        val lastCandle = state.tradingCandles.last
        val volatility = state.recentVolatilityAnalysis.last
        val keltner = volatility.keltnerChannels
        val entry = lastCandle.close
        val atr = volatility.trueRange.atr
        val atrs = 100 * atr

        val (stop, profit) = orderType match {
            case OrderType.Long =>
                // Stop loss at lower Keltner channel
                val longStop = keltner.lowerBand
                // Risk is distance from entry to stop
                val risk = entry - longStop
                // Take profit is 10 ATRs
                val longTP = entry + atrs
                (longStop, longTP)
            case OrderType.Short =>
                // Stop loss at upper Keltner channel
                val shortStop = keltner.upperBand
                // Risk is distance from stop to entry
                val risk = shortStop - entry
                // Take profit is 10 atrs
                val shortTP = entry - atrs
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
