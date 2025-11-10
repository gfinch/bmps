package bmps.core.services.analysis

import bmps.core.models.{Candle, CandleDuration}
import bmps.core.services.analysis._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MomentumSpec extends AnyFlatSpec with Matchers {

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

  // Create oversold scenario (falling prices)
  private def createOversoldCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 110.0 - (i * 1.0) // Sharp decline for oversold
      createCandle(
        open = basePrice + 0.5,
        high = basePrice + 0.8,
        low = basePrice - 0.5,
        close = basePrice,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create overbought scenario (rising prices)
  private def createOverboughtCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 90.0 + (i * 1.0) // Sharp rise for overbought
      createCandle(
        open = basePrice - 0.5,
        high = basePrice + 0.5,
        low = basePrice - 0.8,
        close = basePrice,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create oscillating prices for testing stochastics
  private def createOscillatingCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (math.sin(i * 0.5) * 5.0) // Oscillate between 95-105
      createCandle(
        open = basePrice - 0.5,
        high = basePrice + 1.0,
        low = basePrice - 1.0,
        close = basePrice,
        timestamp = i * 60000L
      )
    }.toList
  }

  val momentumService = new Momentum()

  "Momentum Analysis" should "calculate RSI correctly" in {
    val oversoldCandles = createOversoldCandles(20)
    val rsi = momentumService.calculateRSI(oversoldCandles, 14)
    
    rsi should be >= 0.0
    rsi should be <= 100.0
    // After a decline, RSI should be relatively low
    rsi should be < 50.0
  }

  it should "detect oversold conditions with RSI" in {
    val oversoldCandles = createOversoldCandles(30)
    val analysis = momentumService.doMomentumAnalysis(oversoldCandles)
    
    analysis.rsi should be < 30.0 // RSI oversold threshold
    analysis.rsiOversold shouldBe true
  }

  it should "detect overbought conditions with RSI" in {
    val overboughtCandles = createOverboughtCandles(30)
    val analysis = momentumService.doMomentumAnalysis(overboughtCandles)
    
    analysis.rsi should be > 70.0 // RSI overbought threshold
    analysis.rsiOverbought shouldBe true
  }

  it should "calculate Stochastic oscillator correctly" in {
    val candles = createOscillatingCandles(20)
    val stoch = momentumService.calculateStochastics(candles, 14, 3)
    
    stoch.percentK should be >= 0.0
    stoch.percentK should be <= 100.0
    stoch.percentD should be >= 0.0
    stoch.percentD should be <= 100.0
  }

  it should "detect oversold conditions with Stochastic" in {
    val oversoldCandles = createOversoldCandles(25)
    val analysis = momentumService.doMomentumAnalysis(oversoldCandles)
    
    analysis.stochastics.percentK should be < 20.0
    analysis.stochastics.isOversold shouldBe true
  }

  it should "detect overbought conditions with Stochastic" in {
    val overboughtCandles = createOverboughtCandles(25)
    val analysis = momentumService.doMomentumAnalysis(overboughtCandles)
    
    analysis.stochastics.percentK should be > 80.0
    analysis.stochastics.isOverbought shouldBe true
  }

  it should "calculate Williams %R correctly" in {
    val candles = createOscillatingCandles(20)
    val williamsR = momentumService.calculateWilliamsR(candles, 14)
    
    williamsR should be >= -100.0
    williamsR should be <= 0.0
  }

  it should "detect oversold with Williams %R" in {
    val oversoldCandles = createOversoldCandles(20)
    val analysis = momentumService.doMomentumAnalysis(oversoldCandles)
    
    analysis.williamsR should be < -80.0 // Williams %R oversold
    analysis.williamsROversold shouldBe true
  }

  it should "calculate CCI correctly" in {
    val candles = createOscillatingCandles(20)
    val cci = momentumService.calculateCCI(candles, 20)
    
    // CCI can theoretically go beyond ±100, but should be reasonable
    cci should be >= -300.0
    cci should be <= 300.0
  }

  it should "detect extreme conditions with CCI" in {
    val extremeCandles = createOversoldCandles(25)
    val analysis = momentumService.doMomentumAnalysis(extremeCandles)
    
    // CCI should detect extreme oversold conditions
    analysis.cci should be < -100.0
    analysis.cciOversold shouldBe true
  }

  it should "provide reasonable momentum trading signals" in {
    val oversoldCandles = createOversoldCandles(30)
    val momentumAnalyses = List(momentumService.doMomentumAnalysis(oversoldCandles))
    
    val signal = momentumService.buyingOpportunityScore(momentumAnalyses, 1) // Test basic single-analysis case
    
    signal should be >= 0.0
    signal should be <= 1.0
    // Oversold conditions should generate positive signals for buying
    signal should be > 0.5
  }

  it should "enhance momentum signals with multi-period analysis" in {
    val oversoldCandles = createOversoldCandles(50)
    val recentAnalyses = (1 to 10).map { i =>
      val subCandles = oversoldCandles.take(25 + i)
      momentumService.doMomentumAnalysis(subCandles)
    }.toList
    
    val enhancedSignal = momentumService.buyingOpportunityScore(recentAnalyses, 10)
    
    enhancedSignal should be >= 0.0
    enhancedSignal should be <= 1.0
  }

  it should "handle insufficient data gracefully" in {
    val fewCandles = createOscillatingCandles(5)
    val analysis = momentumService.doMomentumAnalysis(fewCandles)
    
    // Should not throw exceptions and should provide some analysis
    analysis.rsi should be >= 0.0
    analysis.rsi should be <= 100.0
  }

  it should "detect bullish divergence patterns" in {
    // Create price decline with momentum recovery
    val divergenceCandles = createOversoldCandles(15) ++ 
      createOscillatingCandles(10).map(c => c.copy(timestamp = c.timestamp + 15 * 60000L))
    
    val analysis = momentumService.doMomentumAnalysis(divergenceCandles)
    // Should show some improvement in momentum indicators
    analysis.rsi should be > 20.0
  }

  it should "calculate momentum scores consistently" in {
    val neutralCandles = createOscillatingCandles(20)
    val analysis = momentumService.doMomentumAnalysis(neutralCandles)
    
    // All momentum indicators should be within reasonable ranges
    analysis.rsi should be >= 30.0
    analysis.rsi should be <= 70.0
    analysis.stochastics.percentK should be >= 20.0
    analysis.stochastics.percentK should be <= 80.0
    analysis.williamsR should be >= -80.0
    analysis.williamsR should be <= -20.0
  }

  "Edge cases" should "handle empty candle list" in {
    assertThrows[IllegalArgumentException] {
      momentumService.doMomentumAnalysis(List.empty)
    }
  }

  it should "handle single candle" in {
    val singleCandle = List(createCandle(100, 101, 99, 100.5))
    assertThrows[IllegalArgumentException] {
      momentumService.doMomentumAnalysis(singleCandle)
    }
  }

  it should "handle identical price candles" in {
    val flatCandles = (1 to 20).map { i =>
      createCandle(100, 100, 100, 100, timestamp = i * 60000L)
    }.toList
    
    val analysis = momentumService.doMomentumAnalysis(flatCandles)
    // RSI should be around 50 for flat prices, but may not be exact due to calculation methods
    analysis.rsi should be >= 0.0
    analysis.rsi should be <= 100.0
  }

  it should "handle extreme price movements" in {
    val extremeCandles = (1 to 20).map { i =>
      val price = if (i % 2 == 0) 90.0 else 110.0 // Extreme volatility
      createCandle(price, price + 2, price - 2, price, timestamp = i * 60000L)
    }.toList
    
    val analysis = momentumService.doMomentumAnalysis(extremeCandles)
    // Should handle extreme volatility without errors
    analysis.rsi should be >= 0.0
    analysis.rsi should be <= 100.0
  }
}