package bmps.core.services.rules

import bmps.core.models.SystemState

trait TechnicalAnalysisRules extends CrossingRules with TrendRules {
    def hasSpread(state: SystemState, minutesAgo: Int, threshold: Int): Boolean = {
        if (minutesAgo > 2 && (isGoldenCrossMinutesAgo(state, minutesAgo) || isDeathCrossMinutesAgo(state, minutesAgo))) {
            buildSpreads(state, minutesAgo).map { case (firstSpread, lastSpread) => 
                val spreadChange = lastSpread - firstSpread
                spreadChange > threshold
            }.getOrElse(false)
        } else false
    }

    private def buildSpreads(state: SystemState, ago: Int): Option[(Double, Double)] = {
        val trendPoints = state.recentTrendAnalysis.takeRight(ago + 1)
        if (trendPoints.size > 2) {
            val firstSpread = trendStrengthNMinutesAgo(state, ago)
            val lastSpread = trendStrengthNMinutesAgo(state, 0)
            Some(firstSpread, lastSpread)
        } else None
    }

    def isIntersecting(state: SystemState, minutesAgo: Int, within: Int): Boolean = {
        buildSpreads(state, minutesAgo).map { case (firstSpread, lastSpread) =>
            val spreadSlope = slope(0, firstSpread, minutesAgo, lastSpread)
            val expected = futurePoint(spreadSlope, 0, lastSpread, within.toLong)
            if (isGoldenCrossMinutesAgo(state, minutesAgo)) {
                val recentIRsi = state.recentMomentumAnalysis.takeRight(minutesAgo + 1).map(_.iRsi)
                val rsiSlope = slope(0, recentIRsi.head, minutesAgo, recentIRsi.last)
                val rsiExpected = futurePoint(rsiSlope, 0, recentIRsi.last, within.toLong)
                rsiExpected < expected
            } else if (isDeathCrossMinutesAgo(state, minutesAgo)) {
                val recentRsi = state.recentMomentumAnalysis.takeRight(minutesAgo + 1).map(_.rsi)
                val rsiSlope = slope(0, recentRsi.head, minutesAgo, recentRsi.last)
                val rsiExpected = futurePoint(rsiSlope, 0, recentRsi.last, within.toLong)
                rsiExpected < expected
            } else false
        }.getOrElse(false)
    }

    private def slope(t1: Long, y1: Double, t2: Long, y2: Double): Double = {
        (y2 - y1) / (t2 - t1).toDouble
    }

    private def futurePoint(slope: Double, t1: Long, y1: Double, t3: Long): Double = {
        y1 + slope * (t3 - t1)
    }

    def hasIncreasingADX(state: SystemState, n: Int): Boolean = {
        val lastNTrend = state.recentTrendAnalysis.takeRight(n)
        val point1 = lastNTrend.head.adx
        val point2 = lastNTrend.last.adx
        slope(0, point1, n, point2) > 0
    }
}
