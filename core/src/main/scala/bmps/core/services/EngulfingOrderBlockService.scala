package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.models.Candle
import bmps.core.models.OrderStatus
import java.time.Duration

object EngulfingOrderBlockService {

    def processState(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "There must be at least one candle in state.")
        require(state.contractSymbol.isDefined, "The contract symbol must be defined before creating orders.")
        
        val contract = state.contractSymbol.get
        val lastThreeCandles = state.tradingCandles.takeRight(3)
        val (test, subjects) = buildTestAndSubjects(lastThreeCandles)
        // val test = state.tradingCandles.last
        // val subjects = state.tradingCandles.reverse.tail.take(3)
        val newOrder = subjects.find(s => test.engulfs(s) && test.isOpposite(s)).flatMap { subject =>
            test.direction match {
                case Direction.Up if test.close > subject.high && 
                    precededByMeaningfulMove(test, subject, state) && 
                    isSubstantialEngulfing(test, subject, state) &&
                    !isInConsolidation(state, state.tradingCandles.last) => 
                    Some(Order.fromCandle(subject, OrderType.Long, EntryType.EngulfingOrderBlock, test.timestamp, contract))
                case Direction.Down if test.close < subject.low && 
                    precededByMeaningfulMove(test, subject, state) && 
                    isSubstantialEngulfing(test, subject, state) &&
                    !isInConsolidation(state, state.tradingCandles.last) =>
                    Some(Order.fromCandle(subject, OrderType.Short, EntryType.EngulfingOrderBlock, test.timestamp, contract))
                case _ => None
            }
        }

