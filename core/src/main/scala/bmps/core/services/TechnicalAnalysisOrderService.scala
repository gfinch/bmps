package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.ExtremeType
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.models.OrderType
import bmps.core.models.EntryType.Trendy
import bmps.core.models.OrderStatus
import bmps.core.utils.TimestampUtils
import bmps.core.models.OrderStatus.Profit
import java.time.Duration
import java.sql.Timestamp
import bmps.core.models.SerializableOrder
import bmps.core.models.OrderStatus.Loss

class TechnicalAnalysisOrderService(initialCapital: Double = 18000.0) {
    def processOneMinuteState(state: SystemState): SystemState = {
        val lastCandle = state.tradingCandles.last
        if (state.orders.exists(_.isActive) || TimestampUtils.isNearTradingClose(lastCandle.timestamp)) {
        // if (TimestampUtils.isNearTradingClose(lastCandle.timestamp)) {
            state
        } else {
            val orders = createOrders(state: SystemState)
            state.copy(orders = state.orders ++ orders)
        }
    }

    def createOrders(state: SystemState): List[Order] = {
        if (state.tradingCandles.length < 10) List.empty
        else {
            val activeOrders = state.orders.filter(_.isActive).map(_.entryType.toString())
            val activeScenarios = winningCombinations.filterNot(s => { //allScenarios
                val desc = buildScenarioDescription(s)
                activeOrders.contains(desc)
            })
            val riskMultiplier = computeRiskMultiplier(state)
            
            val newOrders = for {
                scenario <- activeScenarios
            } yield {
                if (scenario.forall(n => applyScenario(n, scenario, state))) {
                    val newOrder = buildOrder(state, scenario, riskMultiplier)
                    // Some(newOrder)
                    safetyChecks(state, newOrder)
                } else None
            }

            newOrders.flatten.take(1)
            
        }
    }

    def buildOrder(state: SystemState, scenario: List[Int], riskMultiplier: Float): Order = {
        val someAtrs = atrs(state, 2.0).toFloat
        val lastCandle = state.tradingCandles.last
        val (low, high, orderType) = if (state.recentTrendAnalysis.last.isUptrend) {
            (lastCandle.close - someAtrs, lastCandle.close, OrderType.Long)
        } else (lastCandle.close, lastCandle.close + someAtrs, OrderType.Short)
        val entryType = Trendy(buildScenarioDescription(scenario))
        Order(low, high, lastCandle.timestamp, orderType, entryType, state.contractSymbol.get, 
              status = OrderStatus.PlaceNow,
              riskMultiplier = Some(riskMultiplier))
    }

    def buildScenarioDescription(scenario: List[Int]) = scenario.mkString(",")

    val rules = Map(
        1 -> "MustBeOversoldOrBought",
        2 -> "MustNotBeOversoldOrBought",
        3 -> "MustBeNearingSummitOrFloor",
        4 -> "MustNotBeNearingSummitOrFloor",
        5 -> "CrossNow",
        6 -> "CrossTwoMinutesAgo",
        7 -> "CrossThreeMinutesAgo", 
        8 -> "CrossFiveMinutesAgo",
        9 -> "CrossSevenMinutesAgo",
        10 -> "CrossTenMinutesAgo",
        11 -> "HasIncreasingADX",
        12 -> "HasDecreasingADX",
        13 -> "SpreadToTen",
        14 -> "SpreadToTwenty",
        15 -> "SpreadToThirty",
        16 -> "SpreadToForty",
        17 -> "SpreadToFifty",
        18 -> "AlreadyIntersected",
        19 -> "IntersectingNextMinute",
        20 -> "IntersectingTwoMinutes",
        21 -> "IntersectingFiveMinutes"
    )

    val boughtGroup = List(1, 2)
    val summitGroup = List(3, 4)
    val crossGroup = List(5, 6, 7, 8, 9, 10)
    val adxGroup = List(11, 12)
    val spreadGroup = List(13, 14, 15, 16, 17)
    val intersectGroup = List(18, 19, 20, 21)

