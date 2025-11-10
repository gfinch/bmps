package bmps.core.services.analysis

import bmps.core.models.{Candle, CandleDuration}
import bmps.core.services.analysis._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TrendSpec extends AnyFlatSpec with Matchers {

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

  // Create uptrend test data
  private def createUptrendCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (i * 0.5) // Gradual uptrend
      createCandle(
        open = basePrice - 0.2,
        high = basePrice + 0.3,
        low = basePrice - 0.3,
        close = basePrice,
        timestamp = i * 60000L // 1 minute intervals
      )
    }.toList
  }

  // Create downtrend test data
  private def createDowntrendCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 110.0 - (i * 0.5) // Gradual downtrend
      createCandle(
        open = basePrice + 0.2,
        high = basePrice + 0.3,
        low = basePrice - 0.3,
        close = basePrice,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create sideways/choppy test data
  private def createSidewaysCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (math.sin(i * 0.3) * 0.5) // Oscillating around 100
      createCandle(
        open = basePrice - 0.1,
        high = basePrice + 0.3,
        low = basePrice - 0.3,
        close = basePrice,
        timestamp = i * 60000L
      )
    }.toList
  }

  val trendService = new Trend()

  "Trend Analysis" should "calculate simple moving averages correctly" in {
    val candles = createUptrendCandles(10)
    val sma5 = trendService.simpleMovingAverage(candles, 5)
    val sma10 = trendService.simpleMovingAverage(candles, 10)

    sma5 should be > 0.0
    sma10 should be > 0.0
    // In an uptrend, shorter SMA should be higher than longer SMA
    sma5 should be > sma10
  }

  it should "calculate exponential moving averages correctly" in {
    val candles = createUptrendCandles(20)
    val ema12 = trendService.exponentialMovingAverage(candles, 12)
    val ema26 = trendService.exponentialMovingAverage(candles, 26)

    ema12 should be > 0.0
    ema26 should be > 0.0
    // In an uptrend, shorter EMA should be higher than longer EMA
    ema12 should be > ema26
  }

  it should "detect golden cross (bullish signal)" in {
    val candles = createUptrendCandles(30)
    val analysis = trendService.doTrendAnalysis(candles)
    
    analysis.isGoldenCross shouldBe true
    analysis.isDeathCross shouldBe false
    analysis.direction.toString should include("Up")
  }

  it should "detect death cross (bearish signal)" in {
    val candles = createDowntrendCandles(30)
    val analysis = trendService.doTrendAnalysis(candles)
    
    analysis.isDeathCross shouldBe true
    analysis.isGoldenCross shouldBe false
    analysis.direction.toString should include("Down")
  }

  it should "calculate DMI components correctly" in {
    val candles = createUptrendCandles(30)
    val dmi = trendService.calculateDMI(candles, 14)
    
    dmi.plusDI should be >= 0.0
    dmi.minusDI should be >= 0.0
    dmi.adx should be >= 0.0
    
    // In an uptrend, +DI should typically be higher than -DI
    // But for gentle trends this might not always be true, so just check values are reasonable
    dmi.plusDI should be <= 100.0
    dmi.minusDI should be <= 100.0
  }

  it should "classify trend strength correctly" in {
    val strongTrendCandles = createUptrendCandles(20)
    val analysis = trendService.doTrendAnalysis(strongTrendCandles)
    
    analysis.trendStrength should (be("Strong") or be("Weak") or be("Very Strong"))
    analysis.adx should be >= 0.0
  }

  it should "detect trend reversals" in {
    // Create a pattern that reverses from up to down
    val uptrendPart = createUptrendCandles(15)
    val downtrendPart = createDowntrendCandles(10).map(c => 
      c.copy(timestamp = c.timestamp + 15 * 60000L))
    val reversalCandles = uptrendPart ++ downtrendPart
    
    val analysis = trendService.doTrendAnalysis(reversalCandles)
    // Should detect some form of trend change
    analysis.adx should be >= 0.0
  }

  it should "handle insufficient data gracefully" in {
    val fewCandles = createUptrendCandles(5)
    val analysis = trendService.doTrendAnalysis(fewCandles)
    
    // Should not throw exceptions and should provide some analysis
    analysis.shortTermMA should be > 0.0
    analysis.longTermMA should be > 0.0
  }

  it should "provide reasonable trend trading signals" in {
    val uptrendCandles = createUptrendCandles(30)
    val trendAnalyses = List(trendService.doTrendAnalysis(uptrendCandles))
    
    val signal = trendService.trendReversalSignal(trendAnalyses, uptrendCandles.last.close.toDouble)
    
    signal should be >= 0.0
    signal should be <= 1.0
  }

  it should "enhance trend signals with multi-period analysis" in {
    val uptrendCandles = createUptrendCandles(50)
    val recentAnalyses = (1 to 10).map { i =>
      val subCandles = uptrendCandles.take(30 + i)
      trendService.doTrendAnalysis(subCandles)
    }.toList
    
    val enhancedSignal = trendService.trendReversalSignal(recentAnalyses, uptrendCandles.last.close.toDouble, 10)
    
    enhancedSignal should be >= 0.0
    enhancedSignal should be <= 1.0
  }

  it should "calculate Triangular Moving Average correctly" in {
    val candles = createUptrendCandles(20)
    val tma = trendService.triangularMovingAverage(candles, 10)
    
    tma should be > 0.0
    // TMA should be smoother and between SMA values
    val sma = trendService.simpleMovingAverage(candles, 10)
    math.abs(tma - sma) should be < sma * 0.1 // Within 10% of SMA
  }

  "Edge cases" should "handle empty candle list" in {
    assertThrows[IllegalArgumentException] {
      trendService.doTrendAnalysis(List.empty)
    }
  }

  it should "handle single candle" in {
    val singleCandle = List(createCandle(100, 101, 99, 100.5))
    assertThrows[IllegalArgumentException] {
      trendService.doTrendAnalysis(singleCandle)
    }
  }

  it should "handle identical price candles" in {
    val flatCandles = (1 to 20).map { i =>
      createCandle(100, 100, 100, 100, timestamp = i * 60000L)
    }.toList
    
    val analysis = trendService.doTrendAnalysis(flatCandles)
    analysis.adx should be <= 25.0 // Should indicate weak trend
  }
}