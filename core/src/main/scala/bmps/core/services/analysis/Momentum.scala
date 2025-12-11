package bmps.core.services.analysis

import bmps.core.models.Candle
import scala.collection.mutable

// Case class to hold Stochastics results
case class StochasticsResult(percentK: Double, percentD: Double) {
    def isOverbought: Boolean = percentK > 80.0 || percentD > 80.0
    def isOversold: Boolean = percentK < 20.0 || percentD < 20.0
    def isBullishCrossover: Boolean = percentK > percentD
    def crossoverStrength: Double = math.abs(percentK - percentD) / 100.0
}

// Case class to hold complete momentum analysis results
case class MomentumAnalysis(
    rsi: Double,
    stochastics: StochasticsResult,
    williamsR: Double,
    cci: Double
) {
    // RSI interpretation
    def rsiOverbought: Boolean = rsi > 70.0
    def rsiOversold: Boolean = rsi < 30.0
    def rsiNeutral: Boolean = rsi >= 30.0 && rsi <= 70.0

    lazy val iRsi: Double = 100.0 - rsi
    
    // Williams %R interpretation (inverted scale: 0 to -100)
    def williamsROverbought: Boolean = williamsR > -20.0
    def williamsROversold: Boolean = williamsR < -80.0
    
    // CCI interpretation
    def cciOverbought: Boolean = cci > 100.0
    def cciOversold: Boolean = cci < -100.0
    def cciNeutral: Boolean = cci >= -100.0 && cci <= 100.0
    
    // Overall momentum assessment
    def overallMomentum: String = {
        val bullishSignals = List(
            rsiOversold, 
            stochastics.isOversold, 
            williamsROversold, 
            cciOversold
        ).count(identity)
        
        val bearishSignals = List(
            rsiOverbought, 
            stochastics.isOverbought, 
            williamsROverbought, 
            cciOverbought
        ).count(identity)
        
        if (bullishSignals >= 2) "Oversold"
        else if (bearishSignals >= 2) "Overbought"
        else "Neutral"
    }
}

class Momentum() {
    
    // Calculate RSI (Relative Strength Index)
    def calculateRSI(candles: List[Candle], period: Int = 14): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        require(candles.size >= 2, "Need at least 2 candles for RSI calculation")
        
        // If we don't have enough data, return neutral RSI
        if (candles.size < period + 1) {
            return 50.0 // Neutral RSI
        }
        
        // Calculate price changes
        val priceChanges = candles.sliding(2).map { 
            case List(prev, curr) => curr.close - prev.close
            case _ => 0.0f
        }.toList
        
        // Separate gains and losses
        val gains = priceChanges.map(change => if (change > 0) change.toDouble else 0.0)
        val losses = priceChanges.map(change => if (change < 0) math.abs(change.toDouble) else 0.0)
        
        // Calculate average gain and loss using Wilder's smoothing
        val avgGain = wildersSmoothing(gains, period)
        val avgLoss = wildersSmoothing(losses, period)
        