    val crossOnly = crossGroup.map(List(_))
    val crossAndBought = crossGroup.flatMap(c => boughtGroup.map(b => List(c, b)))
    val crossAndSummit = crossGroup.flatMap(c => summitGroup.map(b => List(c, b)))
    val crossAndAdx = crossGroup.flatMap(c => adxGroup.map(b => List(c, b)))
    val crossAndAdxAndBought = 
        for {
            c <- crossGroup
            a <- adxGroup
            b <- boughtGroup
        } yield List(c, a, b)
    val crossAndAdxAndSummit = 
        for {
            c <- crossGroup
            a <- adxGroup
            b <- summitGroup
        } yield List(c, a, b)
    val spreadOnly = 
        for {
            a <- crossGroup
            b <- spreadGroup
        } yield List(a, b)
    val spreadAndIntersect = 
        for {
            a <- crossGroup
            c <- spreadGroup
            b <- intersectGroup
        } yield List(a, c, b)
    val spreadIntersectAndBought = 
        for {
            a <- crossGroup
            c <- spreadGroup
            b <- intersectGroup
            d <- boughtGroup
        } yield List(a, c, b, d)
    val spreadIntersectAndSummit = 
        for {
            a <- crossGroup
            c <- spreadGroup
            b <- intersectGroup
            d <- summitGroup
        } yield List(a, c, b, d)
    val spreadIntersectAdx = 
        for {
            a <- crossGroup
            c <- spreadGroup
            b <- intersectGroup
            d <- adxGroup
        } yield List(a, c, b, d)

    lazy val allScenarios = (crossOnly ++ crossAndBought ++ crossAndSummit ++ crossAndAdx ++ crossAndAdxAndBought ++ crossAndAdxAndSummit ++
        spreadOnly ++ spreadAndIntersect ++ spreadIntersectAndBought ++ spreadIntersectAndSummit ++ spreadIntersectAdx)
    // .filter { scenario =>
    //         winningCombinations.contains(scenario.toSet)
    // }

    //Besties:
    /**
      *       7,11,3: Wins= 15, Losses= 13, WinRate= 53.57%
             10,12,3: Wins=  7, Losses=  8, WinRate= 46.67%
              9,11,3: Wins=  5, Losses=  6, WinRate= 45.45%
              6,11,3: Wins= 14, Losses= 19, WinRate= 42.42%
             10,12,4: Wins=103, Losses=148, WinRate= 41.04%
          7,14,21,11: Wins=252, Losses=404, WinRate= 38.41%
          9,13,21,12: Wins= 83, Losses=134, WinRate= 38.25%
      */

    def applyScenario(id: Int, siblings: List[Int], state: SystemState): Boolean = {
        id match {
            case 1 => isOverbought(state) || isOversold(state)
            case 2 => !(isOverbought(state) || isOversold(state))
            case 3 => nearingSummit(state, 1.0) || nearingFloor(state, 1.0)
            case 4 => !(nearingSummit(state, 1.0) || nearingFloor(state, 1.0))
            case 5 => false //wasDeathCrossNMinutesAgo(state, 0) || wasGoldenCrossNMinutesAgo(state, 0) ##This generated a lot of orders that were not winners.
            case 6 => wasDeathCrossNMinutesAgo(state, 2) || wasGoldenCrossNMinutesAgo(state, 2)
            case 7 => wasDeathCrossNMinutesAgo(state, 3) || wasGoldenCrossNMinutesAgo(state, 3)
            case 8 => wasDeathCrossNMinutesAgo(state, 5) || wasGoldenCrossNMinutesAgo(state, 5)
            case 9 => wasDeathCrossNMinutesAgo(state, 7) || wasGoldenCrossNMinutesAgo(state, 7)
            case 10 => wasDeathCrossNMinutesAgo(state, 10) || wasGoldenCrossNMinutesAgo(state, 10)
            case 11 => hasIncreasingADX(state, 3)
            case 12 => !hasIncreasingADX(state, 3)
            case 13 => hasSpread(state, siblings, 10)
            case 14 => hasSpread(state, siblings, 20)
            case 15 => hasSpread(state, siblings, 30)
            case 16 => hasSpread(state, siblings, 40)
            case 17 => hasSpread(state, siblings, 50)
            case 18 => isIntersecting(state, siblings, 0)
            case 19 => isIntersecting(state, siblings, 1)
            case 20 => isIntersecting(state, siblings, 2)
            case 21 => isIntersecting(state, siblings, 5)
        }
    }

    //~~~~~~~~~~~

    def isOversold(state: SystemState): Boolean = {
        val momentumAnalysis = state.recentMomentumAnalysis.takeRight(3)
        momentumAnalysis.exists(_.rsiOversold)
    }

