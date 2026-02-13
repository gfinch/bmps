package bmps.core.services.rules

import bmps.core.models.SystemState
import bmps.core.models.Order
import java.time.Duration
import bmps.core.models.Candle
import bmps.core.models.Direction
import bmps.core.models.OrderType
import bmps.core.models.OrderStatus

trait SafetyRules {

    def isSafeOrder(state: SystemState, order: Order): Boolean = {
        hasReasonableTargetDistance(state, order) &&
        hasGoodStopPlacement(state, order) &&
        hasRealisticRiskReward(state, order) &&
        isMovingWithThreeHourTrend(state, order) &&
        meetsPostLossCriteria(state, order)
    }

    /**
     * Rule 1: Target Validation
     * Rejects trades where take profit is unrealistically far from Keltner channel
     */
    private def hasReasonableTargetDistance(state: SystemState, order: Order): Boolean = {
        if (state.recentVolatilityAnalysis.isEmpty) return true
        
        val volatility = state.recentVolatilityAnalysis.last
        val keltner = volatility.keltnerChannels
        val currentPrice = state.tradingCandles.last.close
        
        val channelWidth = keltner.upperBand - keltner.lowerBand
        val targetDistance = math.abs(order.takeProfit - currentPrice)
        
        // Allow TP up to 1.5x the Keltner channel width away
        // This catches "moon shot" targets while allowing reasonable extensions
        targetDistance <= (channelWidth * 1.5)
    }
    
    /**
     * Rule 2: Refined Stop Placement
     * Enforces stops relative to MA with small buffer zone
     */
    private def hasGoodStopPlacement(state: SystemState, order: Order): Boolean = {
        if (state.recentTrendAnalysis.isEmpty) return true
        
        val trend = state.recentTrendAnalysis.last
        val stop = order.stopLoss
        val longTermMA = trend.longTermMA
        val currentPrice = state.tradingCandles.last.close
        
        // Calculate buffer zone (0.1% of current price)
        val bufferPercent = 0.001
        val buffer = currentPrice * bufferPercent
        
        order.orderType match {
            case OrderType.Long => 
                // For longs: stop should be below MA, but allow small buffer
                val effectiveLimit = longTermMA + buffer
                stop < effectiveLimit
                
            case OrderType.Short => 
                // For shorts: stop should be above MA, but allow small buffer
                val effectiveLimit = longTermMA - buffer
                stop > effectiveLimit
        }
    }
    
    /**
     * Rule 3: Risk/Reward Reality Check
     * Verifies TP target doesn't require exceptional volatility to achieve
     */
    private def hasRealisticRiskReward(state: SystemState, order: Order): Boolean = {
        if (state.recentVolatilityAnalysis.isEmpty) return true
        
        val volatility = state.recentVolatilityAnalysis.last
        val atr = volatility.trueRange.atr
        val currentPrice = state.tradingCandles.last.close
        
        val targetDistance = math.abs(order.takeProfit - currentPrice)
        val targetDistancePercent = (targetDistance / currentPrice) * 100
        
        // Calculate average hourly range over last 3 hours
        val avgHourlyRange = if (state.tradingCandles.length >= 180) {
            val recentCandles = state.tradingCandles.takeRight(180) // Last 3 hours of 1-min candles
            val hourlyRanges = recentCandles.grouped(60).map { hourCandles =>
                val high = hourCandles.map(_.high).max
                val low = hourCandles.map(_.low).min
                high - low
            }.toList
            hourlyRanges.sum / hourlyRanges.length
        } else {
            atr // Fallback to ATR if not enough data
        }
        
        val avgHourlyRangePercent = (avgHourlyRange / currentPrice) * 100
        
        // Reject if TP requires more than 5x the average hourly range
        // This catches targets that need exceptional volatility
        targetDistancePercent <= (avgHourlyRangePercent * 5.0)
    }

