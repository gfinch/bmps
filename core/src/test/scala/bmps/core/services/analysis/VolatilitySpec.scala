package bmps.core.services.analysis

import bmps.core.models.{Candle, CandleDuration}
import bmps.core.services.analysis._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VolatilitySpec extends AnyFlatSpec with Matchers {

  // Helper function to create test candles
  private def createCandle(open: Double, high: Double, low: Double, close: Double, volume: Long = 1000L, timestamp: Long = System.currentTimeMillis()): Candle = {
    Candle(
      open = open.toFloat,
      high = high.toFloat,
      low = low.toFloat,
      close = close.toFloat,
      volume = volume,
      timestamp = timestamp,
      duration = CandleDuration.OneMinute,
      createdAt = timestamp
    )
  }

  // Create high volatility candles (large price swings)
  private def createHighVolatilityCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (i % 3 - 1) * 5.0 // Creates larger price swings
      val range = 8.0 // Larger ranges for high volatility
      createCandle(
        open = basePrice,
        high = basePrice + range,
        low = basePrice - range,
        close = basePrice + (math.random() - 0.5) * range,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create low volatility candles (small price movements)
  private def createLowVolatilityCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (i * 0.05) // Very small gradual movement
      val range = 0.1 // Very small ranges for low volatility
      createCandle(
        open = basePrice,
        high = basePrice + range,
        low = basePrice - range,
        close = basePrice + (math.random() - 0.5) * range,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create squeeze pattern (decreasing volatility)
  private def createSqueezeCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0
      val range = math.max(2.0 - (i * 0.1), 0.1) // Decreasing volatility
      createCandle(
        open = basePrice,
        high = basePrice + range,
        low = basePrice - range,
        close = basePrice + (math.random() - 0.5) * range * 0.5,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create trending with gaps (for True Range testing)
  private def createGappingCandles(periods: Int = 15): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (i * 0.5)
      val gap = if (i % 5 == 0) 1.0 else 0.0 // Gaps every 5th candle
      createCandle(
        open = basePrice + gap,
        high = basePrice + gap + 0.5,
        low = basePrice + gap - 0.3,
        close = basePrice + gap + 0.2,
        timestamp = i * 60000L
      )
    }.toList
  }

  val volatilityService = new Volatility()

  "Volatility Analysis" should "calculate True Range correctly" in {
    val gappingCandles = createGappingCandles(15)
    val trAnalysis = volatilityService.calculateTrueRangeAnalysis(gappingCandles, 10)
    
    trAnalysis.currentTR should be > 0.0
    trAnalysis.atr should be > 0.0
    trAnalysis.atrTrend should not be empty
    trAnalysis.volatilityLevel should not be empty
  }

  it should "detect high volatility conditions" in {
    val highVolCandles = createHighVolatilityCandles(25)
    val analysis = volatilityService.doVolatilityAnalysis(highVolCandles)
    
    analysis.trueRange.atr should be > 2.0 // Should detect high volatility
    List("High", "Normal").should(contain(analysis.trueRange.volatilityLevel))
  }

  it should "detect low volatility conditions" in {
    val lowVolCandles = createLowVolatilityCandles(25)
    val analysis = volatilityService.doVolatilityAnalysis(lowVolCandles)
    
    analysis.trueRange.atr should be < 1.0 // Should detect low volatility
    List("Low", "Normal").should(contain(analysis.trueRange.volatilityLevel))
  }

  it should "calculate Keltner Channels correctly" in {
    val candles = createHighVolatilityCandles(25)
    val keltner = volatilityService.calculateKeltnerChannels(candles, 20, 1.5)
    
    keltner.upperBand should be > keltner.centerLine
    keltner.lowerBand should be < keltner.centerLine
    keltner.channelWidth should be > 0.0
    keltner.pricePosition should not be empty
  }

  it should "detect channel breakouts with Keltner" in {
    val extremeCandles = createHighVolatilityCandles(20)
    val analysis = volatilityService.doVolatilityAnalysis(extremeCandles)
    val currentPrice = extremeCandles.last.close.toDouble
    
    if (analysis.keltnerChannels.isUpperBreakout(currentPrice)) {
      analysis.keltnerChannels.pricePosition should be("Above")
    } else if (analysis.keltnerChannels.isLowerBreakout(currentPrice)) {
      analysis.keltnerChannels.pricePosition should be("Below")
    }
  }

  it should "calculate Bollinger Bands correctly" in {
    val candles = createSqueezeCandles(25)
    val bollinger = volatilityService.calculateBollingerBands(candles, 20, 2.0)
    
    bollinger.upperBand should be > bollinger.centerLine
    bollinger.lowerBand should be < bollinger.centerLine
    bollinger.bandwidth should be > 0.0
    bollinger.percentB should be >= 0.0
    bollinger.percentB should be <= 1.0
  }

  it should "detect Bollinger Band squeeze" in {
    val squeezeCandles = createSqueezeCandles(25)
    val analysis = volatilityService.doVolatilityAnalysis(squeezeCandles)
    
    // Later candles in squeeze pattern should have lower bandwidth
    analysis.bollingerBands.bandwidth should be > 0.0
    if (analysis.bollingerBands.isSqueezing) {
      analysis.bollingerBands.bandwidth should be < 0.1
    }
  }

  it should "calculate Standard Deviation analysis correctly" in {
    val candles = createHighVolatilityCandles(25)
    val stdDev = volatilityService.calculateStandardDeviationAnalysis(candles, 20)
    
    stdDev.mean should be > 0.0
    stdDev.standardDeviation should be > 0.0
    stdDev.oneStdDevUpper should be > stdDev.mean
    stdDev.oneStdDevLower should be < stdDev.mean
    stdDev.twoStdDevUpper should be > stdDev.oneStdDevUpper
    stdDev.twoStdDevLower should be < stdDev.oneStdDevLower
  }

  it should "detect extreme price levels with Standard Deviation" in {
    val extremeCandles = createHighVolatilityCandles(30)
    val analysis = volatilityService.doVolatilityAnalysis(extremeCandles)
    
    analysis.standardDeviation.priceStdDevLevel should be >= 0
    if (analysis.standardDeviation.isAtExtremeLevel(extremeCandles.last.close.toDouble)) {
      analysis.standardDeviation.priceStdDevLevel should be >= 2
    }
  }

  it should "calculate position sizing levels correctly" in {
    val candles = createGappingCandles(20)
    val analysis = volatilityService.doVolatilityAnalysis(candles)
    val entryPrice = 100.0
    
    val stopLoss = analysis.trueRange.getStopLossLevel(entryPrice, isLong = true, atrMultiplier = 2.0)
    val profitTarget = analysis.trueRange.getProfitTargetLevel(entryPrice, isLong = true, atrMultiplier = 2.0)
    
    stopLoss should be < entryPrice // Stop loss below entry for long position
    profitTarget should be > entryPrice // Profit target above entry for long position
  }

  it should "provide overall volatility assessment" in {
    val highVolCandles = createHighVolatilityCandles(30)
    val lowVolCandles = createLowVolatilityCandles(30)
    
    val highVolAnalysis = volatilityService.doVolatilityAnalysis(highVolCandles)
    val lowVolAnalysis = volatilityService.doVolatilityAnalysis(lowVolCandles)
    
    // Just check that analysis provides some assessment
    highVolAnalysis.overallVolatility should not be empty
    lowVolAnalysis.overallVolatility should not be empty
    // Low volatility should have lower ATR than high volatility
    lowVolAnalysis.trueRange.atr should be < highVolAnalysis.trueRange.atr
  }

  it should "detect breakout potential" in {
    val squeezeCandles = createSqueezeCandles(25)
    val analysis = volatilityService.doVolatilityAnalysis(squeezeCandles)
    
    if (analysis.breakoutPotential == "High") {
      analysis.bollingerBands.isSqueezing should be(true)
      analysis.trueRange.isLowVolatility should be(true)
    }
  }

  it should "provide reasonable volatility trading signals" in {
    val volatilityCandles = createSqueezeCandles(30)
    val volatilityAnalyses = List(volatilityService.doVolatilityAnalysis(volatilityCandles))
    
    val signal = volatilityService.volatilityTradingSignal(volatilityAnalyses, volatilityCandles.last.close.toDouble, 1) // Test basic single-analysis case
    
    signal should be >= 0.0
    signal should be <= 1.0
  }

  it should "enhance volatility signals with multi-period analysis" in {
    val volatilityCandles = createSqueezeCandles(50)
    val recentAnalyses = (1 to 10).map { i =>
      val subCandles = volatilityCandles.take(25 + i)
      volatilityService.doVolatilityAnalysis(subCandles)
    }.toList
    
    val enhancedSignal = volatilityService.volatilityTradingSignal(recentAnalyses, volatilityCandles.last.close.toDouble, 5)
    
    enhancedSignal should be >= 0.0
    enhancedSignal should be <= 1.0
  }

  it should "handle insufficient data gracefully" in {
    val fewCandles = createHighVolatilityCandles(5)
    val analysis = volatilityService.doVolatilityAnalysis(fewCandles)
    
    // Should not throw exceptions and should provide some analysis
    analysis.trueRange.atr should be > 0.0
    analysis.keltnerChannels.channelWidth should be > 0.0
    analysis.bollingerBands.bandwidth should be > 0.0
  }

  it should "calculate reversal probability correctly" in {
    val extremeCandles = createHighVolatilityCandles(30)
    val analysis = volatilityService.doVolatilityAnalysis(extremeCandles)
    
    analysis.reversalProbability should not be empty
    analysis.standardDeviation.getReversalProbability should be >= 0.0
    analysis.standardDeviation.getReversalProbability should be <= 1.0
  }

  "Edge cases" should "handle empty candle list" in {
    assertThrows[IllegalArgumentException] {
      volatilityService.doVolatilityAnalysis(List.empty)
    }
  }

  it should "handle single candle" in {
    val singleCandle = List(createCandle(100, 101, 99, 100.5))
    assertThrows[IllegalArgumentException] {
      volatilityService.calculateTrueRangeAnalysis(singleCandle)
    }
  }

  it should "handle identical price candles" in {
    val flatCandles = (1 to 20).map { i =>
      createCandle(100, 100, 100, 100, timestamp = i * 60000L)
    }.toList
    
    val analysis = volatilityService.doVolatilityAnalysis(flatCandles)
    // Should indicate very low volatility
    analysis.trueRange.atr should be <= 1.0 // Should be very low
    analysis.overallVolatility should not be("High")
  }

  it should "handle extreme price gaps correctly" in {
    val gapCandles = (1 to 15).map { i =>
      val gap = if (i == 8) 10.0 else 0.0 // Large gap in the middle
      val basePrice = 100.0 + gap
      createCandle(basePrice, basePrice + 0.5, basePrice - 0.3, basePrice + 0.2, timestamp = i * 60000L)
    }.toList
    
    val analysis = volatilityService.doVolatilityAnalysis(gapCandles)
    // Should handle extreme gaps and show higher volatility
    analysis.trueRange.atr should be > 0.5
    analysis.trueRange.volatilityLevel should not be empty
  }

  it should "maintain consistency across different timeframes" in {
    val shortTerm = createHighVolatilityCandles(10)
    val longTerm = createHighVolatilityCandles(50)
    
    val shortAnalysis = volatilityService.doVolatilityAnalysis(shortTerm)
    val longAnalysis = volatilityService.doVolatilityAnalysis(longTerm)
    
    // Both should detect some level of volatility
    shortAnalysis.trueRange.atr should be > 0.0
    longAnalysis.trueRange.atr should be > 0.0
  }
}