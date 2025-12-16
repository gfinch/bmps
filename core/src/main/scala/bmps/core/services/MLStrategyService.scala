package bmps.core.services

import bmps.core.models.{SystemState, Order, OrderType, OrderStatus, ExtremeType, EntryType}
import bmps.core.brokers.rest.{InferenceBroker, InferenceFeatures}
import bmps.core.utils.TimestampUtils
import java.time.{Instant, ZoneId}

class MLStrategyService(inferenceBroker: InferenceBroker = new InferenceBroker()) {

    def processOneMinuteState(state: SystemState): SystemState = {
        val lastCandle = state.tradingCandles.last
        
        // Skip if we already have an active order or it's near close
        if (state.orders.exists(_.isActive) || TimestampUtils.isNearTradingClose(lastCandle.timestamp)) {
            state
        } else {
            // We need enough history for features
            if (state.tradingCandles.length < 20 || state.recentTrendAnalysis.isEmpty) {
                state
            } else {
                val features = calculateFeatures(state)
                val prediction = inferenceBroker.predict(features)
                
                prediction match {
                    case Some(resp) if resp.action == "BUY" =>
                        val newOrder = createOrder(state, OrderType.Long)
                        state.copy(orders = state.orders :+ newOrder)
                    case Some(resp) if resp.action == "SELL" =>
                        val newOrder = createOrder(state, OrderType.Short)
                        state.copy(orders = state.orders :+ newOrder)
                    case _ => state
                }
            }
        }
    }

    private def createOrder(state: SystemState, orderType: OrderType): Order = {
        val lastCandle = state.tradingCandles.last
        val atr = state.recentVolatilityAnalysis.last.trueRange.atr
        val entryPrice = lastCandle.close
        
        // Target: 2 ATR, Stop: 1 ATR
        // Order model expects 'low' and 'high' which define the risk zone (Entry to Stop Loss)
        
        val (lowPrice, highPrice): (Double, Double) = orderType match {
            case OrderType.Long =>
                // Long: Entry = high, Stop = low
                val stopLoss = entryPrice - (1.0 * atr)
                (stopLoss, entryPrice)
            case OrderType.Short =>
                // Short: Entry = low, Stop = high
                val stopLoss = entryPrice + (1.0 * atr)
                (entryPrice, stopLoss)
        }
        
        Order(
            low = lowPrice.toFloat,
            high = highPrice.toFloat,
            timestamp = lastCandle.timestamp,
            orderType = orderType,
            entryType = EntryType.Trendy(s"ML_Strategy_${orderType}"),
            contract = state.contractSymbol.getOrElse("UNK"),
            status = OrderStatus.PlaceNow,
            profitMultiplier = 2.0f // Target 2 ATR (since risk is 1 ATR)
        )
    }