    private def isMovingWithThreeHourTrend(state: SystemState, order: Order) = {
        val threeHoursAgo = findCandleHoursAgo(state, 3)
        val twoHoursAgo = findCandleHoursAgo(state, 2)
        val oneHoursAgo = findCandleHoursAgo(state, 1)
        val now = state.tradingCandles.last

        // Check each hourly move
        val move1 = twoHoursAgo.close - threeHoursAgo.close
        val move2 = oneHoursAgo.close - twoHoursAgo.close
        val move3 = now.close - oneHoursAgo.close
        
        val totalMove = now.close - threeHoursAgo.close
        val percentMove = (totalMove / threeHoursAgo.close).abs * 100

        // Only block if ALL 3 hours moved in same direction (very strong trend)
        // AND the total move is significant (at least 0.45%)
        val strongUpTrend = move1 > 0 && move2 > 0 && move3 > 0 && percentMove >= 0.45
        val strongDownTrend = move1 < 0 && move2 < 0 && move3 < 0 && percentMove >= 0.45

        // Block shorts in strong uptrends, longs in strong downtrends
        // Allow everything else
        if (strongUpTrend && order.orderType == OrderType.Short) {
            false
        } else if (strongDownTrend && order.orderType == OrderType.Long) {
            false
        } else {
            true
        }
    }

    private def isMovingWithTwoHourTrend(state: SystemState, order: Order) = {
        val twoHoursAgo = findCandleHoursAgo(state, 2)
        val oneHoursAgo = findCandleHoursAgo(state, 1)
        val now = state.tradingCandles.last

        // Check each hourly move
        val move1 = oneHoursAgo.close - twoHoursAgo.close
        val move2 = now.close - oneHoursAgo.close
        
        val totalMove = now.close - twoHoursAgo.close
        val percentMove = (totalMove / twoHoursAgo.close).abs * 100

        // Only block if BOTH hours moved in same direction (strong trend)
        // AND the total move is significant (at least 0.45%)
        val strongUpTrend = move1 > 0 && move2 > 0 && percentMove >= 0.45
        val strongDownTrend = move1 < 0 && move2 < 0 && percentMove >= 0.45

        // Block shorts in strong uptrends, longs in strong downtrends
        // Allow everything else
        if (strongUpTrend && order.orderType == OrderType.Short) {
            false
        } else if (strongDownTrend && order.orderType == OrderType.Long) {
            false
        } else {
            true
        }
    }

    private def findCandleHoursAgo(state: SystemState, hoursAgo: Int): Candle = {
        val currentCandle = state.tradingCandles.last
        val hoursAgoTimestamp = currentCandle.timestamp - Duration.ofHours(hoursAgo).toMillis()
        state.tradingCandles.find(_.timestamp >= hoursAgoTimestamp).getOrElse(state.tradingCandles.head)
    }

    private def hasStopUnderMA(state: SystemState, order: Order): Boolean = {
        val stop = order.stopLoss
        val longTermMA = state.recentTrendAnalysis.last.longTermMA

        order.orderType match {
            case OrderType.Long => stop < longTermMA
            case OrderType.Short => stop > longTermMA
        }
    }

    /**
     * Rule: Recent Loss Cooldown
     * After a loss, require stronger market conditions before taking next trade
     */
    private def meetsPostLossCriteria(state: SystemState, order: Order): Boolean = {
        if (state.recentOrders.isEmpty || state.recentTrendAnalysis.isEmpty) return true
        
        // Check if the most recent closed order was a loss
        val recentLoss = state.recentOrders
            .filter(o => !o.isActive)
            .sortBy(_.closeTimestamp.get)
            .lastOption
            .exists { lastOrder =>
                lastOrder.status == OrderStatus.Loss
            }
        
        if (!recentLoss) {
            // No recent loss, allow trade
            true
        } else {
            // Recent loss - require stronger conditions
            val trend = state.recentTrendAnalysis.last
            
            // Require ADX > 25 (stronger trend) after a loss
            trend.adx > 25
        }
    }

}
