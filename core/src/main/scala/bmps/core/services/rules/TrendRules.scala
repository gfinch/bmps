package bmps.core.services.rules

import bmps.core.models.SystemState
import bmps.core.models.Direction
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.services.analysis.VolatilityAnalysis

trait TrendRules {
    def hasEnoughTrendAnalyses(state: SystemState, threshold: Int = 5): Boolean = {
        state.recentTrendAnalysis.size > threshold
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

    def trendStrengthNMinutesAgo(state: SystemState, n: Int): Double = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 1).head
        val volatilityAnalysis = state.recentVolatilityAnalysis.takeRight(n + 1).head
        calculateTrendStrength(trendAnalysis, volatilityAnalysis)
    }

    private def calculateTrendStrength(trend: TrendAnalysis, 
                                       volatility: VolatilityAnalysis): Double = {
        val maSpread = math.abs(trend.shortTermMA - trend.longTermMA)
        val channelWidth = math.abs(volatility.keltnerChannels.upperBand - volatility.keltnerChannels.lowerBand)
        val rawStrength = maSpread / channelWidth
        val clampedStrength = math.max(0.0, math.min(1.0, rawStrength))
        clampedStrength * 100.0 // Scale to 0-100
    }
}