    private def calculateFeatures(state: SystemState): InferenceFeatures = {
        val lastCandle = state.tradingCandles.last
        val trend = state.recentTrendAnalysis.last
        val momentum = state.recentMomentumAnalysis.last
        val volatility = state.recentVolatilityAnalysis.last
        
        val instant = Instant.ofEpochMilli(lastCandle.timestamp).atZone(ZoneId.of("America/New_York"))
        
        def getSpread(idx: Int): Double = {
            if (idx >= 0 && idx < state.recentTrendAnalysis.size) {
                val ta = state.recentTrendAnalysis(idx)
                math.abs(ta.shortTermMA - ta.longTermMA)
            } else 0.0
        }
        
        def getRsi(idx: Int): Double = {
            if (idx >= 0 && idx < state.recentMomentumAnalysis.size) {
                state.recentMomentumAnalysis(idx).rsi
            } else 50.0
        }

        val currentIdx = state.recentTrendAnalysis.size - 1
        val currentSpread = getSpread(currentIdx)
        val currentRsi = getRsi(currentIdx)
        
        InferenceFeatures(
            rsi = momentum.rsi,
            adx = trend.adx,
            trendStrength = calculateTrendStrength(state, 0),
            maSpread = math.abs(trend.shortTermMA - trend.longTermMA),
            atr = volatility.trueRange.atr,
            volume = lastCandle.volume.toDouble,
            hour = instant.getHour,
            minute = instant.getMinute,
            rule1 = applyRule(1, state),
            rule2 = applyRule(2, state),
            rule3 = applyRule(3, state),
            rule4 = applyRule(4, state),
            rule5 = applyRule(5, state),
            rule6 = applyRule(6, state),
            rule7 = applyRule(7, state),
            rule8 = applyRule(8, state),
            rule9 = applyRule(9, state),
            rule10 = applyRule(10, state),
            rule11 = applyRule(11, state),
            rule12 = applyRule(12, state),
            
            spreadChange1 = currentSpread - getSpread(currentIdx - 1),
            spreadChange2 = currentSpread - getSpread(currentIdx - 2),
            spreadChange3 = currentSpread - getSpread(currentIdx - 3),
            spreadChange5 = currentSpread - getSpread(currentIdx - 5),
            spreadChange10 = currentSpread - getSpread(currentIdx - 10),
            
            rsiChange1 = currentRsi - getRsi(currentIdx - 1),
            rsiChange2 = currentRsi - getRsi(currentIdx - 2),
            rsiChange3 = currentRsi - getRsi(currentIdx - 3),
            rsiChange5 = currentRsi - getRsi(currentIdx - 5),
            rsiChange10 = currentRsi - getRsi(currentIdx - 10)
        )
    }

    // --- Rule Logic (Copied & Simplified from TechnicalAnalysisOrderService) ---

    private def applyRule(id: Int, state: SystemState): Boolean = {
        id match {
            case 1 => isOverbought(state) || isOversold(state)
            case 2 => !(isOverbought(state) || isOversold(state))
            case 3 => nearingSummit(state, 1.0) || nearingFloor(state, 1.0)
            case 4 => !(nearingSummit(state, 1.0) || nearingFloor(state, 1.0))
            case 5 => false 
            case 6 => wasDeathCrossNMinutesAgo(state, 2) || wasGoldenCrossNMinutesAgo(state, 2)
            case 7 => wasDeathCrossNMinutesAgo(state, 3) || wasGoldenCrossNMinutesAgo(state, 3)
            case 8 => wasDeathCrossNMinutesAgo(state, 5) || wasGoldenCrossNMinutesAgo(state, 5)
            case 9 => wasDeathCrossNMinutesAgo(state, 7) || wasGoldenCrossNMinutesAgo(state, 7)
            case 10 => wasDeathCrossNMinutesAgo(state, 10) || wasGoldenCrossNMinutesAgo(state, 10)
            case 11 => hasIncreasingADX(state, 3)
            case 12 => !hasIncreasingADX(state, 3)
            case _ => false
        }
    }

    private def isOversold(state: SystemState): Boolean = {
        val momentumAnalysis = state.recentMomentumAnalysis.takeRight(3)
        momentumAnalysis.exists(_.rsiOversold)
    }

    private def isOverbought(state: SystemState): Boolean = {
        val momentumAnalysis = state.recentMomentumAnalysis.takeRight(3)
        momentumAnalysis.exists(_.rsiOverbought)
    }

    private def peakRed(state: SystemState): Double = {
        val highestRed = state.daytimeExtremes.filter(_.extremeType == ExtremeType.High).map(_.level.toDouble).maxOption.getOrElse(Double.MinValue)
        val highestToday = state.tradingCandles.map(_.high.toDouble).maxOption.getOrElse(Double.MinValue)
        math.max(highestRed, highestToday)
    }

