package bmps.core.services.analysis

import bmps.core.models.Candle
import scala.collection.mutable

// Case class to hold True Range and ATR results
case class TrueRangeAnalysis(
    currentTR: Double,
    atr: Double,
    atrTrend: String, // "Increasing", "Decreasing", "Stable"
    volatilityLevel: String // "Low", "Normal", "High", "Extreme"
) {
    def isHighVolatility: Boolean = volatilityLevel == "High" || volatilityLevel == "Extreme"
    def isLowVolatility: Boolean = volatilityLevel == "Low"
    def isVolatilityIncreasing: Boolean = atrTrend == "Increasing"
    
    // Calculate stop loss and profit target levels
    def getStopLossLevel(entryPrice: Double, isLong: Boolean, atrMultiplier: Double = 2.0): Double = {
        if (isLong) entryPrice - (atr * atrMultiplier)
        else entryPrice + (atr * atrMultiplier)
    }
    
    def getProfitTargetLevel(entryPrice: Double, isLong: Boolean, atrMultiplier: Double = 2.0): Double = {
        if (isLong) entryPrice + (atr * atrMultiplier)
        else entryPrice - (atr * atrMultiplier)
    }
}

// Case class to hold Keltner Channel results
case class KeltnerChannels(
    upperBand: Double,
    centerLine: Double, // Moving average
    lowerBand: Double,
    channelWidth: Double,
    pricePosition: String // "Above", "Below", "Inside"
) {
    def isAboveChannel(price: Double): Boolean = price > upperBand
    def isBelowChannel(price: Double): Boolean = price < lowerBand
    def isInsideChannel(price: Double): Boolean = price >= lowerBand && price <= upperBand
    def isNearUpperBand(price: Double, tolerance: Double = 0.002): Boolean = 
        math.abs(price - upperBand) / upperBand <= tolerance
    def isNearLowerBand(price: Double, tolerance: Double = 0.002): Boolean = 
        math.abs(price - lowerBand) / lowerBand <= tolerance
        
    // Breakout signals
    def isUpperBreakout(price: Double): Boolean = price > upperBand
    def isLowerBreakout(price: Double): Boolean = price < lowerBand
}

// Case class to hold Bollinger Bands results
case class BollingerBands(
    upperBand: Double,
    centerLine: Double, // Simple moving average
    lowerBand: Double,
    bandwidth: Double, // Width between bands
    percentB: Double,  // Price position within bands (0-1)
    pricePosition: String // "Above", "Below", "Inside"
) {
    def isAboveBands(price: Double): Boolean = price > upperBand
    def isBelowBands(price: Double): Boolean = price < lowerBand
    def isInsideBands(price: Double): Boolean = price >= lowerBand && price <= upperBand
    def isOverbought: Boolean = percentB > 0.8
    def isOversold: Boolean = percentB < 0.2
    def isSqueezing: Boolean = bandwidth < 0.1 // Low volatility period
    def isExpanding: Boolean = bandwidth > 0.2 // High volatility period
}

// Case class to hold Standard Deviation analysis
case class StandardDeviationAnalysis(
    mean: Double,
    standardDeviation: Double,
    oneStdDevUpper: Double,
    oneStdDevLower: Double,
    twoStdDevUpper: Double,
    twoStdDevLower: Double,
    priceStdDevLevel: Int // How many std devs from mean (1, 2, 3+)
) {
    def isWithinOneStdDev(price: Double): Boolean = price >= oneStdDevLower && price <= oneStdDevUpper
    def isWithinTwoStdDev(price: Double): Boolean = price >= twoStdDevLower && price <= twoStdDevUpper
    def isAtExtremeLevel(price: Double): Boolean = !isWithinTwoStdDev(price)
    def getReversalProbability: Double = priceStdDevLevel match {
        case 1 => 0.32 // 68% inside 1σ, so 32% outside
        case 2 => 0.05 // 95% inside 2σ, so 5% outside  
        case x if x >= 3 => 0.01 // 99.7% inside 3σ, so 0.3% outside
        case _ => 0.0
    }
}

