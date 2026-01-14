package bmps.core.services

import bmps.core.models.Candle
import bmps.core.models.SystemState
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.services.analysis.VolatilityAnalysis
import bmps.core.models.OrderType
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus
import bmps.core.utils.TimestampUtils

object ZoneId {
    val NewLow = 0
    val Long = 1
    val MidLow = 2
    val MidHigh = 3
    val Short = 4
    val NewHigh = 5
}

object Zones {
    def fromState(state: SystemState): Zones = {
        val init = state.tradingCandles.init
        val high = init.map(_.high).max
        val low = init.map(_.low).min
        
        Zones(high, low)
    }

    def isDeathCrossMinutesAgo(state: SystemState, n: Int) = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 2)
        trendAnalysis.tail.head.isDeathCross && !trendAnalysis.head.isDeathCross
    }

    def isGoldenCrossMinutesAgo(state: SystemState, n: Int) = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 2)
        trendAnalysis.tail.head.isGoldenCross && !trendAnalysis.head.isGoldenCross
    }

    def trendStrengthNMinutesAgo(state: SystemState, n: Int): Double = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 1).head
        val volatilityAnalysis = state.recentVolatilityAnalysis.takeRight(n + 1).head
        calculateTrendStrength(trendAnalysis, volatilityAnalysis)
    }

    def calculateTrendStrength(trend: TrendAnalysis, 
                                       volatility: VolatilityAnalysis): Double = {
        val maSpread = math.abs(trend.shortTermMA - trend.longTermMA)
        val channelWidth = math.abs(volatility.keltnerChannels.upperBand - volatility.keltnerChannels.lowerBand)
        val rawStrength = maSpread / channelWidth
        val clampedStrength = math.max(0.0, math.min(1.0, rawStrength))
        clampedStrength * 100.0 // Scale to 0-100
    }
}

//Add to this: check for higher highs (are we trending up all day - don't fight it)
//Add to this: one win logic and double down logic
//Go for win, win, be, reduce loss
    //- 1k, 2k, 4k, 2k - max 8k loss / day ... idk man

case class Zones(high: Float, low: Float) {
    // val quartile = (high - low) * 0.25
    val spread = high - low
    val seventyFive = high - (spread * 0.3)
    val twentyFive = low + (spread * 0.3)
    val fifty = low + (spread * 0.5)

    def newHigh(candle: Candle): Boolean = candle.close > high
    def short(candle: Candle): Boolean = candle.close > seventyFive && candle.close <= high
    def midHigh(candle: Candle): Boolean = candle.close > fifty && candle.close <= seventyFive
    def midLow(candle: Candle): Boolean = candle.close > twentyFive && candle.close <= fifty
    def long(candle: Candle): Boolean = candle.close > low && candle.close <= twentyFive
    def newLow(candle: Candle): Boolean = candle.close <= low

    def zoneId(candle: Candle): Int = {
        if (newLow(candle)) ZoneId.NewLow
        else if (long(candle)) ZoneId.Long
        else if (midLow(candle)) ZoneId.MidLow
        else if (midHigh(candle)) ZoneId.MidHigh
        else if (short(candle)) ZoneId.Short
        else if (newHigh(candle)) ZoneId.NewHigh
        else throw new IllegalStateException("Unexpected state - no zone found")
    }

    def print(candle: Candle): Unit = {
        val id = zoneId(candle)
        if (id == ZoneId.NewHigh) println(s"NewHigh: ${candle.close}")
        println(s"High $high")
        if (id == ZoneId.Short) println(s"Short: ${candle.close}")
        println(s"75% $seventyFive")
        if (id == ZoneId.MidHigh) println(s"MidHigh: ${candle.close}")
        println(s"50% $fifty")
        if (id == ZoneId.MidLow) println(s"MidLow: ${candle.close}")
        println(s"25% $twentyFive")
        if (id == ZoneId.Long) println(s"Long: ${candle.close}")
        println(s"Low $low")
        if (id == ZoneId.NewLow) println(s"NewLow: ${candle.close}")
    }

    def logMinute(candle: Candle, state: SystemState) = {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("America/New_York"))
        val id = zoneId(candle)
        val dcn = Zones.isDeathCrossMinutesAgo(state, 0)
        val dct = Zones.isDeathCrossMinutesAgo(state, 3)
        val gcn = Zones.isGoldenCrossMinutesAgo(state, 0)
        val gct = Zones.isGoldenCrossMinutesAgo(state, 3)
        val tsn = Zones.trendStrengthNMinutesAgo(state, 0)
        val tst = Zones.trendStrengthNMinutesAgo(state, 3)
        val instant = java.time.Instant.ofEpochMilli(candle.timestamp)
        println(s"${formatter.format(instant)} - $id - N $dcn.$gcn.$tsn; T $dct.$gct.$tst")
    }
}

