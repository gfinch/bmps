package bmps.core.strategies.entrance

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.EntryStrategy
import bmps.core.models.ExitStrategy
import bmps.core.strategies.exit.SimpleExitStrategy
import java.time.Duration

trait ConsolidationFadeEntryStrategy {
    private final val entryStrategy = EntryStrategy("ConsolidationFadeEntryStrategy")
    private final val exitStrategy = new SimpleExitStrategy()

    // Configurable parameters
    private val consolidationMinutes = 10
    private val rangeWindowMinutes = 30
    private val adxThreshold = 20.0
    private val entryOffsetPoints = 1.0
    private val stopBufferPoints = 1.0
    private val riskRewardRatio = 4.0

    def isConsolidationFadeTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        if (!isInConsolidation(state)) {
            return None
        }

        val (support, resistance) = findSupportResistance(state)
        val range = resistance - support
        if (range < 2.0) {
            return None
        }

        val midpoint = (support + resistance) / 2.0
        val lastCandle = state.tradingCandles.last

        if (lastCandle.close > midpoint) {
            Some((OrderType.Long, entryStrategy, consolidationFadeSetup))
        } else {
            Some((OrderType.Short, entryStrategy, consolidationFadeSetup))
        }
    }

    def consolidationFadeSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy): Order = {
        val (support, resistance) = findSupportResistance(state)
        val midpoint = (support + resistance) / 2.0
        val lastCandle = state.tradingCandles.last

        val (entry, stop, profit) = orderType match {
            case OrderType.Long =>
                val longEntry = midpoint - entryOffsetPoints
                val longStop = support - stopBufferPoints
                val longRisk = longEntry - longStop
                val longTP = longEntry + (longRisk / riskRewardRatio)
                (longEntry, longStop, longTP)
            case OrderType.Short =>
                val shortEntry = midpoint + entryOffsetPoints
                val shortStop = resistance + stopBufferPoints
                val shortRisk = shortStop - shortEntry
                val shortTP = shortEntry - (shortRisk / riskRewardRatio)
                (shortEntry, shortStop, shortTP)
        }

        Order(
            timestamp = lastCandle.endTime,
            orderType = orderType,
            status = OrderStatus.Planned,
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

    private def isInConsolidation(state: SystemState): Boolean = {
        if (state.recentTrendAnalysis.isEmpty || state.recentVolatilityAnalysis.isEmpty) {
            return false
        }

        val trend = state.recentTrendAnalysis.last
        val volatility = state.recentVolatilityAnalysis.last
        val currentPrice = state.tradingCandles.last.close

        val weakTrend = trend.adx < adxThreshold
        val withinChannels = volatility.keltnerChannels.isInsideChannel(currentPrice)

        val stableConsolidation = {
            val recentCandles = state.tradingCandles.takeRight(10)
            if (recentCandles.length < 10) {
                true
            } else {
                val firstFive = recentCandles.take(5)
                val lastFive = recentCandles.takeRight(5)
                val firstAvgRange = firstFive.map(c => c.high - c.low).sum / 5
                val lastAvgRange = lastFive.map(c => c.high - c.low).sum / 5
                lastAvgRange < (firstAvgRange * 1.5)
            }
        }

        val hasConsolidatedLongEnough = {
            val consolidationMs = Duration.ofMinutes(consolidationMinutes).toMillis()
            val startTime = state.tradingCandles.last.timestamp - consolidationMs
            val recentCandles = state.tradingCandles.filter(_.timestamp >= startTime)
            if (recentCandles.length < consolidationMinutes) {
                false
            } else {
                val high = recentCandles.map(_.high).max
                val low = recentCandles.map(_.low).min
                val rangePercent = ((high - low) / low) * 100
                rangePercent < 1.0
            }
        }

        weakTrend && withinChannels && stableConsolidation && hasConsolidatedLongEnough
    }

    private def findSupportResistance(state: SystemState): (Double, Double) = {
        val windowMs = Duration.ofMinutes(rangeWindowMinutes).toMillis()
        val startTime = state.tradingCandles.last.timestamp - windowMs
        val recentCandles = state.tradingCandles.filter(_.timestamp >= startTime)

        if (recentCandles.isEmpty) {
            val last = state.tradingCandles.last
            (last.low, last.high)
        } else {
            val support = recentCandles.map(_.low).min
            val resistance = recentCandles.map(_.high).max
            (support, resistance)
        }
    }
}