// Case class to hold complete volatility analysis results
case class VolatilityAnalysis(
    trueRange: TrueRangeAnalysis,
    keltnerChannels: KeltnerChannels,
    bollingerBands: BollingerBands,
    standardDeviation: StandardDeviationAnalysis
) {
    def overallVolatility: String = {
        val indicators = List(
            trueRange.volatilityLevel,
            if (keltnerChannels.channelWidth > 0.02) "High" else "Normal",
            if (bollingerBands.bandwidth > 0.2) "High" else if (bollingerBands.bandwidth < 0.1) "Low" else "Normal"
        )
        
        val highCount = indicators.count(_ == "High")
        val lowCount = indicators.count(_ == "Low")
        
        if (highCount >= 2) "High"
        else if (lowCount >= 2) "Low"
        else "Normal"
    }
    
    def breakoutPotential: String = {
        val currentPrice = (keltnerChannels.upperBand + keltnerChannels.lowerBand) / 2 // Approximate current price
        
        if (bollingerBands.isSqueezing && trueRange.isLowVolatility) "High"
        else if (keltnerChannels.isNearUpperBand(currentPrice) || keltnerChannels.isNearLowerBand(currentPrice)) "Medium"
        else "Low"
    }
    
    def reversalProbability: String = {
        val sdProb = standardDeviation.getReversalProbability
        if (sdProb >= 0.05) "High"
        else if (sdProb >= 0.01) "Medium" 
        else "Low"
    }
}

class Volatility() {
    
    // Calculate True Range for a single candle
    private def calculateTrueRange(current: Candle, previous: Candle): Double = {
        val tr1 = current.high - current.low
        val tr2 = math.abs(current.high - previous.close)
        val tr3 = math.abs(current.low - previous.close)
        math.max(tr1, math.max(tr2, tr3)).toDouble
    }
    
    // Calculate True Range analysis including ATR
    def calculateTrueRangeAnalysis(candles: List[Candle], period: Int = 14): TrueRangeAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        require(candles.size >= 2, "Need at least 2 candles for TR calculation")
        
        if (candles.size < period + 1) {
            // Insufficient data - return basic analysis
            val current = candles.last
            val previous = if (candles.size >= 2) candles(candles.size - 2) else current
            val tr = calculateTrueRange(current, previous)
            return TrueRangeAnalysis(tr, tr, "Stable", "Normal")
        }
        
        // Calculate True Range for each period
        val trueRanges = mutable.ListBuffer[Double]()
        for (i <- 1 until candles.length) {
            val tr = calculateTrueRange(candles(i), candles(i - 1))
            trueRanges += tr
        }
        
        // Calculate ATR using Wilder's smoothing
        val atr = wildersSmoothing(trueRanges.toList, period)
        val currentTR = trueRanges.last
        
        // Determine ATR trend
        val atrTrend = if (trueRanges.size >= period * 2) {
            val recentATR = wildersSmoothing(trueRanges.takeRight(period).toList, period)
            val olderATR = wildersSmoothing(trueRanges.dropRight(period/2).takeRight(period).toList, period)
            
            if (recentATR > olderATR * 1.1) "Increasing"
            else if (recentATR < olderATR * 0.9) "Decreasing"
            else "Stable"
        } else "Stable"
        
        // Determine volatility level based on current TR vs ATR
        val volatilityRatio = currentTR / atr
        val volatilityLevel = volatilityRatio match {
            case x if x > 2.0 => "Extreme"
            case x if x > 1.5 => "High"
            case x if x < 0.5 => "Low"
            case _ => "Normal"
        }
        