class ZoneTrendOrderService(
    bufferTicks: Float = 1.0f
) {
    def processOneMinuteState(state: SystemState): SystemState = {
        if (state.orders.exists(_.isActive) || state.recentTrendAnalysis.size < 5) state else {
            val currentCandle = state.tradingCandles.last
            val zones = Zones.fromState(state)
            zones.print(currentCandle)
            zones.logMinute(currentCandle, state)
            zones.zoneId(currentCandle) match {
                case ZoneId.NewHigh => state
                case ZoneId.Short =>
                    if (Zones.isDeathCrossMinutesAgo(state, 0) && !hasFailingOrderInZone(state, ZoneId.Short)) {
                        addOrder(OrderType.Short, currentCandle, zones, 0, state)
                    } else state
                case ZoneId.MidHigh | ZoneId.MidLow => 
                    if (
                        Zones.isDeathCrossMinutesAgo(state, 0) &&
                        Zones.trendStrengthNMinutesAgo(state, 3) > 15.0
                    ) {
                        addOrder(OrderType.Short, currentCandle, zones, 0, state)
                    } else if (
                        Zones.isDeathCrossMinutesAgo(state, 3) &&
                        Zones.trendStrengthNMinutesAgo(state, 0) > 15.0
                    ) {
                        addOrder(OrderType.Short, currentCandle, zones, 1, state)
                    } else if (
                        Zones.isGoldenCrossMinutesAgo(state, 0) &&
                        Zones.trendStrengthNMinutesAgo(state, 3) > 15.0
                    ) {
                        addOrder(OrderType.Long, currentCandle, zones, 0, state)
                    } else if (
                        Zones.isGoldenCrossMinutesAgo(state, 3) &&
                        Zones.trendStrengthNMinutesAgo(state, 0) > 15.0
                    ) {
                        addOrder(OrderType.Long, currentCandle, zones, 1, state)
                    } else state
                case ZoneId.Long =>
                    if (Zones.isGoldenCrossMinutesAgo(state, 0) && !hasFailingOrderInZone(state, ZoneId.Long)) {
                        addOrder(OrderType.Long, currentCandle, zones, 0, state)
                    } else state
                case ZoneId.NewLow => state
            }
        }
    }

    private def hasFailingOrderInZone(state: SystemState, zoneId: Int): Boolean = {
        state.orders.filter(_.status == OrderStatus.Loss).map(_.entryType).exists { entryType =>
            entryType match {
                case _ @ EntryType.Trendy(description) => description.startsWith(s"ZoneId:$zoneId.")
                case _ => false
            }
        }
    }

    private def hasNWinnersToday(state: SystemState, n: Int): Boolean = {
        countWinnersToday(state) >= n
    }

    private def hasNLosersToday(state: SystemState, n: Int): Boolean = {
        countLosersToday(state) >= n
    }

    private def countWinnersToday(state: SystemState): Int = {
        state.orders.count(_.status == OrderStatus.Profit)
    }

    private def countLosersToday(state: SystemState): Int = {
        state.orders.count(_.status == OrderStatus.Loss)
    }

    private def isEndOfDay(state: SystemState): Boolean = {
        TimestampUtils.isNearTradingClose(state.tradingCandles.last.timestamp)
    }

    private def calcAtrs(state: SystemState, quantity: Double): Double = {
        state.recentVolatilityAnalysis.last.trueRange.atr * quantity
    }

    def addOrder(orderType: OrderType, lastCandle: Candle, zones: Zones, 
                 subId: Int, state: SystemState): SystemState = {
        if (hasNWinnersToday(state, 1) || hasNLosersToday(state, 4) || isEndOfDay(state)) {
            state
        } else {
            val zoneId = zones.zoneId(lastCandle)
            val losses = countLosersToday(state)
            val riskMultiplier = Math.pow(2, losses).toFloat
            val (low, high) = orderType match {
                case OrderType.Short if zoneId == ZoneId.Short => 
                    (lastCandle.close, zones.high + bufferTicks)
                case OrderType.Short if zoneId == ZoneId.MidHigh || zoneId == ZoneId.MidLow =>  
                    val atrs = calcAtrs(state, 1.0).toFloat
                    (lastCandle.close, lastCandle.close + atrs)
                case OrderType.Long if zoneId == ZoneId.MidLow || zoneId == ZoneId.MidHigh => 
                    val atrs = calcAtrs(state, 1.0).toFloat
                    (lastCandle.close - atrs, lastCandle.close)
                case OrderType.Long if zoneId == ZoneId.Long =>
                    (zones.low - bufferTicks, lastCandle.close)
                case _ => throw new IllegalArgumentException("Unexpected order state.")
            }

            val newOrder = Order(
                low,
                high,
                lastCandle.timestamp,
                orderType,
                EntryType.Trendy(s"ZoneId:$zoneId.$subId"),
                state.contractSymbol.get,
                status = OrderStatus.PlaceNow,
                profitMultiplier = 1.0f,
                riskMultiplier = Some(riskMultiplier)
            )

            state.copy(orders = state.orders :+ newOrder)
        }
    }
}
