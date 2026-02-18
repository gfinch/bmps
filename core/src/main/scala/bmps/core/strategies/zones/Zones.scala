package bmps.core.strategies.zones

import bmps.core.models.SystemState
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.services.analysis.VolatilityAnalysis
import java.time.Duration

object ZoneId {
    val NewLow = 0
    val Long = 1
    val MidLow = 2
    val MidHigh = 3
    val Short = 4
    val NewHigh = 5

    def fromId(zoneId: Int): String = {
        zoneId match {
            case NewLow => "NewLow"
            case Long => "Long"
            case MidLow => "MidLow"
            case MidHigh => "MidHigh"
            case Short => "Short"
            case NewHigh => "NewHigh"
            case _ => "Unknown"
        }
    }
}

case class Zones(high: Double, low: Double) {
    val spread = high - low
    val seventyFive = high - (spread * 0.3)
    val twentyFive = low + (spread * 0.3)
    val fifty = low + (spread * 0.5)

    def newHigh(close: Double): Boolean = close > high
    def short(close: Double): Boolean = close > seventyFive && close <= high
    def midHigh(close: Double): Boolean = close > fifty && close <= seventyFive
    def midLow(close: Double): Boolean = close > twentyFive && close <= fifty
    def long(close: Double): Boolean = close > low && close <= twentyFive
    def newLow(close: Double): Boolean = close <= low

    def zoneId(close: Double): Int = {
        if (newLow(close)) ZoneId.NewLow
        else if (long(close)) ZoneId.Long
        else if (midLow(close)) ZoneId.MidLow
        else if (midHigh(close)) ZoneId.MidHigh
        else if (short(close)) ZoneId.Short
        else if (newHigh(close)) ZoneId.NewHigh
        else throw new IllegalStateException("Unexpected state - no zone found")
    }
}

object Zones {
    def fromState(state: SystemState): Zones = {
        val init = state.tradingCandles.init
        val high = init.map(_.high).max
        val low = init.map(_.low).min
        Zones(high, low)
    }

    def isDeathCrossMinutesAgo(state: SystemState, n: Int): Boolean = {
        if (state.recentTrendAnalysis.size < n + 2) return false
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 2)
        trendAnalysis.tail.head.isDeathCross && !trendAnalysis.head.isDeathCross
    }

    def isGoldenCrossMinutesAgo(state: SystemState, n: Int): Boolean = {
        if (state.recentTrendAnalysis.size < n + 2) return false
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 2)
        trendAnalysis.tail.head.isGoldenCross && !trendAnalysis.head.isGoldenCross
    }

    def trendStrengthNMinutesAgo(state: SystemState, n: Int): Double = {
        if (state.recentTrendAnalysis.size < n + 1 || state.recentVolatilityAnalysis.size < n + 1) return 0.0
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 1).head
        val volatilityAnalysis = state.recentVolatilityAnalysis.takeRight(n + 1).head
        calculateTrendStrength(trendAnalysis, volatilityAnalysis)
    }

    def calculateTrendStrength(trend: TrendAnalysis, volatility: VolatilityAnalysis): Double = {
        val maSpread = math.abs(trend.shortTermMA - trend.longTermMA)
        val channelWidth = math.abs(volatility.keltnerChannels.upperBand - volatility.keltnerChannels.lowerBand)
        if (channelWidth == 0) return 0.0
        val rawStrength = maSpread / channelWidth
        val clampedStrength = math.max(0.0, math.min(1.0, rawStrength))
        clampedStrength * 100.0
    }

    def movingForMinutes(state: SystemState, direction: Direction, n: Int): Boolean = {
        if (state.tradingCandles.size < n) false
        else {
            val lastNCandles = state.tradingCandles.takeRight(n)
            val lastWick = if (direction == Direction.Up) lastNCandles.last.upperWick 
                else lastNCandles.last.lowerWick
            lastNCandles.forall(_.direction == direction) &&
                lastNCandles.last.bodyHeight > lastWick
        }
    }

    def lastOrderFromZone(state: SystemState, zoneId: Int): Boolean = {
        val now = state.tradingCandles.last.timestamp
        state.orders.filter(o => inTheLastHour(o, now)).lastOption.exists { order =>
            order.entryStrategy.description.contains(s"ZoneId:$zoneId")
        }
    }

    def inTheLastHour(order: Order, now: Long): Boolean = {
        now - order.timestamp < Duration.ofHours(1).toMillis()
    }
}
