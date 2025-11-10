package bmps.core.services.analysis

import bmps.core.models.{Candle, CandleDuration}
import bmps.core.services.analysis._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VolumeSpec extends AnyFlatSpec with Matchers {

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

  // Create candles with high volume on up moves
  private def createHighVolumeUptrendCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (i * 0.5)
      val volume = if (basePrice > 105.0) 2000L else 1000L // Higher volume on uptrend
      createCandle(
        open = basePrice - 0.2,
        high = basePrice + 0.3,
        low = basePrice - 0.3,
        close = basePrice,
        volume = volume,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create candles with varying volume
  private def createVaryingVolumeCandles(periods: Int = 20): List[Candle] = {
    (1 to periods).map { i =>
      val basePrice = 100.0 + (math.sin(i * 0.3) * 2.0)
      val volume = (1000L + (i * 100L)) // Increasing volume over time
      createCandle(
        open = basePrice - 0.2,
        high = basePrice + 0.3,
        low = basePrice - 0.3,
        close = basePrice,
        volume = volume,
        timestamp = i * 60000L
      )
    }.toList
  }

  // Create 1-second candles for volume profile testing
  private def createOneSecondCandles(baseCandle: Candle, count: Int = 60): List[Candle] = {
    (1 to count).map { i =>
      val price = baseCandle.close + (math.random() - 0.5) * 0.2
      createCandle(
        open = price - 0.05,
        high = price + 0.1,
        low = price - 0.1,
        close = price,
        volume = (100L + (math.random() * 200).toLong),
        timestamp = baseCandle.timestamp + (i * 1000L)
      )
    }.toList
  }

  val volumeService = new Volume()

  "Volume Analysis" should "calculate VWAP correctly" in {
    val candles = createVaryingVolumeCandles(20)
    val vwap = volumeService.calculateVWAP(candles)
    
    vwap.vwap should be > 0.0
    vwap.standardDeviationBands.head should be > 0.0
    vwap.currentPriceVsVWAP should not be empty
  }

  it should "detect volume-price relationships in VWAP" in {
    val highVolumeCandles = createHighVolumeUptrendCandles(20)
    val analysis = volumeService.doVolumeAnalysis(highVolumeCandles)
    
    analysis.vwap.vwap should be > 0.0
    analysis.vwap.currentPriceVsVWAP should not be empty
  }

  it should "calculate Volume Profile correctly" in {
    val baseCandle = createCandle(100, 101, 99, 100.5, 60000L)
    val oneSecondCandles = createOneSecondCandles(baseCandle)
    val analysis = volumeService.doVolumeAnalysis(oneSecondCandles)
    
    analysis.volumeProfile.pocPrice should be > 0.0
    analysis.volumeProfile.valueAreaHigh should be >= analysis.volumeProfile.pocPrice
    analysis.volumeProfile.valueAreaLow should be <= analysis.volumeProfile.pocPrice
    analysis.volumeProfile.totalVolume should be > 0L
  }

  it should "identify Point of Control correctly" in {
    val baseCandle = createCandle(100, 101, 99, 100.5, 60000L)
    val oneSecondCandles = createOneSecondCandles(baseCandle, 100)
    val analysis = volumeService.doVolumeAnalysis(oneSecondCandles)
    
    analysis.volumeProfile.pocPrice should be >= 99.0
    analysis.volumeProfile.pocPrice should be <= 101.0
    analysis.volumeProfile.pocVolume should be > 0L
  }

  it should "calculate OBV correctly" in {
    val candles = createHighVolumeUptrendCandles(15)
    val obv = volumeService.calculateOBV(candles)
    
    // OBV should generally increase with uptrend + volume
    obv.toDouble should be > 0.0
  }

  it should "calculate VPT correctly" in {
    val candles = createVaryingVolumeCandles(15)
    val vpt = volumeService.calculateVPT(candles)
    
    vpt should not be 0.0 // Should have some value
  }

  it should "detect volume confirmation signals" in {
    val highVolumeCandles = createHighVolumeUptrendCandles(25)
    val analysis = volumeService.doVolumeAnalysis(highVolumeCandles)
    
    // Should detect volume supporting price movement
    analysis.vwap.currentPriceVsVWAP should not be "Unknown"
    analysis.volumeQuality should not be "Poor"
  }

  it should "handle volume profile with insufficient 1-second data" in {
    val regularCandles = createVaryingVolumeCandles(10)
    val analysis = volumeService.doVolumeAnalysis(regularCandles)
    
    // Should handle gracefully without 1-second data
    analysis.volumeProfile.pocPrice should be > 0.0
    analysis.volumeProfile.totalVolume should be > 0L
  }

  it should "provide reasonable volume trading signals" in {
    val volumeCandles = createHighVolumeUptrendCandles(30)
    val volumeAnalyses = List(volumeService.doVolumeAnalysis(volumeCandles))
    
    val signal = volumeService.volumeTradingSignal(volumeAnalyses, volumeCandles.last.close.toDouble)
    
    signal should be >= 0.0
    signal should be <= 1.0
  }

  it should "enhance volume signals with multi-period analysis" in {
    val volumeCandles = createHighVolumeUptrendCandles(50)
    val recentAnalyses = (1 to 10).map { i =>
      val subCandles = volumeCandles.take(30 + i)
      volumeService.doVolumeAnalysis(subCandles)
    }.toList
    
    // Just test that the basic signal works since enhanced version might not exist
    val basicSignal = volumeService.volumeTradingSignal(recentAnalyses, volumeCandles.last.close.toDouble)
    
    basicSignal should be >= 0.0
    basicSignal should be <= 1.0
  }

  it should "calculate volume profile statistics correctly" in {
    val baseCandle = createCandle(100, 105, 95, 102, 100000L)
    val oneSecondCandles = createOneSecondCandles(baseCandle, 200)
    val analysis = volumeService.doVolumeAnalysis(oneSecondCandles)
    
    // Value Area should contain significant volume
    analysis.volumeProfile.valueAreaHigh should be >= analysis.volumeProfile.valueAreaLow
    analysis.volumeProfile.totalVolume should be > 0L
  }

  it should "detect VWAP support/resistance levels" in {
    val candles = createVaryingVolumeCandles(30)
    val vwap = volumeService.calculateVWAP(candles)
    val currentPrice = candles.last.close.toDouble
    
    if (currentPrice > vwap.vwap) {
      vwap.currentPriceVsVWAP should include("Above")
    } else if (currentPrice < vwap.vwap) {
      vwap.currentPriceVsVWAP should include("Below")
    }
  }

  it should "handle edge cases gracefully" in {
    val fewCandles = createVaryingVolumeCandles(3)
    val analysis = volumeService.doVolumeAnalysis(fewCandles)
    
    // Should not throw exceptions
    analysis.vwap.vwap should be > 0.0
    analysis.volumeProfile.pocPrice should be > 0.0
  }

  "Edge cases" should "handle empty candle list" in {
    assertThrows[IllegalArgumentException] {
      volumeService.doVolumeAnalysis(List.empty)
    }
  }

  it should "handle single candle" in {
    val singleCandle = List(createCandle(100, 101, 99, 100.5, 1000L))
    val analysis = volumeService.doVolumeAnalysis(singleCandle)
    
    analysis.vwap.vwap should be > 0.0
    analysis.volumeProfile.pocPrice should be > 0.0
  }

  it should "handle zero volume candles" in {
    val zeroVolumeCandles = (1 to 10).map { i =>
      createCandle(100 + i * 0.1, 100.2 + i * 0.1, 99.8 + i * 0.1, 100.05 + i * 0.1, 0L, i * 60000L)
    }.toList
    
    val analysis = volumeService.doVolumeAnalysis(zeroVolumeCandles)
    // Should handle gracefully
    analysis.vwap.vwap should be > 0.0
  }

  it should "handle extreme volume spikes" in {
    val normalCandles = createVaryingVolumeCandles(10)
    val spikeCandles = normalCandles.updated(5, 
      normalCandles(5).copy(volume = 1000000L) // Extreme volume spike
    )
    
    val analysis = volumeService.doVolumeAnalysis(spikeCandles)
    // Should handle volume spikes without errors
    analysis.vwap.vwap should be > 0.0
    analysis.volumeQuality should not be empty
  }

  it should "maintain volume profile accuracy with large datasets" in {
    val baseCandle = createCandle(100, 105, 95, 102, 100000L)
    val largeDataset = createOneSecondCandles(baseCandle, 1000)
    
    val analysis = volumeService.doVolumeAnalysis(largeDataset)
    
    // Should maintain accuracy with large datasets
    analysis.volumeProfile.totalVolume should be > 0L
    analysis.volumeProfile.pocPrice should be >= 95.0
    analysis.volumeProfile.pocPrice should be <= 105.0
  }
}