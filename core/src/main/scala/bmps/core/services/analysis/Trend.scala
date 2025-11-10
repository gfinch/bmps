package bmps.core.services.analysis

import bmps.core.models.Candle
import scala.collection.mutable
import bmps.core.models.Direction

// Case class to hold DMI/ADX results
case class DMIResult(plusDI: Double, minusDI: Double, adx: Double)

// Case class to hold complete trend analysis results
case class TrendAnalysis(
    sma: Double,
    ema: Double,
    tma: Double,
    shortTermMA: Double,  // e.g., 9-period EMA
    longTermMA: Double,   // e.g., 21-period EMA
    plusDI: Double,
    minusDI: Double,
    adx: Double
) {
    // Convenience methods for trend interpretation
    def isUptrend: Boolean = plusDI > minusDI
    def isDowntrend: Boolean = minusDI > plusDI
    def isStrongTrend: Boolean = adx > 25.0
    def trendStrength: String = adx match {
        case x if x < 25 => "Weak"
        case x if x < 50 => "Strong"
        case x if x < 75 => "Very Strong"
        case _ => "Extremely Strong"
    }

    def direction: Direction = isUptrend match {
        case true if isStrongTrend => Direction.Up
        case false if isStrongTrend => Direction.Down
        case _ => Direction.Doji //weak trends are considered doji.
    }
    
    // MA crossover signals
    def isGoldenCross: Boolean = shortTermMA > longTermMA
    def isDeathCross: Boolean = shortTermMA < longTermMA
    def maCrossoverStrength: Double = math.abs(shortTermMA - longTermMA) / longTermMA
}

class Trend() {
    
    // Alternative SMA that handles insufficient data gracefully
    def simpleMovingAverage(candles: List[Candle], depth: Int): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(depth > 0, "Depth must be positive")
        
        // Use all available candles if we don't have enough for the requested depth
        val actualDepth = math.min(depth, candles.size)
        val relevantCandles = candles.takeRight(actualDepth)
        