        // Calculate RSI
        if (avgLoss == 0.0) 100.0
        else {
            val rs = avgGain / avgLoss
            100.0 - (100.0 / (1.0 + rs))
        }
    }
    
    // Calculate Stochastics (%K and %D)
    def calculateStochastics(candles: List[Candle], kPeriod: Int = 14, dPeriod: Int = 3): StochasticsResult = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(kPeriod > 0, "K period must be positive")
        require(dPeriod > 0, "D period must be positive")
        
        // If we don't have enough data, return neutral values
        if (candles.size < kPeriod) {
            return StochasticsResult(percentK = 50.0, percentD = 50.0)
        }
        
        // Calculate %K values
        val kValues = candles.sliding(kPeriod).map { window =>
            val highs = window.map(_.high.toDouble)
            val lows = window.map(_.low.toDouble)
            val currentClose = window.last.close.toDouble
            
            val highestHigh = highs.max
            val lowestLow = lows.min
            
            if (highestHigh == lowestLow) 50.0
            else ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100.0
        }.toList
        
        // Current %K
        val percentK = kValues.last
        
        // Calculate %D (SMA of %K values)
        val percentD = if (kValues.size >= dPeriod) {
            kValues.takeRight(dPeriod).sum / dPeriod
        } else {
            kValues.sum / kValues.size
        }
        
        StochasticsResult(percentK, percentD)
    }
    
    // Calculate Williams %R
    def calculateWilliamsR(candles: List[Candle], period: Int = 14): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        
        // If we don't have enough data, return neutral Williams %R
        if (candles.size < period) {
            return -50.0 // Neutral Williams %R
        }
        
        val recentCandles = candles.takeRight(period)
        val highs = recentCandles.map(_.high.toDouble)
        val lows = recentCandles.map(_.low.toDouble)
        val currentClose = candles.last.close.toDouble
        
        val highestHigh = highs.max
        val lowestLow = lows.min
        
        if (highestHigh == lowestLow) -50.0
        else ((highestHigh - currentClose) / (highestHigh - lowestLow)) * -100.0
    }
    
    // Calculate CCI (Commodity Channel Index)
    def calculateCCI(candles: List[Candle], period: Int = 20): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(period > 0, "Period must be positive")
        
        // If we don't have enough data, return neutral CCI
        if (candles.size < period) {
            return 0.0 // Neutral CCI
        }
        
        val recentCandles = candles.takeRight(period)
        
        // Calculate typical prices
        val typicalPrices = recentCandles.map { candle =>
            (candle.high + candle.low + candle.close).toDouble / 3.0
        }
        
        // Calculate SMA of typical prices
        val smaTypicalPrice = typicalPrices.sum / typicalPrices.size
        
        // Calculate mean deviation
        val meanDeviation = typicalPrices.map(tp => math.abs(tp - smaTypicalPrice)).sum / typicalPrices.size
        
        // Current typical price
        val currentTypicalPrice = typicalPrices.last
        
        // Calculate CCI
        if (meanDeviation == 0.0) 0.0
        else (currentTypicalPrice - smaTypicalPrice) / (0.015 * meanDeviation)
    }
    
    // Wilder's smoothing function (same as in Trend.scala)
    private def wildersSmoothing(values: List[Double], period: Int): Double = {
        if (values.size < period) return if (values.nonEmpty) values.sum / values.size else 0.0
        
        val initial = values.take(period).sum / period
        values.drop(period).foldLeft(initial) { (smoothed, value) =>
            ((smoothed * (period - 1)) + value) / period
        }
    }
    
    // Main momentum analysis function
    def doMomentumAnalysis(candles: List[Candle], rsiPeriod: Int = 14, stochKPeriod: Int = 14, 
                          stochDPeriod: Int = 3, williamsRPeriod: Int = 14, cciPeriod: Int = 20): MomentumAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        val rsi = calculateRSI(candles, rsiPeriod)
        val stochastics = calculateStochastics(candles, stochKPeriod, stochDPeriod)
        val williamsR = calculateWilliamsR(candles, williamsRPeriod)
        val cci = calculateCCI(candles, cciPeriod)
        
        MomentumAnalysis(rsi, stochastics, williamsR, cci)
    }
    
    // Calculate buying opportunity score (0.0 to 1.0) with multi-period analysis
    def buyingOpportunityScore(recentMomentumAnalysis: List[MomentumAnalysis], 
                              lookbackPeriods: Int = 5): Double = {
        require(recentMomentumAnalysis.nonEmpty, "Momentum analysis list cannot be empty")
        require(lookbackPeriods > 0, "Lookback periods must be positive")
        
        // If we don't have enough data for enhanced analysis, use basic calculation
        if (recentMomentumAnalysis.size < lookbackPeriods || lookbackPeriods == 1) {
            return basicBuyingOpportunityScore(recentMomentumAnalysis)
        }
        
        val relevantData = recentMomentumAnalysis.takeRight(lookbackPeriods)
        var totalScore = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Sustained oversold conditions (contrarian buying opportunity)
        val oversoldCount = relevantData.count(_.overallMomentum == "Oversold")
        if (oversoldCount >= lookbackPeriods * 0.6) { // 60% or more oversold periods
            totalScore += 0.3
        }
        maxPossibleScore += 0.3
        
        // 2. RSI divergence patterns
        val rsiValues = relevantData.map(_.rsi)
        val rsiTrend = if (rsiValues.size >= 3) {
            val recent = rsiValues.takeRight(3)
            if (recent.head < 35 && recent.last > recent.head) "Recovering from oversold"
            else if (recent.last < recent.head && recent.head > 65) "Declining from overbought"
            else "Stable"
        } else "Unknown"
        
        rsiTrend match {
            case "Recovering from oversold" => totalScore += 0.25
            case "Declining from overbought" => totalScore += 0.1
            case _ => // No additional score
        }
        maxPossibleScore += 0.25
        
        // 3. Stochastic patterns
        val stochCrossovers = relevantData.sliding(2).count { 
            case List(prev, curr) => curr.stochastics.isBullishCrossover && !prev.stochastics.isBullishCrossover
            case _ => false
        }
        if (stochCrossovers > 0) {
            totalScore += math.min(stochCrossovers * 0.1, 0.2)
        }
        maxPossibleScore += 0.2
        
        // 4. Combine with basic score
        val basicScore = basicBuyingOpportunityScore(relevantData.takeRight(1))
        totalScore += basicScore * 0.25
        maxPossibleScore += 0.25
        
        // Normalize
        val normalizedScore = if (maxPossibleScore > 0) totalScore / maxPossibleScore else 0.0
        
        // Apply enhancement
        val enhancedScore = normalizedScore * normalizedScore * (3.0 - 2.0 * normalizedScore)
        
        math.min(enhancedScore, 1.0)
    }
    
    // Basic buying opportunity calculation (used internally and as fallback)
    private def basicBuyingOpportunityScore(recentMomentumAnalysis: List[MomentumAnalysis]): Double = {
        require(recentMomentumAnalysis.nonEmpty, "Momentum analysis list cannot be empty")
        
        val current = recentMomentumAnalysis.last
        var score = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Oversold conditions (bullish for buying) - Weight: 0.4
        val oversoldSignals = List(
            current.rsiOversold -> 0.15,      // RSI oversold
            current.stochastics.isOversold -> 0.1,  // Stochastics oversold
            current.williamsROversold -> 0.05,      // Williams %R oversold
            current.cciOversold -> 0.1              // CCI oversold
        )
        
        oversoldSignals.foreach { case (condition, weight) =>
            if (condition) score += weight
            maxPossibleScore += weight
        }
        
        // 2. Momentum turning points - Weight: 0.3
        if (recentMomentumAnalysis.size >= 2) {
            val previous = recentMomentumAnalysis(recentMomentumAnalysis.size - 2)
            
            // RSI turning up from oversold
            if (current.rsi > previous.rsi && previous.rsiOversold) {
                score += 0.1
            }
            
            // Stochastics bullish crossover in oversold territory
            if (current.stochastics.isBullishCrossover && current.stochastics.isOversold) {
                score += 0.1 * current.stochastics.crossoverStrength
            }
            
            // CCI turning up from oversold
            if (current.cci > previous.cci && previous.cciOversold) {
                score += 0.1
            }
            
            maxPossibleScore += 0.3
        } else {
            maxPossibleScore += 0.3
        }
        
        // 3. Avoid overbought conditions (negative for buying) - Weight: 0.2
        val overboughtPenalties = List(
            current.rsiOverbought -> 0.1,
            current.stochastics.isOverbought -> 0.05,
            current.williamsROverbought -> 0.025,
            current.cciOverbought -> 0.025
        )
        
        overboughtPenalties.foreach { case (condition, penalty) =>
            if (!condition) score += penalty // Add score if NOT overbought
            maxPossibleScore += penalty
        }
        
        // 4. Overall momentum assessment - Weight: 0.1
        current.overallMomentum match {
            case "Oversold" => score += 0.1
            case "Neutral" => score += 0.05
            case "Overbought" => // No additional score
        }
        maxPossibleScore += 0.1
        
        // Normalize and apply enhancement for strong signals
        val normalizedScore = if (maxPossibleScore > 0) score / maxPossibleScore else 0.0
        
        // Apply sigmoid-like enhancement for strong buying signals
        val enhancedScore = normalizedScore * normalizedScore * (3.0 - 2.0 * normalizedScore)
        
        math.min(enhancedScore, 1.0)
    }
}