    def isOverbought(state: SystemState): Boolean = {
        val momentumAnalysis = state.recentMomentumAnalysis.takeRight(3)
        momentumAnalysis.exists(_.rsiOverbought)
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
        val someAtrs = atrs(state, useAtrs) * 2 //Assume 2x profit??
        val peak = peakRed(state)
        lastClose < peak && (lastClose + someAtrs) > peak
    }
    
    def nearingFloor(state: SystemState, useAtrs: Double): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = atrs(state, useAtrs) * 2 //Assume 2x profit??
        val peakGreen = lowGreen(state)
        lastClose > peakGreen && (lastClose - someAtrs) < peakGreen
    }

    def wasDeathCrossNMinutesAgo(state: SystemState, n: Int): Boolean = {
        val lastNPlusOneMinutesAgo = state.recentTrendAnalysis.takeRight(n + 1)
        val nPlusOneMinutesAgo = lastNPlusOneMinutesAgo.head
        nPlusOneMinutesAgo.isDeathCross == false &&
            lastNPlusOneMinutesAgo.tail.forall(_.isDeathCross == true)
    }

    def wasGoldenCrossNMinutesAgo(state: SystemState, n: Int): Boolean = {
        val lastNPlusOneMinutesAgo = state.recentTrendAnalysis.takeRight(n + 1)
        val nPlusOneMinutesAgo = lastNPlusOneMinutesAgo.head
        nPlusOneMinutesAgo.isGoldenCross == false &&
            lastNPlusOneMinutesAgo.tail.forall(_.isGoldenCross == true)
    }

    def hasIncreasingADX(state: SystemState, n: Int): Boolean = {
        val lastNTrend = state.recentTrendAnalysis.takeRight(n)
        val point1 = lastNTrend.head.adx
        val point2 = lastNTrend.last.adx
        slope(0, point1, n, point2) > 0
    }

    def hasSpread(state: SystemState, siblings: List[Int], threshold: Int): Boolean = {
        val ago = minutesAgo(siblings)
        if (ago > 2 && (wasGoldenCrossNMinutesAgo(state, ago) || wasDeathCrossNMinutesAgo(state, ago))) {
            buildSpreads(state, ago).map { case (firstSpread, lastSpread) => 
                val spreadChange = lastSpread - firstSpread
                spreadChange > threshold
            }.getOrElse(false)
        } else false
    }

    def buildSpreads(state: SystemState, ago: Int): Option[(Double, Double)] = {
        val trendPoints = state.recentTrendAnalysis.takeRight(ago + 1)
        if (trendPoints.size > 2) {
            val firstSpread = calculateTrendStrength(state, ago)
            val lastSpread = calculateTrendStrength(state)
            Some(firstSpread, lastSpread)
        } else None
    }

    def isIntersecting(state: SystemState, siblings: List[Int], within: Int): Boolean = {
        val ago = minutesAgo(siblings)
        buildSpreads(state, ago).map { case (firstSpread, lastSpread) =>
            val spreadSlope = slope(0, firstSpread, ago, lastSpread)
            val expected = futurePoint(spreadSlope, 0, lastSpread, within.toLong)
            if (wasGoldenCrossNMinutesAgo(state, ago)) {
                val recentIRsi = state.recentMomentumAnalysis.takeRight(ago + 1).map(_.iRsi)
                val rsiSlope = slope(0, recentIRsi.head, ago, recentIRsi.last)
                val rsiExpected = futurePoint(rsiSlope, 0, recentIRsi.last, within.toLong)
                rsiExpected < expected
            } else if (wasDeathCrossNMinutesAgo(state, ago)) {
                val recentRsi = state.recentMomentumAnalysis.takeRight(ago + 1).map(_.rsi)
                val rsiSlope = slope(0, recentRsi.head, ago, recentRsi.last)
                val rsiExpected = futurePoint(rsiSlope, 0, recentRsi.last, within.toLong)
                rsiExpected < expected
            } else false
        }.getOrElse(false)
    }

    def minutesAgo(siblings: List[Int]): Int = {
        if (siblings.contains(5)) 0
        else if (siblings.contains(6)) 2
        else if (siblings.contains(7)) 3
        else if (siblings.contains(8)) 5
        else if (siblings.contains(9)) 7
        else if (siblings.contains(10)) 10
        else 0
    }

    def computeRiskMultiplier(state: SystemState): Float = {
        val orders = state.orders.filter(_.isProfitOrLoss)
        val pastOrders = state.recentOrders ++ orders
        val approximateAccountValue = pastOrders.foldLeft(initialCapital) { (r, c) =>
            val risk = riskPerTrade(r)
            r + valueOfOrder(c, risk)
        }
        val finalRisk = riskPerTrade(approximateAccountValue)
        val valueBasedMultiplier = finalRisk.toFloat / 1000.0f
        val lastWinIndex = pastOrders.reverse.indexWhere(_.status == OrderStatus.Profit)
        val finalIndex = if (lastWinIndex == -1) pastOrders.size else lastWinIndex
        finalIndex match {  
            case -1 => valueBasedMultiplier
            case 0 => valueBasedMultiplier
            case 1 => valueBasedMultiplier * 2.0f
            case 2 => valueBasedMultiplier * 4.0f
            case n => (valueBasedMultiplier * math.pow(0.5, n - 2)).toFloat
        }
    }

    private def riskPerTrade(runningTotal: Double): Double = {
        // if (runningTotal < 30000.0) 300.0
        if (runningTotal < 50000.0) 500.0
        else if (runningTotal < 100000.0) 1000.0
        else math.floor(runningTotal / 100000.0) * 1000.0
    }

    private def valueOfOrder(order: Order, riskPerTrade: Double): Double = {
        require(order.isProfitOrLoss, "This order is not closed.")
        val serializedOrder = SerializableOrder.fromOrder(order, riskPerTrade)
        order.status match {
            case Loss => serializedOrder.atRisk * -1
            case Profit => serializedOrder.potential
            case _ => throw new IllegalStateException(s"Unexpected order statue: ${order.status}")
        }
    }

    //NO safety checks = $56k / $170k
    //InsideLimit only = $70k / $177k
    //InQuiet && InLimit && early = $22k
    //Strong Trend only = $34k
    //Up trend only = $43k
    //>10 trend only = $
    //>-3 run, = $-4k
    //>-6 run, = $30k
    //<3 run, $63k
    //<6 run, $56k
    //drawdown -2 from max = $-94k
    //drawdown -2 from max >=3 = $63k
    //drawdown - 2 from max >=3 = $72k
    //max < 3 and inside limit = $75k / $185k
    //    - with limited winners = $66k / $228k ***
    //    - don't order in same direction = $53k / $157k
    //    - within hour = $56k / $184k
    //------ doubled total and max
    //drawdown - 2 from max 6 or limit 6 and inside limit = $57k
    //limit 1, drawdown -3 and inside limit = $35k

    //early open and inside limit = $28k
    //later morning and inside limit = -$8k
    //early afternoon = $25k
    //closing = $42k / $85k <-- fake, there are end of day trades that aren't real
    //------ fixed closing profit calculation defect :(
    //closing = $18k
    //baseline = $44k / $168k 
    //>>> baseline + exclude late morning = $65k / $182k *** new best after defect fix.
    //above + inlimithours = $42k
    //above + 1.5 atrs = $44k
    //above + 1.75 atrs = $51k
    //above + 1.9 atrs = $57k
    //above + 2.2 atrs = $39k

    def safetyChecks(state: SystemState, order: Order): Option[Order] = {
        // val trendStrengthNow = calculateTrendStrength(state, 0)
        // val trendStrength2MinAgo = calculateTrendStrength(state, 2)
        // val strongTrend = trendStrengthNow > 10.0
        // val increasingTrend = trendStrengthNow > trendStrength2MinAgo

        val insideLimit = order.orderType match {
            case OrderType.Long => order.takeProfit < peakRed(state)
            case OrderType.Short => order.takeProfit > lowGreen(state)
        }

        // val insideLimitHours = order.orderType match {
        //     case OrderType.Long => order.takeProfit < peakRedHours(state, 3)
        //     case OrderType.Short => order.takeProfit > lowGreenHours(state, 3)
        // }

        // val isInEarlyOpen = TimestampUtils.isInEarlyOpen(order.timestamp)
        // val isInQuiet = TimestampUtils.isInQuiet(order.timestamp)

        val (running, max) = state.orders.foldLeft(0, 0) { case ((r, x), c) =>
            c.status match {
                case OrderStatus.Profit => 
                    val total = r + 2
                    val max = if (total > x) total else x
                    (total, max)
                case OrderStatus.Loss => (r - 1, x)
                case _ => (r, x)
            }
        }

        // val lastWinnerOrLoser = state.orders.filter(o => o.status == OrderStatus.Profit || o.status == OrderStatus.Loss).lastOption
        // val lastIsLoser = lastWinnerOrLoser.map(_.status == OrderStatus.Loss).getOrElse(false)
        // val isSameDirection = lastWinnerOrLoser.map(_.direction == order.direction).getOrElse(false)
        // val isWithinOneHour = lastWinnerOrLoser.map(_.timestamp + Duration.ofHours(1).toMillis > order.timestamp).getOrElse(false)
        // val lastIsLoserSameDirection = lastIsLoser && isSameDirection
        // val lastIsLoserSameDirectionOneHour = lastIsLoserSameDirection && isWithinOneHour

        // val isEarlyOpen = TimestampUtils.isInHour(order.timestamp, 9, 10)
        val isLaterMorning = TimestampUtils.isInHour(order.timestamp, 10, 12)
        // val isEarlyAfternoon = TimestampUtils.isInHour(order.timestamp, 12, 15)
        // val isClosing = TimestampUtils.isInHour(order.timestamp, 15, 16)

        // if (isClosing && insideLimit) Some(order) else None

        // if ((isInQuiet && insideLimit) || (!isInEarlyOpen && !isInQuiet)) {
        //     if (strongTrend) Some(order) else None
        // } else None

        // if ((isInQuiet && insideLimit) || (!isInEarlyOpen && !isInQuiet)) {
        //     Some(order)
        // } else None

        // if (strongTrend) Some(order) else None

        // if (insideLimit) Some(order) else None
        // if (strongTrend) Some(order) else None
        // if ((max < 6 || (max - running) != 2) && insideLimit) Some(order) else None
        // if (running < 1 && running >= -3 && insideLimit) Some(order) else None
        if (max < 6 && insideLimit && !isLaterMorning) Some(order) else None //**** <<< winner so far

        // Some(order)
    }

    private def slope(t1: Long, y1: Double, t2: Long, y2: Double): Double = {
        (y2 - y1) / (t2 - t1).toDouble
    }

    private def futurePoint(slope: Double, t1: Long, y1: Double, t3: Long): Double = {
        y1 + slope * (t3 - t1)
    }

    private def atrs(state: SystemState, quantity: Double): Double = {
        state.recentVolatilityAnalysis.last.trueRange.atr * quantity
    }

    def calculateTrendStrength(state: SystemState, minutesAgo: Int = 0): Double = {
        require(minutesAgo >= 0, "minutesAgo must be non-negative")

        val requiredSize = minutesAgo + 1

        if (state.recentTrendAnalysis.length < requiredSize || 
            state.recentVolatilityAnalysis.length < requiredSize) {
            return 0.0
        }

        // Get the analysis from N minutes ago
        // takeRight(minutesAgo + 1) gets the last N+1 elements, then head gets the oldest
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

        // Calculate MA spread (how far apart short and long MAs are)
        val maSpread = math.abs(trendAnalysis.shortTermMA - trendAnalysis.longTermMA)

        // Calculate absolute Keltner channel width
        val channelWidth = math.abs(keltnerChannels.upperBand - keltnerChannels.lowerBand) * 0.5

        // Avoid division by zero
        if (channelWidth == 0.0) {
            return 0.0
        }

        // Ratio of MA spread to channel width
        val rawStrength = maSpread / channelWidth

        // Clamp between 0.0 and 1.0
        math.max(0.0, math.min(1.0, rawStrength)) * 100
    }

    /**
      *       7,11,3: Wins= 15, Losses= 13, WinRate= 53.57%
             10,12,3: Wins=  7, Losses=  8, WinRate= 46.67%
              9,11,3: Wins=  5, Losses=  6, WinRate= 45.45%
              6,11,3: Wins= 14, Losses= 19, WinRate= 42.42%
             10,12,4: Wins=103, Losses=148, WinRate= 41.04%
          7,14,21,11: Wins=252, Losses=404, WinRate= 38.41%
          9,13,21,12: Wins= 83, Losses=134, WinRate= 38.25%
      */

    val winningCombinations = List(
        // List(8,13,21,12), aa
        List(9,11,3), //>
        // List(10,17,21,12),
        // List(7,17,21,11),
        // List(8,12,3),
        // List(10,15),
        // List(10,14,21,11), aa
        List(10,12,3), //>
        List(6,11,3), //>
        // List(8,15,21,12),
        List(9,13,21,12), //>
        // List(8,13,21,11),
        // List(9,12,4),
        // List(10,15,21,11),
        List(7,14,21,11), //>
        // List(8,17,21,11),
        // List(7,16,21,11),
        // List(10,13), aa
        List(10,12,4), //>
        List(7,11,3), //>
        // List(9,13)
    ) 


}