        newOrder.map { order =>
            state.copy(orders = state.orders :+ order)
        }.getOrElse(state)
    }

    def shouldPlaceOrder(order: Order, state: SystemState, candle: Candle): Boolean = {
        require(order.entryType == EntryType.EngulfingOrderBlock, s"Called EngulfingOrderBlock.shouldPlace order with ${order.entryType}")
        
        //Use below for limit orders
        // val orderIsReady = order.orderType match {
        //     case OrderType.Long => candle.close >= order.entryPoint && candle.timestamp > order.timestamp
        //     case OrderType.Short => candle.close <= order.entryPoint && candle.timestamp > order.timestamp
        //     case _ => false
        // }
        
        //Placing market order, so make sure we're within one tick either way of entry. 
        val orderIsReady = math.abs(candle.close - order.entryPoint) <= 0.25 && candle.timestamp > order.timestamp
        val recentFailedOrder = recentEOBLossOrder(state.orders, candle)

        orderIsReady && !recentFailedOrder
    }

    private def recentEOBLossOrder(orders: List[Order], candle: Candle): Boolean = {
        val losingOrders = orders.filter(o => o.entryType == EntryType.EngulfingOrderBlock && o.status == OrderStatus.Loss)
        val lastTimestamp = losingOrders.flatMap(_.closeTimestamp).maxOption.getOrElse(0L)
        candle.timestamp - lastTimestamp <= Duration.ofMinutes(5).toMillis()
    }

    private def buildTestAndSubjects(threeCandles: List[Candle]): (Candle, List[Candle]) = {
        require(threeCandles.size == 3, "Must select exactly three candles")
        val firstCandle = threeCandles.head
        val middleCandle = threeCandles.tail.head
        val lastCandle = threeCandles.last

        if (lastCandle.direction == middleCandle.direction) {
            val test = lastCandle.mergeWithPrevious(middleCandle)
            val subjects = List(firstCandle)
            (test, subjects)
        } else {
            val test = lastCandle
            val subjects = List(middleCandle, firstCandle)
            (test, subjects)
        }
    }

    // Check if engulfing candle is substantial relative to recent volatility
    private def isSubstantialEngulfing(test: Candle, subject: Candle, state: SystemState): Boolean = {
        val idx = state.tradingCandles.indexOf(subject)
        
        // Get average body height of last 10 candles for context
        val recentCandles = if (idx >= 10) {
            state.tradingCandles.slice(idx - 10, idx)
        } else {
            state.tradingCandles.take(idx)
        }
        
        if (recentCandles.isEmpty) return false // Not enough data, don't allow it
        
        val avgBodyHeight = recentCandles.map(_.bodyHeight).sum / recentCandles.length
        
        // Engulfing candle should be at least 1.2x average size
        val isTestSubstantial = test.bodyHeight >= avgBodyHeight * 1.2
        
        // Engulfing should be at least 1.5x the subject candle
        val engulfsSignificantly = test.bodyHeight >= subject.bodyHeight * 1.5
        
        // Subject should not be a doji (very small body)
        val subjectNotDoji = subject.bodyHeight > avgBodyHeight * 0.3
        
        // NEW: Check that engulfing candle doesn't consume too much of the prior move
        val doesntConsumeMove = !engulfingConsumesTooMuchMove(test, subject, state, idx)
        
        isTestSubstantial && engulfsSignificantly && subjectNotDoji && doesntConsumeMove
    }

    // Check if there was a meaningful price move before the pattern
    private def precededByMeaningfulMove(test: Candle, subject: Candle, state: SystemState): Boolean = {
        val idx = state.tradingCandles.indexOf(subject)
        
        // Look back 5-8 candles (more flexible window)
        val lookbackPeriod = math.min(8, idx)
        if (lookbackPeriod < 3) return true // Not enough history, allow it
        
        val startCandle = state.tradingCandles(idx - lookbackPeriod)
        
        subject.direction match {
            case Direction.Down => 
                // For bullish engulfing, check for preceding downtrend
                val priceDecline = startCandle.high - subject.low
                val avgCandleRange = calculateAvgRange(state.tradingCandles.slice(idx - lookbackPeriod, idx + 1))
                
                // Price should have declined at least 2x the average candle range
                priceDecline >= avgCandleRange * 2.0
                
            case Direction.Up =>
                // For bearish engulfing, check for preceding uptrend
                val priceIncline = subject.high - startCandle.low
                val avgCandleRange = calculateAvgRange(state.tradingCandles.slice(idx - lookbackPeriod, idx + 1))
                
                // Price should have inclined at least 2x the average candle range
                priceIncline >= avgCandleRange * 2.0
                
            case _ => false
        }
    }
    
    // Helper to calculate average range (high-low) of candles
    private def calculateAvgRange(candles: Seq[Candle]): Double = {
        if (candles.isEmpty) 0.0
        else {
            val totalRange = candles.map(c => c.high - c.low).sum
            totalRange / candles.length
        }
    }
    
    // Check if the engulfing candle retraces too much of the prior move
    // This indicates we're too late to the reversal
    private def engulfingConsumesTooMuchMove(test: Candle, subject: Candle, state: SystemState, subjectIdx: Int): Boolean = {
        val lookbackPeriod = math.min(8, subjectIdx)
        if (lookbackPeriod < 3) return false // Not enough history, allow it
        
        val startCandle = state.tradingCandles(subjectIdx - lookbackPeriod)
        
        subject.direction match {
            case Direction.Down =>
                // For bullish engulfing after downtrend
                val totalDecline = startCandle.high - subject.low
                val engulfingRetracement = test.close - subject.low
                
                // If engulfing candle retraces more than 60% of the move, it's too late
                val retracementPercent = if (totalDecline > 0) engulfingRetracement / totalDecline else 0.0
                retracementPercent > 0.6
                
            case Direction.Up =>
                // For bearish engulfing after uptrend
                val totalIncline = subject.high - startCandle.low
                val engulfingRetracement = subject.high - test.close
                
                // If engulfing candle retraces more than 60% of the move, it's too late
                val retracementPercent = if (totalIncline > 0) engulfingRetracement / totalIncline else 0.0
                retracementPercent > 0.6
                
            case _ => false
        }
    }

    private def isInConsolidation(state: SystemState, currentCandle: Candle): Boolean = {
        // Look back 15-20 minutes for consolidation pattern
        val lookbackMinutes = 20
        val lookbackMillis = Duration.ofMinutes(lookbackMinutes).toMillis()
        val cutoffTimestamp = currentCandle.timestamp - lookbackMillis
        
        // Get recent swing points within lookback window
        val recentSwings = state.tradingSwingPoints.filter(_.timestamp >= cutoffTimestamp)
        
        if (recentSwings.length < 4) return false // Need at least 4 swings to detect consolidation
        
        // Separate highs and lows
        val swingHighs = recentSwings.filter(_.direction == Direction.Down).map(_.level)
        val swingLows = recentSwings.filter(_.direction == Direction.Up).map(_.level)
        
        if (swingHighs.length < 2 || swingLows.length < 2) return false
        
        // Calculate the range of swing highs and lows
        val highestHigh = swingHighs.max
        val lowestHigh = swingHighs.min
        val highestLow = swingLows.max
        val lowestLow = swingLows.min
        
        // Calculate overall range and clustering
        val overallRange = highestHigh - lowestLow
        val highsRange = highestHigh - lowestHigh  // How much swing highs vary
        val lowsRange = highestLow - lowestLow     // How much swing lows vary
        
        // Get average candle range for context
        val recentCandles = state.tradingCandles
            .filter(c => c.timestamp >= cutoffTimestamp)
        val avgRange = calculateAvgRange(recentCandles)
        
        // Consolidation indicators:
        // 1. Multiple swings (market is oscillating)
        val hasMultipleSwings = recentSwings.length >= 4
        
        // 2. Swing highs are clustered (not trending up)
        val highsClustered = highsRange < overallRange * 0.4
        
        // 3. Swing lows are clustered (not trending down)
        val lowsClustered = lowsRange < overallRange * 0.4
        
        // 4. Overall range is relatively small (< 4x average candle range)
        val rangeIsSmall = overallRange < avgRange * 4.0
        
        // 5. Price has touched both boundaries multiple times
        val touchesTopBoundary = recentCandles.count(c => 
            c.high >= highestHigh - (overallRange * 0.1)
        )
        val touchesBottomBoundary = recentCandles.count(c => 
            c.low <= lowestLow + (overallRange * 0.1)
        )
        val multipleTouches = touchesTopBoundary >= 2 && touchesBottomBoundary >= 2
        
        // We're in consolidation if we have clustered highs/lows with multiple boundary touches
        hasMultipleSwings && highsClustered && lowsClustered && multipleTouches && rangeIsSmall
    }
}