        relevantCandles.map(_.close.toDouble).sum / actualDepth
    }
    
    // Most efficient version - only processes exactly what we need
    def exponentialMovingAverage(candles: List[Candle], depth: Int): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(depth > 0, "Depth must be positive")
        
        val multiplier = 2.0 / (depth + 1)
        
        // Take only the most recent candles we need for accurate EMA
        // For EMA, using 2-3x the depth gives nearly identical results to using all data
        val requiredLength = math.min(candles.size, math.max(depth * 2, 10))
        val recentCandles = candles.takeRight(requiredLength)
        
        if (recentCandles.size == 1) return recentCandles.head.close.toDouble
        
        // Start with SMA of first few candles for better initialization
        val initLength = math.min(3, recentCandles.size - 1)
        val initialEma = recentCandles.take(initLength).map(_.close.toDouble).sum / initLength
        
        // Apply EMA to the remaining candles
        recentCandles.drop(initLength).foldLeft(initialEma) { (ema, candle) =>
            (candle.close.toDouble * multiplier) + (ema * (1 - multiplier))
        }
    }
    
    // Alternative TMA implementation that's more efficient for single value calculation
    def triangularMovingAverage(candles: List[Candle], depth: Int): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(depth > 0, "Depth must be positive")
        
        val (period1, period2) = if (depth % 2 == 1) {
            val p = (depth + 1) / 2
            (p, p)
        } else {
            (depth / 2, depth / 2 + 1)
        }
        
        val requiredLength = period1 + period2 - 1
        
        // If we don't have enough candles for TMA, fall back to SMA
        if (candles.size < requiredLength) {
            return simpleMovingAverage(candles, math.min(depth, candles.size))
        }
        
        // Take only the most recent candles we need
        val recentCandles = candles.takeRight(requiredLength)
        val prices = recentCandles.map(_.close.toDouble)
        
        // Calculate the first layer of SMA values we need for the final calculation
        val firstSMAValues = for (i <- period1 - 1 until prices.length) yield {
            prices.slice(i - period1 + 1, i + 1).sum / period1
        }
        
        // Calculate the final TMA value
        firstSMAValues.takeRight(period2).sum / period2
    }
    
    // Calculate True Range for a single candle
    private def calculateTrueRange(current: Candle, previous: Candle): Double = {
        val tr1 = current.high - current.low
        val tr2 = math.abs(current.high - previous.close)
        val tr3 = math.abs(current.low - previous.close)
        math.max(tr1, math.max(tr2, tr3))
    }
    
    // Calculate Directional Movement (+DM and -DM)
    private def calculateDirectionalMovement(current: Candle, previous: Candle): (Double, Double) = {
        val upMove = current.high - previous.high
        val downMove = previous.low - current.low
        
        val plusDM = if (upMove > downMove && upMove > 0) upMove else 0.0
        val minusDM = if (downMove > upMove && downMove > 0) downMove else 0.0
        
        (plusDM, minusDM)
    }
    
    // Smoothed moving average used in ADX calculation (Wilder's smoothing)
    private def wildersSmoothing(values: List[Double], period: Int): Double = {
        if (values.size < period) return values.sum / values.size
        
        val initial = values.take(period).sum / period
        values.drop(period).foldLeft(initial) { (smoothed, value) =>
            ((smoothed * (period - 1)) + value) / period
        }
    }
    
    // Calculate DMI and ADX
    def calculateDMI(candles: List[Candle], period: Int = 14): DMIResult = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        require(candles.size >= 2, "Need at least 2 candles for DMI calculation")
        
        // If we don't have enough data, return simplified values
        if (candles.size < period + 1) {
            val avgClose = candles.map(_.close.toDouble).sum / candles.size
            return DMIResult(plusDI = 50.0, minusDI = 50.0, adx = 25.0)
        }
        
        // Calculate True Range and Directional Movements for each period
        val trueRanges = mutable.ListBuffer[Double]()
        val plusDMs = mutable.ListBuffer[Double]()
        val minusDMs = mutable.ListBuffer[Double]()
        
        for (i <- 1 until candles.length) {
            val current = candles(i)
            val previous = candles(i - 1)
            
            trueRanges += calculateTrueRange(current, previous)
            val (plusDM, minusDM) = calculateDirectionalMovement(current, previous)
            plusDMs += plusDM
            minusDMs += minusDM
        }
        
        // Calculate smoothed averages
        val smoothedTR = wildersSmoothing(trueRanges.toList, period)
        val smoothedPlusDM = wildersSmoothing(plusDMs.toList, period)
        val smoothedMinusDM = wildersSmoothing(minusDMs.toList, period)
        
        // Calculate DI+ and DI-
        val plusDI = if (smoothedTR != 0) (smoothedPlusDM / smoothedTR) * 100 else 0.0
        val minusDI = if (smoothedTR != 0) (smoothedMinusDM / smoothedTR) * 100 else 0.0
        
        // Calculate DX (Directional Index)
        val dx = if (plusDI + minusDI != 0) {
            math.abs(plusDI - minusDI) / (plusDI + minusDI) * 100
        } else 0.0
        
        // For ADX, we need to smooth the DX values, but for simplicity we'll return the current DX
        // In a full implementation, you'd calculate multiple DX values and smooth them
        val adx = dx
        
        DMIResult(plusDI, minusDI, adx)
    }
    
    // Calculate ADX with proper DX smoothing (more accurate)
    def calculateADX(candles: List[Candle], period: Int = 14): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        require(candles.size >= 2, "Need at least 2 candles for ADX calculation")
        
        // If we don't have enough data, return neutral ADX
        if (candles.size < period * 2) {
            return 25.0 // Neutral ADX value
        }
        
        // Calculate DX values for smoothing
        val dxValues = mutable.ListBuffer[Double]()
        
        // We need enough data to calculate DX values
        val windowSize = period + 1
        for (i <- windowSize until candles.length) {
            val windowCandles = candles.slice(i - windowSize, i + 1)
            val dmiResult = calculateDMI(windowCandles, period)
            
            val dx = if (dmiResult.plusDI + dmiResult.minusDI != 0) {
                math.abs(dmiResult.plusDI - dmiResult.minusDI) / (dmiResult.plusDI + dmiResult.minusDI) * 100
            } else 0.0
            
            dxValues += dx
        }
        
        // Return smoothed DX (ADX)
        if (dxValues.nonEmpty) wildersSmoothing(dxValues.toList, period) else 25.0
    }
    
    // Convenience method to get all trend indicators at once
    def doTrendAnalysis(candles: List[Candle], period: Int = 14, shortPeriod: Int = 9, longPeriod: Int = 21): TrendAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        val sma = simpleMovingAverage(candles, period)
        val ema = exponentialMovingAverage(candles, period)
        val tma = triangularMovingAverage(candles, period)
        val shortTermMA = exponentialMovingAverage(candles, shortPeriod)
        val longTermMA = exponentialMovingAverage(candles, longPeriod)
        val dmi = calculateDMI(candles, period)
        val adx = calculateADX(candles, period)
        
        TrendAnalysis(
            sma = sma,
            ema = ema,
            tma = tma,
            shortTermMA = shortTermMA,
            longTermMA = longTermMA,
            plusDI = dmi.plusDI,
            minusDI = dmi.minusDI,
            adx = adx
        )
    }

    
    // Calculate trend reversal signals with enhanced multi-period analysis
    def trendReversalSignal(recentTrendAnalysis: List[TrendAnalysis], currentPrice: Double, lookbackPeriods: Int = 5): Double = {
        require(recentTrendAnalysis.nonEmpty, "TrendAnalysis list cannot be empty")
        require(lookbackPeriods > 1, "Lookback periods must be greater than 1")
        
        // Basic fallback for insufficient data
        if (recentTrendAnalysis.size < 2) return 0.0
        
        // Use basic 2-point analysis if we don't have enough data for enhanced analysis
        if (recentTrendAnalysis.size < lookbackPeriods) {
            return basicTrendReversalSignal(recentTrendAnalysis, currentPrice)
        }
        
        val relevantData = recentTrendAnalysis.takeRight(lookbackPeriods)
        var totalReversalScore = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Trend consistency analysis
        val directions = relevantData.map(_.direction)
        val directionChanges = directions.sliding(2).count { 
            case Seq(prev, curr) => prev != curr
            case _ => false
        }
        val directionChangeRatio = directionChanges.toDouble / (directions.size - 1)
        
        if (directionChangeRatio > 0.3) { // More than 30% direction changes
            totalReversalScore += 0.25
        }
        maxPossibleScore += 0.25
        
        // 2. Moving average convergence/divergence
        val emaSmaDifferences = relevantData.map(ta => math.abs(ta.ema - ta.sma))
        val convergingMAs = emaSmaDifferences.sliding(2).forall { 
            case Seq(prev, curr) => curr < prev // MAs are getting closer
            case _ => false
        }
        
        if (convergingMAs) {
            totalReversalScore += 0.2
        }
        maxPossibleScore += 0.2
        
        // 3. ADX trend strength analysis
        val adxValues = relevantData.map(_.adx)
        val adxTrend = if (adxValues.size >= 3) {
            val recent = adxValues.takeRight(3)
            if (recent.head > recent.last && recent.head > 30) "Weakening" // Strong trend getting weaker
            else if (recent.last > recent.head && recent.last > 25) "Strengthening" // Trend getting stronger
            else "Stable"
        } else "Unknown"
        
        adxTrend match {
            case "Weakening" => totalReversalScore += 0.3 // Strong signal
            case "Strengthening" => totalReversalScore += 0.1 // Weak reversal signal
            case _ => // No additional score
        }
        maxPossibleScore += 0.3
        
        // 4. Combine with basic reversal signal
        val basicSignal = basicTrendReversalSignal(relevantData.takeRight(2), currentPrice)
        totalReversalScore += basicSignal * 0.25
        maxPossibleScore += 0.25
        
        // Normalize and apply confidence boost for stronger signals
        val normalizedScore = if (maxPossibleScore > 0) totalReversalScore / maxPossibleScore else 0.0
        
        // Apply sigmoid-like transformation to make strong signals more pronounced
        val enhancedScore = normalizedScore * normalizedScore * (3.0 - 2.0 * normalizedScore)
        
        math.min(enhancedScore, 1.0)
    }
    
    // Helper method for basic 2-point trend reversal analysis (used internally)
    private def basicTrendReversalSignal(recentTrendAnalysis: List[TrendAnalysis], currentPrice: Double): Double = {
        require(recentTrendAnalysis.nonEmpty, "TrendAnalysis list cannot be empty")
        
        // Need at least 2 data points to detect changes
        if (recentTrendAnalysis.size < 2) return 0.0
        
        val current = recentTrendAnalysis.last
        val previous = recentTrendAnalysis(recentTrendAnalysis.size - 2)
        
        var reversalScore = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Moving Average Crossovers (Short-term vs Long-term MA)
        val currentGoldenCross = current.isGoldenCross
        val previousGoldenCross = previous.isGoldenCross
        
        if (currentGoldenCross != previousGoldenCross) {
            // MA crossover detected - weight by crossover strength
            val crossoverStrength = math.min(current.maCrossoverStrength, 0.05) / 0.05 // Normalize to 0-1
            val crossoverSignal = 0.35 * crossoverStrength // Higher weight for MA crossovers
            reversalScore += crossoverSignal
        }
        maxPossibleScore += 0.35
        
        // 2. Price relationship to moving averages (use short-term MA as primary reference)
        val currentPriceAboveShortMA = currentPrice > current.shortTermMA
        val previousPriceAboveShortMA = recentTrendAnalysis.size >= 3 && 
            currentPrice > previous.shortTermMA // Approximate previous price relationship
        
        // Price crossing through MA indicates trend change
        if (recentTrendAnalysis.size >= 3) {
            val priceMARelationshipChanged = currentPriceAboveShortMA != previousPriceAboveShortMA
            if (priceMARelationshipChanged) {
                reversalScore += 0.25
            }
        }
        maxPossibleScore += 0.25
        
        // 3. Directional Movement Index changes (DI crossovers)
        val currentBullishDI = current.plusDI > current.minusDI
        val previousBullishDI = previous.plusDI > previous.minusDI
        
        if (currentBullishDI != previousBullishDI) {
            // DI crossover - stronger signal if ADX is high
            val adxStrength = math.min(current.adx / 50.0, 1.0) // Normalize ADX
            val diCrossoverSignal = 0.2 * adxStrength
            reversalScore += diCrossoverSignal
        }
        maxPossibleScore += 0.2
        
        // 4. Trend strength changes (ADX divergence)
        val adxDifference = current.adx - previous.adx
        if (math.abs(adxDifference) > 5.0) {
            // Significant ADX change indicates potential trend shift
            val adxChangeSignal = math.min(math.abs(adxDifference) / 25.0, 0.15)
            reversalScore += adxChangeSignal
        }
        maxPossibleScore += 0.15
        
        // 5. Multiple MA alignment changes
        val currentMAAlignment = getMAAligment(current)
        val previousMAAlignment = getMAAligment(previous)
        
        if (currentMAAlignment != previousMAAlignment) {
            reversalScore += 0.1
        }
        maxPossibleScore += 0.1
        
        // Normalize the score to 0-1 range
        if (maxPossibleScore > 0) reversalScore / maxPossibleScore else 0.0
    }
    
    // Helper function to determine MA alignment
    private def getMAAligment(analysis: TrendAnalysis): String = {
        val shortMA = analysis.shortTermMA
        val longMA = analysis.longTermMA
        val ema = analysis.ema
        
        if (shortMA > longMA && longMA > ema) "Strong Bullish"
        else if (shortMA > longMA) "Bullish"
        else if (shortMA < longMA && longMA < ema) "Strong Bearish"
        else if (shortMA < longMA) "Bearish"
        else "Mixed"
    }

}