    private def lowGreen(state: SystemState): Double = {
        val lowestGreen = state.daytimeExtremes.filter(_.extremeType == ExtremeType.Low).map(_.level.toDouble).minOption.getOrElse(Double.MaxValue)
        val lowestToday = state.tradingCandles.map(_.low.toDouble).minOption.getOrElse(Double.MaxValue)
        math.min(lowestGreen, lowestToday)
    }

    private def atrs(state: SystemState, quantity: Double): Double = {
        state.recentVolatilityAnalysis.last.trueRange.atr * quantity
    }

    private def nearingSummit(state: SystemState, useAtrs: Double): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = atrs(state, useAtrs) * 2 
        val peak = peakRed(state)
        // Avoid crash if peak is invalid (though maxOption handles empty list, max of MinValue is MinValue)
        if (peak == Double.MinValue) false else
        lastClose < peak && (lastClose + someAtrs) > peak
    }
    
    private def nearingFloor(state: SystemState, useAtrs: Double): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = atrs(state, useAtrs) * 2 
        val peakGreen = lowGreen(state)
        if (peakGreen == Double.MaxValue) false else
        lastClose > peakGreen && (lastClose - someAtrs) < peakGreen
    }

    private def wasDeathCrossNMinutesAgo(state: SystemState, n: Int): Boolean = {
        if (state.recentTrendAnalysis.size < n + 1) return false
        val lastNPlusOneMinutesAgo = state.recentTrendAnalysis.takeRight(n + 1)
        val nPlusOneMinutesAgo = lastNPlusOneMinutesAgo.head
        nPlusOneMinutesAgo.isDeathCross == false &&
            lastNPlusOneMinutesAgo.tail.forall(_.isDeathCross == true)
    }

    private def wasGoldenCrossNMinutesAgo(state: SystemState, n: Int): Boolean = {
        if (state.recentTrendAnalysis.size < n + 1) return false
        val lastNPlusOneMinutesAgo = state.recentTrendAnalysis.takeRight(n + 1)
        val nPlusOneMinutesAgo = lastNPlusOneMinutesAgo.head
        nPlusOneMinutesAgo.isGoldenCross == false &&
            lastNPlusOneMinutesAgo.tail.forall(_.isGoldenCross == true)
    }

    private def hasIncreasingADX(state: SystemState, n: Int): Boolean = {
        if (state.recentTrendAnalysis.size < n) return false
        val lastNTrend = state.recentTrendAnalysis.takeRight(n)
        val point1 = lastNTrend.head.adx
        val point2 = lastNTrend.last.adx
        slope(0, point1, n, point2) > 0
    }

    private def slope(t1: Long, y1: Double, t2: Long, y2: Double): Double = {
        (y2 - y1) / (t2 - t1).toDouble
    }

    private def calculateTrendStrength(state: SystemState, minutesAgo: Int = 0): Double = {
        require(minutesAgo >= 0, "minutesAgo must be non-negative")

        val requiredSize = minutesAgo + 1

        if (state.recentTrendAnalysis.length < requiredSize || 
            state.recentVolatilityAnalysis.length < requiredSize) {
            return 0.0
        }

        val trendAnalysis = if (minutesAgo == 0) {
            state.recentTrendAnalysis.last
        } else {
            state.recentTrendAnalysis.takeRight(minutesAgo + 1).head
        }

        val volatilityAnalysis = if (minutesAgo == 0) {
            state.recentVolatilityAnalysis.last
        } else {
            state.recentVolatilityAnalysis.takeRight(minutesAgo + 1).head
        }

        val keltnerChannels = volatilityAnalysis.keltnerChannels
        val maSpread = math.abs(trendAnalysis.shortTermMA - trendAnalysis.longTermMA)
        val channelWidth = math.abs(keltnerChannels.upperBand - keltnerChannels.lowerBand) * 0.5

        if (channelWidth == 0.0) {
            return 0.0
        }

        val rawStrength = maSpread / channelWidth
        math.max(0.0, math.min(1.0, rawStrength)) * 100
    }
}
