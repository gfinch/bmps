package bmps.core.services.rules

import bmps.core.models.{SystemState, Candle, OrderType}
import bmps.core.services.analysis._

/**
 * Multi-timeframe confluence checking
 * Aggregates multiple timeframes to ensure alignment before entry
 */
trait MultiTimeframeRules {
    
    /**
     * Aggregate candles into larger timeframes
     * @param candles 1-minute candles
     * @param minutes Number of minutes per aggregated candle (e.g., 5, 15)
     * @return List of aggregated candles
     */
    def aggregateCandles(candles: List[Candle], minutes: Int): List[Candle] = {
        require(minutes > 0, "Minutes must be positive")
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        val millisPerPeriod = minutes * 60 * 1000L
        
        candles.groupBy { candle =>
            // Group by time period
            candle.timestamp / millisPerPeriod
        }.toList.sortBy(_._1).map { case (periodKey, periodCandles) =>
            // Create aggregated candle for this period
            val sorted = periodCandles.sortBy(_.timestamp)
            Candle(
                open = sorted.head.open,
                high = sorted.map(_.high).max,
                low = sorted.map(_.low).min,
                close = sorted.last.close,
                volume = sorted.map(_.volume).sum,
                timestamp = sorted.head.timestamp,
                duration = sorted.head.duration,
                createdAt = sorted.head.createdAt
            )
        }
    }
    
    /**
     * Check if trend aligns across multiple timeframes
     * @param candles 1-minute candles (at least 60)
     * @param trendService Trend analysis service
     * @param orderType Long or Short
     * @return true if all timeframes agree on direction
     */
    def checkMultiTimeframeTrendAlignment(
        candles: List[Candle],
        trendService: Trend,
        orderType: OrderType
    ): Boolean = {
        if (candles.size < 60) return false
        
        // Take last 60 candles for analysis
        val recent60 = candles.takeRight(60)
        
        // Analyze 1m, 5m, 15m timeframes
        val candles1m = recent60.takeRight(20)
        val candles5m = aggregateCandles(recent60, 5).takeRight(12)
        val candles15m = aggregateCandles(recent60, 15).takeRight(4)
        
        if (candles1m.size < 20 || candles5m.size < 12 || candles15m.size < 4) return false
        
        // Get trend analysis for each timeframe
        val trend1m = trendService.doTrendAnalysis(candles1m)
        val trend5m = trendService.doTrendAnalysis(candles5m)
        val trend15m = trendService.doTrendAnalysis(candles15m)
        
        // Check alignment
        orderType match {
            case OrderType.Long =>
                trend1m.isUptrend && trend5m.isUptrend && trend15m.isUptrend &&
                trend1m.isGoldenCross && trend5m.isGoldenCross
            case OrderType.Short =>
                trend1m.isDowntrend && trend5m.isDowntrend && trend15m.isDowntrend &&
                trend1m.isDeathCross && trend5m.isDeathCross
        }
    }
    
    /**
     * Check if momentum aligns across timeframes
     */
    def checkMultiTimeframeMomentumAlignment(
        candles: List[Candle],
        momentumService: Momentum,
        orderType: OrderType
    ): Boolean = {
        if (candles.size < 60) return false
        
        val recent60 = candles.takeRight(60)
        
        val candles1m = recent60.takeRight(20)
        val candles5m = aggregateCandles(recent60, 5).takeRight(12)
        
        if (candles1m.size < 14 || candles5m.size < 14) return false
        
        val momentum1m = momentumService.doMomentumAnalysis(candles1m)
        val momentum5m = momentumService.doMomentumAnalysis(candles5m)
        
        // Check RSI alignment
        orderType match {
            case OrderType.Long =>
                momentum1m.rsi > 30.0 && momentum1m.rsi < 70.0 &&
                momentum5m.rsi > 35.0 && momentum5m.rsi < 65.0 &&
                !momentum1m.rsiOverbought && !momentum5m.rsiOverbought
            case OrderType.Short =>
                momentum1m.rsi > 30.0 && momentum1m.rsi < 70.0 &&
                momentum5m.rsi > 35.0 && momentum5m.rsi < 65.0 &&
                !momentum1m.rsiOversold && !momentum5m.rsiOversold
        }
    }
    
    /**
     * Full multi-timeframe confluence check
     * Returns confidence score 0-1
     */
    def calculateMultiTimeframeConfidence(
        state: SystemState,
        trendService: Trend,
        momentumService: Momentum,
        orderType: OrderType
    ): Double = {
        if (state.tradingCandles.size < 60) return 0.0
        
        var score = 0.0
        var maxScore = 0.0
        
        // Trend alignment (50% weight)
        val trendAligned = checkMultiTimeframeTrendAlignment(state.tradingCandles, trendService, orderType)
        if (trendAligned) score += 0.5
        maxScore += 0.5
        
        // Momentum alignment (30% weight)
        val momentumAligned = checkMultiTimeframeMomentumAlignment(state.tradingCandles, momentumService, orderType)
        if (momentumAligned) score += 0.3
        maxScore += 0.3
        
        // Current timeframe strong trend (20% weight)
        if (state.recentTrendAnalysis.nonEmpty) {
            val currentTrend = state.recentTrendAnalysis.last
            if (currentTrend.isStrongTrend) score += 0.2
            maxScore += 0.2
        }
        
        if (maxScore > 0) score / maxScore else 0.0
    }
}
