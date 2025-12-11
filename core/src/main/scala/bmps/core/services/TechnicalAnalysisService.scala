package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.services.analysis.Trend
import bmps.core.services.analysis.Momentum
import bmps.core.services.analysis.Volume
import bmps.core.services.analysis.Volatility
import bmps.core.models.OrderType
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus
import bmps.core.utils.MarketCalendar
import bmps.core.utils.TimestampUtils
import bmps.core.models.Direction
import bmps.core.api.storage.EventStore.create
import bmps.core.models.ExtremeType
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.brokers.AccountBroker
import bmps.core.models.OrderStatus.Profit
import bmps.core.models.OrderStatus.Loss

class TechnicalAnalysisService(trend: Trend = new Trend(),
                               momentum: Momentum = new Momentum(),
                               volume: Volume = new Volume(),
                               volatility: Volatility = new Volatility(),
                               useAtrs: Double = 2.0) {

    def processOneSecondState(state: SystemState): SystemState = {
        val lastCandle = state.recentOneSecondCandles.last
        val secondsInTimestamp = (lastCandle.timestamp / 1000) % 60 // Extract seconds from Unix timestamp
        
        if (secondsInTimestamp % 15 == 0) {
            // Process the candle at 0, 15, 30, or 45 seconds
            // Volume analysis works best with 1-second candles for detailed volume profile
            val volumeAnalysis = volume.doVolumeAnalysis(
                candles = state.recentOneSecondCandles,
                existingAnalysis = state.recentVolumeAnalysis.lastOption,
                newCandleCount = 15 // 15 seconds worth of new data
            )
            
            // Update system state with new volume analysis
            state.copy(
                recentVolumeAnalysis = state.recentVolumeAnalysis.takeRight(19) :+ volumeAnalysis // Keep last 20 analyses
            )
        } else {
            state
        }
    }

    def processOneMinuteState(state: SystemState): SystemState = {
        val lastCandle = state.tradingCandles.last

        // Perform trend analysis on 1-minute candles
        val trendAnalysis = trend.doTrendAnalysis(state.tradingCandles)
        
        // Perform momentum analysis on 1-minute candles
        val momentumAnalysis = momentum.doMomentumAnalysis(state.tradingCandles)
        
        // Perform volatility analysis on 1-minute candles
        val volatilityAnalysis = volatility.doVolatilityAnalysis(state.tradingCandles)
        
        // Update system state with all new analyses
        val recentTrendAnalysis = state.recentTrendAnalysis.takeRight(19) :+ trendAnalysis
        val recentMomentumAnalysis = state.recentMomentumAnalysis.takeRight(19) :+ momentumAnalysis
        val recentVolatilityAnalysis = state.recentVolatilityAnalysis.takeRight(19) :+ volatilityAnalysis

        val stateWithAnalysis = state.copy(
            recentTrendAnalysis = recentTrendAnalysis,
            recentMomentumAnalysis = recentMomentumAnalysis,
            recentVolatilityAnalysis = recentVolatilityAnalysis
        )
        
        if (stateWithAnalysis.orders.exists(_.isActive) || TimestampUtils.isNearTradingClose(lastCandle.timestamp)) stateWithAnalysis
        else buildOrders(stateWithAnalysis)
        stateWithAnalysis
    }

    private def buildOrders(state: SystemState): SystemState = {
        if (state.recentMomentumAnalysis.length > 3 && !state.orders.exists(_.isActive)) {
            createOrder(state).map { order => 
                state.copy(orders = state.orders :+ order)
            }.getOrElse(state)
        } else state
    }

    private def createOrder(state: SystemState): Option[Order] = {
        createMomentumOrder(state)
        // .orElse(createTrendOrder(state))
        // createTrendOrder((state))
        // .orElse(createOverOrder(state))
    }

    private def createTrendOrder(state: SystemState): Option[Order] = {
        val lastCandle = state.tradingCandles.last
        val entryPrice = state.recentTrendAnalysis.last.shortTermMA.toFloat
        val someAtrs = atrs(state, useAtrs)
        ruleIsGoldenOrDeathCross(state) match {
            case Some(OrderType.Long) =>
                val low = entryPrice - someAtrs
                Some(Order(low.toFloat, entryPrice, lastCandle.timestamp, OrderType.Long, 
                      EntryType.TrendOrderBlock, state.contractSymbol.get))
            case Some(OrderType.Short) => 
                val high = entryPrice + someAtrs
                Some(Order(entryPrice, high.toFloat, lastCandle.timestamp, OrderType.Short, 
                      EntryType.TrendOrderBlock, state.contractSymbol.get))
            case None => None
        }
    }

    private def createOverOrder(state: SystemState): Option[Order] = {
        val lastCandle = state.tradingCandles.last
        val someAtrs = atrs(state, 2.0)
        ruleIsMomentumOverboughtOrOversold(state) match {
            case Some(OrderType.Long) =>
                val entryPrice = lastCandle.low
                val low = entryPrice - someAtrs
                Some(Order(low.toFloat, entryPrice, lastCandle.timestamp, OrderType.Long, 
                      EntryType.OverOrderBlock, state.contractSymbol.get))
            case Some(OrderType.Short) => 
                val entryPrice = lastCandle.high
                val high = entryPrice + someAtrs
                Some(Order(entryPrice, high.toFloat, lastCandle.timestamp, OrderType.Short, 
                      EntryType.OverOrderBlock, state.contractSymbol.get))
            case None => None
        }
    }

    private def createMomentumOrder(state: SystemState): Option[Order] = {
        val lastCandle = state.tradingCandles.last
        val entryPrice = lastCandle.close
        val someAtrs = atrs(state, useAtrs)
        ruleHasStrongMomentum(state) match {
            case Some(OrderType.Long) =>
                val low = entryPrice - someAtrs
                Some(Order(low.toFloat, entryPrice, lastCandle.timestamp, OrderType.Long, 
                      EntryType.MomentumOrderBlock, state.contractSymbol.get, 
                      status = OrderStatus.PlaceNow))
            case Some(OrderType.Short) => 
                val high = entryPrice + someAtrs
                Some(Order(entryPrice, high.toFloat, lastCandle.timestamp, OrderType.Short, 
                      EntryType.MomentumOrderBlock, state.contractSymbol.get,
                      status = OrderStatus.PlaceNow))
            case None => None
        }
    }

    private def isGoldenCross(state: SystemState): Boolean = {
        val trendAnalysis = state.recentTrendAnalysis.last
        val priorTrendAnalysis = state.recentTrendAnalysis.init.last
        trendAnalysis.isGoldenCross && !priorTrendAnalysis.isGoldenCross
    }

    private def isDeathCross(state: SystemState): Boolean = {
        val trendAnalysis = state.recentTrendAnalysis.last
        val priorTrendAnalysis = state.recentTrendAnalysis.init.last
        trendAnalysis.isDeathCross && !priorTrendAnalysis.isDeathCross
    }

    private def isStrongUpTrend(state: SystemState): Boolean = {
        val lastCandle = state.tradingCandles.last
        val trendAnalysis = state.recentTrendAnalysis.last
        val volatilityAnalysis = state.recentVolatilityAnalysis.last
        //Either the adx needs to be strong 
        //Or the move on the candle pushes out of the keltner channel
        trendAnalysis.adx > 25.0 || 
            (lastCandle.isBullish &&
             lastCandle.low < volatilityAnalysis.keltnerChannels.upperBand &&
             lastCandle.close > volatilityAnalysis.keltnerChannels.upperBand)
    }

    private def isStrongDownTrend(state: SystemState): Boolean = {
        val lastCandle = state.tradingCandles.last
        val trendAnalysis = state.recentTrendAnalysis.last
        val volatilityAnalysis = state.recentVolatilityAnalysis.last
        trendAnalysis.adx > 25.0 || 
            (lastCandle.isBearish &&
             lastCandle.high > volatilityAnalysis.keltnerChannels.lowerBand &&
             lastCandle.close < volatilityAnalysis.keltnerChannels.lowerBand)
    }

    private def isMovingBullish(state: SystemState): Boolean = {
        val lastCandle = state.tradingCandles.last
        val priorCandle = state.tradingCandles.init.last
        lastCandle.isBullish && !priorCandle.isBearish
    }

    private def isMovingBearish(state: SystemState): Boolean = {
        val lastCandle = state.tradingCandles.last
        val priorCandle = state.tradingCandles.init.last
        lastCandle.isBearish && !priorCandle.isBullish
    }

    private def isSlowingTrend(state: SystemState): Boolean = {
        val lastThreeTrends = state.recentTrendAnalysis.takeRight(3)
        val firstTrend = lastThreeTrends.head
        val lastTrend = lastThreeTrends.last
        lastTrend.adx < 35.0 && lastTrend.adx < firstTrend.adx
    }

    private def ruleIsGoldenOrDeathCross(state: SystemState): Option[OrderType] = {
        if (isGoldenCross(state) && isStrongUpTrend(state) && isMovingBullish(state) && !isSlowingTrend(state)) {
            Some(OrderType.Long)
        } else if (isDeathCross(state) && isStrongDownTrend(state) && isMovingBearish(state) && !isSlowingTrend(state)) {
            Some(OrderType.Short)
        }
        else None
    }

    private def isOversold(state: SystemState): Boolean = {
        val momentumAnalysis = state.recentMomentumAnalysis.takeRight(3)
        momentumAnalysis.exists(_.rsiOversold)
    }

    private def isOverbought(state: SystemState): Boolean = {
        val momentumAnalysis = state.recentMomentumAnalysis.takeRight(3)
        momentumAnalysis.exists(_.rsiOverbought)
    }

    private def isNewYorkQuiet(state: SystemState): Boolean = {
        val lastCandle = state.tradingCandles.last
        val tradingDay = state.tradingDay
        lastCandle.timestamp > TimestampUtils.newYorkQuiet(tradingDay)
    }

    private def isEngulfing(state: SystemState): Boolean = {
        // val momentumAnalysis = state.recentMomentumAnalysis.last
        val lastCandle = state.tradingCandles.last
        val priorCandle = state.tradingCandles.init.last
        lastCandle.engulfs(priorCandle) && lastCandle.isOpposite(priorCandle)
        // candles.exists(lastCandle.engulfs)
        // candles.forall(lastCandle.engulfs)
    }

    private def isUpward(state: SystemState): Boolean = {
        state.tradingCandles.last.direction == Direction.Up
    }

    private def isDownward(state: SystemState): Boolean = {
        state.tradingCandles.last.direction == Direction.Down
    }

    private def hasTrendStrength(state: SystemState): Boolean = {
        state.recentTrendAnalysis.last.adx >= 40.0
    }

    private def ruleIsMomentumOverboughtOrOversold(state: SystemState): Option[OrderType] = {
        val lastCandle = state.tradingCandles.last

        if (isOversold(state) && isNewYorkQuiet(state) && 
            isEngulfing(state) && isUpward(state)) {
            Some(OrderType.Long)
        } else if (isOverbought(state) && isNewYorkQuiet(state) && 
                   isEngulfing(state) && isDownward(state)) {
            Some(OrderType.Short)
        } else None
    }

    private def isSpreadADXCrossing(state: SystemState): Option[Direction] = {
        val keltnerChannel = state.recentVolatilityAnalysis.last.keltnerChannels
        val trendAnalysis = state.recentTrendAnalysis.last
        val keltnerSpread = math.abs(keltnerChannel.lowerBand - keltnerChannel.upperBand)
        val maSpread = math.abs(trendAnalysis.longTermMA - trendAnalysis.shortTermMA)
        val rawStrength = maSpread / keltnerSpread
        val clamped = math.max(0.0, math.min(1.0, rawStrength)) * 100 //scale to match adx scale.
        val adx = trendAnalysis.adx
        if (clamped > adx && adx <= 50.0 && trendAnalysis.isUptrend) Some(Direction.Up)
        else if (clamped > adx && adx <= 50.0 && trendAnalysis.isDowntrend) Some(Direction.Down)
        else None
    }

    private def strengthOfTrendAfterCross(state: SystemState, lastFiveTrend: List[TrendAnalysis]): Double = {
        val keltnerChannel = state.recentVolatilityAnalysis.last.keltnerChannels
        val trendPoints  = lastFiveTrend.tail
        val firstInTrend = trendPoints.head
        val lastInTrend  = trendPoints.last

        val firstSpread  = math.abs(firstInTrend.shortTermMA - firstInTrend.longTermMA)
        val lastSpread   = math.abs(lastInTrend.shortTermMA  - lastInTrend.longTermMA)

        val spreadChange = lastSpread - firstSpread
        val maxSpreadChange = math.abs(keltnerChannel.channelWidth * 0.5) //Half channel growth is max

        val rawStrength   = spreadChange / maxSpreadChange
        math.max(0.0, math.min(1.0, rawStrength))
    }

    private def strengthOfTrendAfterGoldenCross(state: SystemState): Double = {
        val lastestTrend = state.recentTrendAnalysis.takeRight(6)
        val first         = lastestTrend.head
        val nextFive      = lastestTrend.tail

        if (!first.isGoldenCross && lastestTrend.tail.forall(_.isGoldenCross)) {
            strengthOfTrendAfterCross(state, nextFive)
        } else 0.0
    }

    private def strengthOfTrendAfterDeathCross(state: SystemState): Double = {
        val lastestTrend = state.recentTrendAnalysis.takeRight(6)
        val first         = lastestTrend.head
        val nextFive      = lastestTrend.tail

        if (!first.isDeathCross && lastestTrend.tail.forall(_.isDeathCross)) {
            strengthOfTrendAfterCross(state, nextFive)
        } else 0.0
    }
    
    private def theTrendIsYourFriend(state: SystemState): Option[Direction] = {
        // if (strengthOfTrendAfterGoldenCross(state) > 0.25) Some(Direction.Up)
        // else if (strengthOfTrendAfterDeathCross(state) > 0.25) Some(Direction.Down)
        // else None
        isSpreadADXCrossing(state)
    }

    private def nearingSummit(state: SystemState): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = atrs(state, useAtrs) * 2 //Assume 2x profit??
        val peakRed = state.daytimeExtremes.filter(_.extremeType == ExtremeType.High).map(_.level).max
        lastClose < peakRed && (lastClose + someAtrs) > peakRed
    }

    private def nearingFloor(state: SystemState): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = atrs(state, useAtrs) * 2 //Assume 2x profit??
        val peakGreen = state.daytimeExtremes.filter(_.extremeType == ExtremeType.Low).map(_.level).min
        lastClose > peakGreen && (lastClose - someAtrs) < peakGreen
    }

    private def hasReachedLimit(state: SystemState): Boolean = {
        val total = state.orders.foldLeft(0.0) { (r, c) =>
            val runningTotal = c.status match {
                case Profit => r + 1.0
                case Loss => r - 0.5
                case _ => r
            }
            if (runningTotal >= 2.0) 2.0
            else if (runningTotal <= -1.0) -1.0
            else runningTotal
        }

        if (total >= 2.0 || total <= -1.0) true else false
    }

    private def wasDeathCrossNMinutesAgo(state: SystemState, n: Int): Boolean = {
        val lastNPlusOneMinutesAgo = state.recentTrendAnalysis.takeRight(n + 1)
        val nPlusOneMinutesAgo = lastNPlusOneMinutesAgo.head
        nPlusOneMinutesAgo.isDeathCross == false &&
            lastNPlusOneMinutesAgo.tail.forall(_.isDeathCross == true)
    }

    private def wasGoldenCrossNMinutesAgo(state: SystemState, n: Int): Boolean = {
        val lastNPlusOneMinutesAgo = state.recentTrendAnalysis.takeRight(n + 1)
        val nPlusOneMinutesAgo = lastNPlusOneMinutesAgo.head
        nPlusOneMinutesAgo.isGoldenCross == false &&
            lastNPlusOneMinutesAgo.tail.forall(_.isGoldenCross == true)
    }

    private def spreadKeltnerRatioNMinutesAgo(state: SystemState, n: Int): Double = {
        val volatility = state.recentVolatilityAnalysis.takeRight(n + 1).head
        val trend = state.recentTrendAnalysis.takeRight(n + 1).head
        val keltnerChannel = volatility.keltnerChannels
        val keltnerSpread = math.abs(keltnerChannel.lowerBand - keltnerChannel.upperBand)
        val maSpread = math.abs(trend.longTermMA - trend.shortTermMA)
        val rawStrength = maSpread / keltnerSpread
        val clamped = math.max(0.0, math.min(1.0, rawStrength)) * 100 //scale to 0 to 100.
        clamped
    }
    
    private def willRSISpreadCrossBearish(state: SystemState): Boolean = {
        val minutesAgo = 4
        if (wasDeathCrossNMinutesAgo(state, minutesAgo)) {
            val rsiNow = state.recentMomentumAnalysis.last.rsi
            val rsiHistory = state.recentMomentumAnalysis.takeRight(10)
            
            // Find the maximum RSI in recent history
            val maxRsiIdx = rsiHistory.zipWithIndex.maxBy(_._1.rsi)._2
            val rsiAtMax = rsiHistory(maxRsiIdx).rsi
            val timeSinceMax = rsiHistory.length - maxRsiIdx - 1
            
            // Calculate RSI slope from the peak to now
            val rsiSlope = (rsiNow - rsiAtMax) / (timeSinceMax + 0.0000001)
            
            // Calculate spread values and slope from minutesAgo to now
            val spreadNMinutesAgo = spreadKeltnerRatioNMinutesAgo(state, minutesAgo)
            val spreadNow = spreadKeltnerRatioNMinutesAgo(state, 0)
            val spreadSlope = (spreadNow - spreadNMinutesAgo) / minutesAgo
            
            // Find intersection time (from now = time 0)
            // RSI trendline: rsi(t) = rsiNow + rsiSlope * t
            // Spread line: spread(t) = spreadNow + spreadSlope * t
            // At intersection: rsiNow + rsiSlope * t = spreadNow + spreadSlope * t
            // Solving for t: t = (spreadNow - rsiNow) / (rsiSlope - spreadSlope)
            
            val slopeDiff = rsiSlope - spreadSlope
            val timeOfIntersection = (spreadNow - rsiNow) / (slopeDiff + 0.0000001)
            timeOfIntersection <= 4.0
        } else false
    }

    private def willRSISpreadCrossBullish(state: SystemState): Boolean = {
        val minutesAgo = 4
        if (wasGoldenCrossNMinutesAgo(state, minutesAgo)) {
            val rsiNow = state.recentMomentumAnalysis.last.iRsi
            val rsiHistory = state.recentMomentumAnalysis.takeRight(10)
            
            // Find the maximum RSI in recent history
            val maxRsiIdx = rsiHistory.zipWithIndex.maxBy(_._1.iRsi)._2
            val rsiAtMax = rsiHistory(maxRsiIdx).iRsi
            val timeSinceMax = rsiHistory.length - maxRsiIdx - 1
            
            // Calculate RSI slope from the peak to now
            val rsiSlope = (rsiNow - rsiAtMax) / (timeSinceMax + 0.0000001)
            
            // Calculate spread values and slope from minutesAgo to now
            val spreadNMinutesAgo = spreadKeltnerRatioNMinutesAgo(state, minutesAgo)
            val spreadNow = spreadKeltnerRatioNMinutesAgo(state, 0)
            val spreadSlope = (spreadNow - spreadNMinutesAgo) / minutesAgo
            
            // Find intersection time (from now = time 0)
            // RSI trendline: rsi(t) = rsiNow + rsiSlope * t
            // Spread line: spread(t) = spreadNow + spreadSlope * t
            // At intersection: rsiNow + rsiSlope * t = spreadNow + spreadSlope * t
            // Solving for t: t = (spreadNow - rsiNow) / (rsiSlope - spreadSlope)
            
            val slopeDiff = rsiSlope - spreadSlope
            val timeOfIntersection = (spreadNow - rsiNow) / (slopeDiff + 0.0000001)
            timeOfIntersection <= 4.0
        } else false
    }

    private def momentumConvergenceScoreBullish(state: SystemState): Boolean = {
        val minutesAgo = 4
        val threshold = 0.5
        if (wasGoldenCrossNMinutesAgo(state, minutesAgo)) {
            // Current values
            val rsiNow = state.recentMomentumAnalysis.last.rsi
            val spreadNow = spreadKeltnerRatioNMinutesAgo(state, 0)
            
            // Historical values
            val rsiThen = state.recentMomentumAnalysis.takeRight(minutesAgo + 1).head.rsi
            val spreadThen = spreadKeltnerRatioNMinutesAgo(state, minutesAgo)
            
            // Calculate rates of change (velocity)
            val rsiVelocity = (rsiNow - rsiThen) / minutesAgo
            val spreadVelocity = (spreadNow - spreadThen) / minutesAgo
            
            // Both must be increasing (positive velocity) for bullish
            if (rsiVelocity <= 0 || spreadVelocity <= 0) return false
            
            // Normalize current strength to 0-1
            val rsiScore = math.max(0.0, math.min(1.0, (rsiNow - 30.0) / 40.0))
            val spreadScore = spreadNow / 100.0
            
            // Calculate how much they're converging (getting closer together)
            val gapNow = math.abs(rsiScore - spreadScore)
            val gapThen = math.abs((rsiThen - 30.0) / 40.0 - spreadThen / 100.0)
            val isConverging = gapNow < gapThen  // Gap is closing
            
            // Only trigger if BOTH are moving up AND converging
            if (!isConverging) return false
            
            // Final score: both must be strong AND aligned
            val minStrength = math.min(rsiScore, spreadScore)
            val convergence = 1.0 - gapNow
            val score = minStrength * convergence
            
            score > threshold
        } else false
    }

    private def momentumConvergenceScoreBearish(state: SystemState): Boolean = {
        val minutesAgo = 4
        val threshold = 0.5
        if (wasDeathCrossNMinutesAgo(state, minutesAgo)) {
            // Current values
            val rsiNow = state.recentMomentumAnalysis.last.rsi
            val spreadNow = spreadKeltnerRatioNMinutesAgo(state, 0)
            
            // Historical values
            val rsiThen = state.recentMomentumAnalysis.takeRight(minutesAgo + 1).head.rsi
            val spreadThen = spreadKeltnerRatioNMinutesAgo(state, minutesAgo)
            
            // For bearish: Use inverted RSI (100 - rsi) so high values = strong bearish momentum
            val iRsiNow = 100.0 - rsiNow
            val iRsiThen = 100.0 - rsiThen
            
            // Calculate rates of change (velocity)
            val rsiVelocity = (iRsiNow - iRsiThen) / minutesAgo
            val spreadVelocity = (spreadNow - spreadThen) / minutesAgo
            
            // Both must be increasing (positive velocity) for bearish
            if (rsiVelocity <= 0 || spreadVelocity <= 0) return false
            
            // Normalize to 0-1 scale
            val rsiScore = math.max(0.0, math.min(1.0, (iRsiNow - 30.0) / 40.0))
            val spreadScore = spreadNow / 100.0
            
            // Calculate how much they're converging (getting closer together)
            val gapNow = math.abs(rsiScore - spreadScore)
            val gapThen = math.abs((iRsiThen - 30.0) / 40.0 - spreadThen / 100.0)
            val isConverging = gapNow < gapThen  // Gap is closing
            
            // Only trigger if BOTH are moving down AND converging
            if (!isConverging) return false
            
            // Final score: both must be strong AND aligned
            val minStrength = math.min(rsiScore, spreadScore)
            val convergence = 1.0 - gapNow
            val score = minStrength * convergence
            
            score > threshold
        } else false
    }

    private def isMaintainingMomentum(state: SystemState, direction: Direction): Boolean = {
        val lastCandle = state.tradingCandles.last
        val recentCandles = state.tradingCandles.takeRight(3)
        val recentMomentum = state.recentMomentumAnalysis.takeRight(3)
        
        direction match {
            case Direction.Down => 
                val noStrongRejection = lastCandle.lowerWick < (lastCandle.bodyHeight * 1.5)
                val notMakingHigherLows = if (recentCandles.length >= 3) {
                    val lows = recentCandles.map(_.low)
                    !(lows(2) > lows(0) && lows(1) > lows(0))
                } else true
                
                noStrongRejection && notMakingHigherLows
                
            case Direction.Up =>
                val noStrongRejection = lastCandle.upperWick < (lastCandle.bodyHeight * 1.5)
                val notMakingLowerHighs = if (recentCandles.length >= 3) {
                    val highs = recentCandles.map(_.high)
                    !(highs(2) < highs(0) && highs(1) < highs(0))
                } else true
                
                noStrongRejection && notMakingLowerHighs
                
            case _ => false
        }
    }

    private def ruleHasStrongMomentum(state: SystemState): Option[OrderType] = {
        // if (!hasReachedLimit(state)) {
            // willRSISpreadCrossBearish(state) match {
            //     case Some(Direction.Up) if !nearingSummit(state) => Some(OrderType.Long)
            //     case Some(Direction.Down) if !nearingFloor(state) => Some(OrderType.Short)
            //     case _ => None
            // }
        // } else None
        if (willRSISpreadCrossBearish(state) && !nearingFloor(state) && 
                isMaintainingMomentum(state, Direction.Down)) {
            Some(OrderType.Short)
        } else if (willRSISpreadCrossBullish(state) && !nearingSummit(state) && 
                isMaintainingMomentum(state, Direction.Up)) {
            Some(OrderType.Long)
        } else None
    }

    private def atrs(state: SystemState, quantity: Double): Double = {
        state.recentVolatilityAnalysis.last.trueRange.atr * quantity
    }

}