        TrueRangeAnalysis(currentTR, atr, atrTrend, volatilityLevel)
    }
    
    // Calculate Keltner Channels
    def calculateKeltnerChannels(candles: List[Candle], period: Int = 20, atrMultiplier: Double = 1.5): KeltnerChannels = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        
        if (candles.size < period) {
            val price = candles.last.close.toDouble
            return KeltnerChannels(price * 1.02, price, price * 0.98, 0.04, "Inside")
        }
        
        // Calculate center line (typically EMA, but using SMA for simplicity)
        val recentCandles = candles.takeRight(period)
        val centerLine = recentCandles.map(_.close.toDouble).sum / recentCandles.size
        
        // Calculate ATR for the same period
        val trAnalysis = calculateTrueRangeAnalysis(candles, math.min(14, period))
        val atr = trAnalysis.atr
        
        // Calculate bands
        val upperBand = centerLine + (atr * atrMultiplier)
        val lowerBand = centerLine - (atr * atrMultiplier)
        val channelWidth = (upperBand - lowerBand) / centerLine
        
        // Determine current price position
        val currentPrice = candles.last.close.toDouble
        val pricePosition = if (currentPrice > upperBand) "Above"
                           else if (currentPrice < lowerBand) "Below"
                           else "Inside"
        
        KeltnerChannels(upperBand, centerLine, lowerBand, channelWidth, pricePosition)
    }
    
    // Calculate Bollinger Bands
    def calculateBollingerBands(candles: List[Candle], period: Int = 20, stdDevMultiplier: Double = 2.0): BollingerBands = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        
        if (candles.size < period) {
            val price = candles.last.close.toDouble
            return BollingerBands(price * 1.02, price, price * 0.98, 0.04, 0.5, "Inside")
        }
        
        val recentCandles = candles.takeRight(period)
        val prices = recentCandles.map(_.close.toDouble)
        
        // Calculate SMA (center line)
        val sma = prices.sum / prices.size
        
        // Calculate standard deviation
        val variance = prices.map(price => math.pow(price - sma, 2)).sum / prices.size
        val stdDev = math.sqrt(variance)
        
        // Calculate bands
        val upperBand = sma + (stdDev * stdDevMultiplier)
        val lowerBand = sma - (stdDev * stdDevMultiplier)
        val bandwidth = (upperBand - lowerBand) / sma
        
        // Calculate %B (price position within bands)
        val currentPrice = candles.last.close.toDouble
        val percentB = if (upperBand != lowerBand) (currentPrice - lowerBand) / (upperBand - lowerBand) else 0.5
        
        // Determine price position
        val pricePosition = if (currentPrice > upperBand) "Above"
                           else if (currentPrice < lowerBand) "Below"
                           else "Inside"
        
        BollingerBands(upperBand, sma, lowerBand, bandwidth, percentB, pricePosition)
    }
    
    // Calculate Standard Deviation analysis
    def calculateStandardDeviationAnalysis(candles: List[Candle], period: Int = 20): StandardDeviationAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        
        val recentCandles = candles.takeRight(math.min(period, candles.size))
        val prices = recentCandles.map(_.close.toDouble)
        
        // Calculate mean
        val mean = prices.sum / prices.size
        
        // Calculate standard deviation
        val variance = prices.map(price => math.pow(price - mean, 2)).sum / prices.size
        val stdDev = math.sqrt(variance)
        
        // Calculate standard deviation levels
        val oneStdDevUpper = mean + stdDev
        val oneStdDevLower = mean - stdDev
        val twoStdDevUpper = mean + (2 * stdDev)
        val twoStdDevLower = mean - (2 * stdDev)
        
        // Determine current price's standard deviation level
        val currentPrice = candles.last.close.toDouble
        val priceStdDevLevel = if (currentPrice >= twoStdDevUpper || currentPrice <= twoStdDevLower) 2
                              else if (currentPrice >= oneStdDevUpper || currentPrice <= oneStdDevLower) 1
                              else 0
        
        StandardDeviationAnalysis(mean, stdDev, oneStdDevUpper, oneStdDevLower, 
                                 twoStdDevUpper, twoStdDevLower, priceStdDevLevel)
    }
    
    // Wilder's smoothing function (reused from other analysis)
    private def wildersSmoothing(values: List[Double], period: Int): Double = {
        if (values.size < period) return if (values.nonEmpty) values.sum / values.size else 0.0
        
        val initial = values.take(period).sum / period
        values.drop(period).foldLeft(initial) { (smoothed, value) =>
            ((smoothed * (period - 1)) + value) / period
        }
    }
    
    // Main volatility analysis function
    def doVolatilityAnalysis(
        candles: List[Candle], 
        atrPeriod: Int = 14, 
        keltnerPeriod: Int = 20, 
        keltnerMultiplier: Double = 1.5,
        bollingerPeriod: Int = 20, 
        bollingerMultiplier: Double = 2.0,
        stdDevPeriod: Int = 20
    ): VolatilityAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        val trueRange = calculateTrueRangeAnalysis(candles, atrPeriod)
        val keltnerChannels = calculateKeltnerChannels(candles, keltnerPeriod, keltnerMultiplier)
        val bollingerBands = calculateBollingerBands(candles, bollingerPeriod, bollingerMultiplier)
        val standardDeviation = calculateStandardDeviationAnalysis(candles, stdDevPeriod)
        
        VolatilityAnalysis(trueRange, keltnerChannels, bollingerBands, standardDeviation)
    }
    
    // Calculate volatility-based trading signal score (0.0 to 1.0) with multi-period analysis
    def volatilityTradingSignal(recentVolatilityAnalysis: List[VolatilityAnalysis], 
                               currentPrice: Double, 
                               lookbackPeriods: Int = 5): Double = {
        require(recentVolatilityAnalysis.nonEmpty, "Volatility analysis list cannot be empty")
        require(lookbackPeriods > 0, "Lookback periods must be positive")
        
        // If we don't have enough data for enhanced analysis, use basic calculation
        if (recentVolatilityAnalysis.size < lookbackPeriods || lookbackPeriods == 1) {
            return basicVolatilityTradingSignal(recentVolatilityAnalysis, currentPrice)
        }
        
        val relevantData = recentVolatilityAnalysis.takeRight(lookbackPeriods)
        var totalScore = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Volatility regime analysis
        val volatilityRegimes = relevantData.map(_.overallVolatility)
        val lowVolCount = volatilityRegimes.count(_ == "Low")
        val highVolCount = volatilityRegimes.count(_ == "High")
        
        if (lowVolCount >= lookbackPeriods * 0.6) {
            totalScore += 0.3 // Extended low volatility = high breakout potential
        } else if (highVolCount >= lookbackPeriods * 0.6) {
            totalScore += 0.1 // Extended high volatility = potential exhaustion
        }
        maxPossibleScore += 0.3
        
        // 2. Band squeeze patterns
        val squeezingCount = relevantData.count(_.bollingerBands.isSqueezing)
        if (squeezingCount >= 2) {
            totalScore += 0.25
        }
        maxPossibleScore += 0.25
        
        // 3. Extreme level reversions
        val extremeLevels = relevantData.count(_.standardDeviation.priceStdDevLevel >= 2)
        if (extremeLevels >= 1) {
            totalScore += 0.2
        }
        maxPossibleScore += 0.2
        
        // 4. Combine with basic signal
        val basicSignal = basicVolatilityTradingSignal(relevantData.takeRight(1), currentPrice)
        totalScore += basicSignal * 0.25
        maxPossibleScore += 0.25
        
        // Normalize
        val normalizedScore = if (maxPossibleScore > 0) totalScore / maxPossibleScore else 0.0
        
        // Apply enhancement
        val enhancedScore = normalizedScore * normalizedScore * (3.0 - 2.0 * normalizedScore)
        
        math.min(enhancedScore, 1.0)
    }
    
    // Basic volatility trading signal calculation (used internally and as fallback)
    private def basicVolatilityTradingSignal(recentVolatilityAnalysis: List[VolatilityAnalysis], currentPrice: Double): Double = {
        require(recentVolatilityAnalysis.nonEmpty, "Volatility analysis list cannot be empty")
        
        val current = recentVolatilityAnalysis.last
        var score = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Volatility contraction signals (potential breakout setup) - Weight: 0.3
        if (current.bollingerBands.isSqueezing) score += 0.1
        if (current.trueRange.isLowVolatility) score += 0.1
        if (current.breakoutPotential == "High") score += 0.1
        maxPossibleScore += 0.3
        
        // 2. Mean reversion signals (extreme levels) - Weight: 0.25
        if (current.standardDeviation.priceStdDevLevel >= 2) score += 0.15
        if (current.bollingerBands.isOverbought || current.bollingerBands.isOversold) score += 0.1
        maxPossibleScore += 0.25
        
        // 3. Breakout confirmation signals - Weight: 0.25
        if (current.keltnerChannels.isAboveChannel(currentPrice) && current.trueRange.isVolatilityIncreasing) score += 0.125
        if (current.keltnerChannels.isBelowChannel(currentPrice) && current.trueRange.isVolatilityIncreasing) score += 0.125
        maxPossibleScore += 0.25
        
        // 4. Channel position analysis - Weight: 0.2
        if (current.keltnerChannels.isInsideChannel(currentPrice)) score += 0.1 // Good for range trading
        if (current.bollingerBands.isInsideBands(currentPrice)) score += 0.1 // Normal volatility environment
        maxPossibleScore += 0.2
        
        // Normalize the score
        if (maxPossibleScore > 0) score / maxPossibleScore else 0.0
    }
}


