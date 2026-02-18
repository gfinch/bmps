package bmps.core.strategies.entrance

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.EntryStrategy
import bmps.core.strategies.exit.SimpleExitStrategy
import bmps.core.services.analysis.VolatilityAnalysis
import bmps.core.strategies.zones.{Zones, ZoneId}

/**
 * SqueezeEntryStrategy detects breakouts from Bollinger Band / Keltner Channel squeezes.
 * 
 * A "squeeze" occurs when Bollinger Bands contract inside Keltner Channels during low
 * volatility consolidation periods. When price breaks out through both indicators
 * simultaneously, it signals a high-probability directional move.
 * 
 * Entry conditions:
 * - Long: Price breaks above BOTH upper Bollinger Band AND upper Keltner Channel
 * - Short: Price breaks below BOTH lower Bollinger Band AND lower Keltner Channel
 * 
 * The squeeze pattern is detected when both Bollinger Bands are inside the Keltner Channels,
 * indicating compression that often precedes explosive moves.
 */
trait SqueezeEntryStrategy {
    private final val exitStrategy = new SimpleExitStrategy()

    // Configurable parameters
    
    /** ATR multiplier for take profit level */
    private val takeProfitATRMultiple = 3.0
    
    /** ATR multiplier for stop loss distance from Keltner channel */
    private val stopLossATRMultiple = 0.5
    
    /** Number of periods to look back for squeeze detection */
    private val squeezeLookbackPeriods = 3

    /**
     * Main trigger method - checks if squeeze breakout conditions are met.
     * Returns Some((OrderType, EntryStrategy, setupFn)) if triggered, None otherwise.
     */
    def isSqueezeTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        // Require sufficient history
        if (state.recentVolatilityAnalysis.size < squeezeLookbackPeriods + 1) return None
        if (state.tradingCandles.isEmpty) return None

        val currentVol = state.recentVolatilityAnalysis.last
        val recentVols = state.recentVolatilityAnalysis.takeRight(squeezeLookbackPeriods + 1)
        val currentPrice = state.tradingCandles.last.close

        // Check if we were recently in a squeeze (BB inside KC)
        val wasInSqueeze = recentVols.init.exists(vol => isInSqueeze(vol))
        
        if (!wasInSqueeze) return None

        val bollinger = currentVol.bollingerBands
        val keltner = currentVol.keltnerChannels

        // Check for breakout through BOTH indicators
        val longBreakout = currentPrice > bollinger.upperBand && currentPrice > keltner.upperBand
        val shortBreakout = currentPrice < bollinger.lowerBand && currentPrice < keltner.lowerBand

        // Get zone to filter out shorts near the low of day
        val zones = Zones.fromState(state)
        val currentZone = zones.zoneId(currentPrice)
        val nearLowOfDay = currentZone == ZoneId.NewLow || currentZone == ZoneId.Long

        if (longBreakout) {
            val entryStrategy = EntryStrategy(s"SqueezeEntryStrategy:Long:Breakout")
            Some((OrderType.Long, entryStrategy, squeezeSetup))
        } else if (shortBreakout && !nearLowOfDay) {
            val entryStrategy = EntryStrategy(s"SqueezeEntryStrategy:Short:Breakout")
            Some((OrderType.Short, entryStrategy, squeezeSetup))
        } else {
            None
        }
    }

    /**
     * Checks if volatility analysis indicates a squeeze condition.
     * Squeeze = Bollinger Bands are inside Keltner Channels.
     */
    private def isInSqueeze(vol: VolatilityAnalysis): Boolean = {
        val bollinger = vol.bollingerBands
        val keltner = vol.keltnerChannels
        
        // Upper BB must be below upper KC AND lower BB must be above lower KC
        bollinger.upperBand < keltner.upperBand && bollinger.lowerBand > keltner.lowerBand
    }

    /**
     * Sets up the order with ATR-based stops and targets.
     * 
     * For long:
     * - Take profit: n ATRs above entry
     * - Stop loss: m ATRs below lower Keltner channel (trailing)
     * 
     * For short:
     * - Take profit: n ATRs below entry
     * - Stop loss: m ATRs above upper Keltner channel (trailing)
     */
    def squeezeSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy): Order = {
        val lastCandle = state.tradingCandles.last
        val volatility = state.recentVolatilityAnalysis.last
        val atr = volatility.trueRange.atr
        val keltner = volatility.keltnerChannels
        val entry = lastCandle.close

        val (stop, profit, trailDistance) = orderType match {
            case OrderType.Long =>
                val longTP = entry + (atr * takeProfitATRMultiple)
                // Stop loss is m ATRs below the lower Keltner channel
                val longStop = keltner.lowerBand - (atr * stopLossATRMultiple)
                // Trailing stop distance is the same as initial stop distance
                val trailDist = entry - longStop
                (longStop, longTP, trailDist)
            case OrderType.Short =>
                val shortTP = entry - (atr * takeProfitATRMultiple)
                // Stop loss is m ATRs above the upper Keltner channel
                val shortStop = keltner.upperBand + (atr * stopLossATRMultiple)
                // Trailing stop distance is the same as initial stop distance
                val trailDist = shortStop - entry
                (shortStop, shortTP, trailDist)
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
            trailStop = Some(trailDistance),
            takeProfit = profit
        )
    }
}
