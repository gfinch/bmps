package bmps.core.services

import bmps.core.models.{Candle, Direction}
import scala.math.{abs, sqrt}

object MarketTrendService {

  case class TrendAnalysis(
    direction: Direction,
    strength: Double, // 0.0 to 1.0
    momentum: Double, // positive = up momentum, negative = down momentum
    volatility: Double,
    confidence: Double // 0.0 to 1.0
  )

  /**
   * Primary interface: Determines market direction from trading candles
   * @param tradingCandles List of 1-minute candles (ideally several days worth)
   * @return Direction.Up or Direction.Down
   */
  def determineDirection(tradingCandles: List[Candle]): Direction = {
    if (tradingCandles.isEmpty) return Direction.Up
    
    val analysis = analyzeMultiTimeframeTrend(tradingCandles)
    analysis.direction
  }

  /**
   * Comprehensive trend analysis using multiple timeframes
   */
  private def analyzeMultiTimeframeTrend(candles: List[Candle]): TrendAnalysis = {
    // Use only the most recent 3 hours (180 minutes) for analysis
    val recentCandles = candles.takeRight(180)
    
    if (recentCandles.length < 30) {
      // Not enough data, return default
      return TrendAnalysis(Direction.Up, 0.5, 0.0, 0.0, 0.3)
    }

    // Multi-timeframe analysis - moderately more responsive
    val longTerm = analyzeTrendWindow(recentCandles, 75)  // 1.25 hours (was 1.5h)
    val mediumTerm = analyzeTrendWindow(recentCandles, 35) // 35 minutes (was 45m)  
    val shortTerm = analyzeTrendWindow(recentCandles, 15)  // 15 minutes (was 20m)

    // Weighted scoring (more balanced between timeframes)
    val longWeight = 0.4   // Further reduced from 0.45
    val mediumWeight = 0.4 // Increased from 0.35
    val shortWeight = 0.2  // Same

    val weightedMomentum = 
      longTerm.momentum * longWeight +
      mediumTerm.momentum * mediumWeight +
      shortTerm.momentum * shortWeight

    val weightedStrength = 
      longTerm.strength * longWeight +
      mediumTerm.strength * mediumWeight +
      shortTerm.strength * shortWeight

    // Confidence based on timeframe agreement
    val agreement = calculateTimeframeAgreement(longTerm, mediumTerm, shortTerm)
    val confidence = (agreement + weightedStrength) / 2.0

    // Apply stability threshold - require meaningful momentum to change direction
    val momentumThreshold = 0.06 // More sensitive (was 0.08)
    val direction = if (abs(weightedMomentum) < momentumThreshold) {
      // Weak momentum, check if we have reasonable agreement
      if (confidence > 0.6) { // Less strict (was 0.65)
        if (weightedMomentum >= 0) Direction.Up else Direction.Down
      } else {
        Direction.Up // Default when uncertain
      }
    } else {
      if (weightedMomentum > 0) Direction.Up else Direction.Down
    }

    val avgVolatility = (longTerm.volatility + mediumTerm.volatility + shortTerm.volatility) / 3.0

    TrendAnalysis(
      direction = direction,
      strength = weightedStrength,
      momentum = weightedMomentum,
      volatility = avgVolatility,
      confidence = confidence
    )
  }

  /**
   * Analyze trend for a specific time window
   */
  private def analyzeTrendWindow(candles: List[Candle], windowSize: Int): TrendAnalysis = {
    val windowCandles = candles.takeRight(windowSize)
    
    if (windowCandles.length < 10) {
      return TrendAnalysis(Direction.Up, 0.0, 0.0, 0.0, 0.0)
    }

    val closes = windowCandles.map(_.close.value.toDouble)
    
    // Linear regression for trend slope
    val momentum = calculateLinearRegressionSlope(closes)
    
    // Trend strength based on consistency
    val strength = calculateTrendStrength(windowCandles)
    
    // Volatility measurement
    val volatility = calculateVolatility(closes)
    
    // Direction determination
    val direction = if (momentum > 0) Direction.Up else Direction.Down
    
    // Confidence based on strength and consistency
    val confidence = math.min(1.0, strength * (1.0 - volatility))

    TrendAnalysis(
      direction = direction,
      strength = strength,
      momentum = momentum,
      volatility = volatility,
      confidence = confidence
    )
  }

  /**
   * Calculate linear regression slope for trend momentum
   */
  private def calculateLinearRegressionSlope(prices: List[Double]): Double = {
    if (prices.length < 2) return 0.0
    
    val n = prices.length.toDouble
    val x = (0 until prices.length).map(_.toDouble)
    val y = prices
    
    val sumX = x.sum
    val sumY = y.sum
    val sumXY = x.zip(y).map { case (xi, yi) => xi * yi }.sum
    val sumX2 = x.map(xi => xi * xi).sum
    
    val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    
    // Normalize slope relative to price level
    val avgPrice = sumY / n
    slope / avgPrice
  }

  /**
   * Calculate trend strength based on higher highs/lower lows pattern
   */
  private def calculateTrendStrength(candles: List[Candle]): Double = {
    if (candles.length < 5) return 0.0
    
    val highs = candles.map(_.high)
    val lows = candles.map(_.low)
    
    // Count progressive highs and lows
    var higherHighs = 0
    var lowerLows = 0
    var totalComparisons = 0
    
    for (i <- 1 until candles.length by 2) { // Sample every other candle
      if (i + 2 < candles.length) {
        val prevHigh = highs(i)
        val currHigh = highs(i + 2)
        val prevLow = lows(i)
        val currLow = lows(i + 2)
        
        if (currHigh.value > prevHigh.value) higherHighs += 1
        if (currLow.value < prevLow.value) lowerLows += 1
        totalComparisons += 1
      }
    }
    
    if (totalComparisons == 0) return 0.0
    
    // Strength is the dominance of one pattern over the other
    val upStrength = higherHighs.toDouble / totalComparisons
    val downStrength = lowerLows.toDouble / totalComparisons
    
    math.max(upStrength, downStrength)
  }

  /**
   * Calculate price volatility (normalized standard deviation)
   */
  private def calculateVolatility(prices: List[Double]): Double = {
    if (prices.length < 2) return 0.0
    
    val mean = prices.sum / prices.length
    val variance = prices.map(p => math.pow(p - mean, 2)).sum / prices.length
    val stdDev = sqrt(variance)
    
    // Normalize by mean price
    stdDev / mean
  }

  /**
   * Calculate agreement between different timeframe analyses
   */
  private def calculateTimeframeAgreement(long: TrendAnalysis, medium: TrendAnalysis, short: TrendAnalysis): Double = {
    val directions = List(long.direction, medium.direction, short.direction)
    val upCount = directions.count(_ == Direction.Up)
    val agreement = math.max(upCount, directions.length - upCount).toDouble / directions.length
    
    // Bonus for momentum alignment
    val momentums = List(long.momentum, medium.momentum, short.momentum)
    val momentumAlignment = if (momentums.forall(_ > 0) || momentums.forall(_ < 0)) 0.2 else 0.0
    
    math.min(1.0, agreement + momentumAlignment)
  }
}