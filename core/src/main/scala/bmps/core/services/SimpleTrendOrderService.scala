package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderStatus
import bmps.core.models.Order
import bmps.core.models.OrderType
import bmps.core.models.EntryType
import bmps.core.models.ExtremeType
import java.time.Duration
import bmps.core.models.Direction

class SimpleTrendOrderService {
    def processOneMinuteState(state: SystemState): SystemState = {
        if (state.recentTrendAnalysis.size < 4 || state.recentVolatilityAnalysis.size < 3 || shouldNotPlaceOrder(state)) state
        else {
            val atrs = 1.5
            val trendStrengthNow = trendStrengthNMinutesAgo(state, 0)
            val trendStrengthThreeMinAgo = trendStrengthNMinutesAgo(state, 3)
            val trendDirection = direction(state)
            if (
                (trendStrengthNow >= 20.0 && !hasOrderedInSameRally(state)) || 
                (trendStrengthNow <= 1.5 && trendStrengthThreeMinAgo >= 20.0)
            ) {
                if (trendDirection == Direction.Up && !nearingSummit(state, atrs)) {
                    buildOrder(state, atrs)
                } else if (trendDirection == Direction.Down && !nearingFloor(state, atrs)) {
                    buildOrder(state, atrs)
                } else state
            } else state
        }
    }

    //Add floor & ceiling protection
    //Add max loss protection (250, 500, 1000, 2000, 4000 <-- stop)
    //Add protection against repeat attempts in same rally

    def direction(state: SystemState): Direction = {
        state.tradingSwingPoints.last.direction
    }

    def peakRed(state: SystemState): Double = {
        val highestRed = state.daytimeExtremes.filter(_.extremeType == ExtremeType.High).map(_.level.toDouble).reduceOption(_ max _).getOrElse(Double.MinValue)
        val highestToday = state.tradingCandles.map(_.high.toDouble).reduceOption(_ max _).getOrElse(Double.MinValue)
        math.max(highestRed, highestToday)
    }

    def lowGreen(state: SystemState): Double = {
        val lowestGreen = state.daytimeExtremes.filter(_.extremeType == ExtremeType.Low).map(_.level.toDouble).reduceOption(_ min _).getOrElse(Double.MaxValue)
        val lowestToday = state.tradingCandles.map(_.low.toDouble).reduceOption(_ min _).getOrElse(Double.MaxValue)
        math.min(lowestGreen, lowestToday)
    }

    def peakRedHours(state: SystemState, hours: Int): Double = {
        state.tradingCandles
            .filter(_.timestamp >= state.tradingCandles.last.timestamp - Duration.ofHours(hours).toMillis()) //Max from last three hours
            .map(_.high.toDouble).reduceOption(_ max _).getOrElse(Double.MinValue)
    }

    def lowGreenHours(state: SystemState, hours: Int): Double = {
        state.tradingCandles
            .filter(_.timestamp >= state.tradingCandles.last.timestamp - Duration.ofHours(hours).toMillis()) //Min from last three hours
            .map(_.low.toDouble).reduceOption(_ min _).getOrElse(Double.MaxValue)
    }

    def nearingSummit(state: SystemState, useAtrs: Double): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = calcAtrs(state, useAtrs)
        // val peak = peakRed(state)
        val peak = peakRedHours(state, 2)
        lastClose < peak && (lastClose + someAtrs) > peak
    }
    
    def nearingFloor(state: SystemState, useAtrs: Double): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = calcAtrs(state, useAtrs)
        val peakGreen = lowGreenHours(state, 2)
        // val peakGreen = lowGreen(state)
        lastClose > peakGreen && (lastClose - someAtrs) < peakGreen
    }

    def hasOrderedInSameRally(state: SystemState): Boolean = {
        if (state.orders.size > 0) {
            val lastOrderTimestamp = state.orders.last.timestamp
            val lastRallyChange = findLastTrendChangeTime(state)
            lastRallyChange <= lastOrderTimestamp
        } else false
    }

    def findLastTrendChangeTime(state: SystemState): Long = {
        val currentTrendIsGolden = state.recentTrendAnalysis.last.isGoldenCross
        state.recentTrendAnalysis.reverse.find(_.isGoldenCross != currentTrendIsGolden)
            .map(_.timestamp).getOrElse(0L)
    }

    private def trendStrengthNMinutesAgo(state: SystemState, n: Int): Double = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 1).head
        val volatilityAnalysis = state.recentVolatilityAnalysis.takeRight(n + 1).head
        calculateTrendStrength(trendAnalysis, volatilityAnalysis)
    }
    
    private def calculateTrendStrength(trend: bmps.core.services.analysis.TrendAnalysis, 
                                       volatility: bmps.core.services.analysis.VolatilityAnalysis): Double = {
        val maSpread = math.abs(trend.shortTermMA - trend.longTermMA)
        val channelWidth = math.abs(volatility.keltnerChannels.upperBand - volatility.keltnerChannels.lowerBand)
        val rawStrength = maSpread / channelWidth
        val clampedStrength = math.max(0.0, math.min(1.0, rawStrength))
        clampedStrength * 100.0 // Scale to 0-100
    }

    def shouldNotPlaceOrder(state: SystemState): Boolean = {
        state.orders.exists(_.isActive) || 
        hasNLosersToday(state: SystemState, 4) ||
        hasNWinnersToday(state: SystemState, 1)
    }

    def hasNWinnersToday(state: SystemState, n: Int): Boolean = {
        state.orders.count(_.status == OrderStatus.Profit) >= n
    }

    def hasNLosersToday(state: SystemState, n: Int): Boolean = {
        state.orders.count(_.status == OrderStatus.Loss) >= n
    }

    def consecutiveLossCount(state: SystemState): Int = {
        state.orders.foldLeft(0) { (r, c) =>
            c.status match {
                case OrderStatus.Profit => 0
                case OrderStatus.Loss => r + 1
                case _ => r
            }
        }
    }

    def buildOrder(state: SystemState, atrs: Double): SystemState = {
        val losses = consecutiveLossCount(state)
        val riskMultiplier = Math.pow(2, losses).toFloat
        val oneAtr = calcAtrs(state, atrs)
        val lastCandle = state.tradingCandles.last
        val (high, low, orderType) = if (state.recentTrendAnalysis.last.isGoldenCross) {
            val high = lastCandle.close
            val low = high - oneAtr
            (high, low.toFloat, OrderType.Long)
        } else {
            val low = lastCandle.close
            val high = low + oneAtr
            (high.toFloat, low, OrderType.Short)
        }

        val entryType = EntryType.Trendy("SimpleTrend")
        val newOrder = Order(low, high, lastCandle.timestamp, orderType, entryType,
            state.contractSymbol.get, status = OrderStatus.PlaceNow, 
            profitMultiplier = 1.0f, riskMultiplier = Some(riskMultiplier))

        state.copy(orders = state.orders :+ newOrder)
    }

    private def calcAtrs(state: SystemState, quantity: Double): Double = {
        state.recentVolatilityAnalysis.last.trueRange.atr * quantity
    }

}


