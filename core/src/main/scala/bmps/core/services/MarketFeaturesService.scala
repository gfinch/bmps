package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.Candle
import bmps.core.utils.TimestampUtils
import bmps.core.brokers.LeadAccountBroker
import java.time.{Instant, LocalTime}

/**
 * Simplified, normalized MarketFeatures & label generator focused on short-term regression targets.
 * - All price features are expressed relative to the current candle (close = 0) and scaled by ATR.
 * - Feature set is intentionally small and short-term focused.
 * - Labels are continuous future-relative moves at horizons (in minutes) normalized by ATR.
 *
 * Use this class to regenerate training data for the next model iteration.
 */

case class RegressionLabels(
  candleTimestamp: Long,
  horizonsMin: Seq[Int],            // e.g. Seq(1,2,5,10,20)
  futureMovesAtr: Seq[Float]        // (future_close - entry_close) / atr  (signed)
) {
  lazy val asVector: Seq[Float] = futureMovesAtr
  
  /**
   * Backward compatibility alias for asVector
   */
  def toLabelVector: Seq[Float] = asVector
}

case class MarketFeaturesNormalized(
  atr: Float,

  // Candle-anchored, ATR-scaled features (close is the origin = 0)
  candleBodyRatio: Float,            // body / range (0..1)
  candlePositionInRange: Float,      // 0..1 where close sits in range
  upperWickRatio: Float,             // 0..1
  lowerWickRatio: Float,             // 0..1

  // time-of-day
  timeSin: Float,
  timeCos: Float,
  minutesSinceOpenNorm: Float,       // minutes / total_session_minutes

  // short-term momentum (expressed in ATR units)
  mom_1: Float,                      // (close - close[-1]) / atr
  mom_2: Float,
  mom_3: Float,
  mom_5: Float,
  mom_10: Float,

  // short volatility (std of log returns normalized to ATR)
  vol_5: Float,
  vol_10: Float,

  // short RSI
  rsi_7: Float,

  // volume
  volume_z_5: Float,                 // z-score vs last 5 bars
  relative_volume_10: Float,         // ratio current / avg(last 10)

  // price vs short EMAs in ATR units
  price_vs_ema_5: Float,
  price_vs_ema_10: Float,

  // distance to recent extremes (in ATR units)
  dist_to_high_10_atr: Float,
  dist_to_low_10_atr: Float,

  // simple short trend alignment (5 vs 15) -> -1,0,1
  trend_align_5_15: Float
) {
  def toFeatureVector: Array[Float] = Array(
    candleBodyRatio,
    candlePositionInRange,
    upperWickRatio,
    lowerWickRatio,
    timeSin,
    timeCos,
    minutesSinceOpenNorm,
    mom_1, mom_2, mom_3, mom_5, mom_10,
    vol_5, vol_10,
    rsi_7,
    volume_z_5, relative_volume_10,
    price_vs_ema_5, price_vs_ema_10,
    dist_to_high_10_atr, dist_to_low_10_atr,
    trend_align_5_15
  )
}

object MarketFeaturesService {
  // short horizons in minutes for regression labels
  val defaultHorizons: Seq[Int] = Seq(1, 2, 5, 10, 20)
  
  // ATR period optimized for short-term trading (10 minutes)
  val atrPeriod: Int = 10

  // tick-to-price conversion for ES should be provided by caller/contract; default multiplier is placeholder
  val defaultTickToPrice: Double = 0.25
}

class MarketFeaturesService(leadAccount: LeadAccountBroker, lagCandles: Int = 20) {

  import MarketFeaturesService._

  /**
   * Compute features using only past data (causal). All price differences are scaled by atr (14) so
   * that the model sees normalized patterns independent of absolute price level or regime.
   */
  def computeMarketFeatures(state: SystemState): MarketFeaturesNormalized = {
    val candles = state.tradingCandles.dropRight(lagCandles)
    require(candles.nonEmpty, "Not enough candles for features")
    val cur = candles.last

    // compute atr from past candles (causal) - optimized for short-term trades
    val atrVal = computeAtr(candles, Seq(atrPeriod)).headOption.getOrElse(0.0f)
    val atrSafe = if (atrVal <= 0.0f) 1.0f else atrVal.toDouble

    // basic candle ratios (already relative to candle range)
    val bodyRatio = computeCandleBodyRatio(cur)
    val posInRange = computeCandlePositionInRange(cur)
    val upWick = computeUpperWickRatio(cur)
    val lowWick = computeLowerWickRatio(cur)

    // time features
    val (timeSin, timeCos) = computeTimeComponents(cur)
    val minutesSinceOpen = computeMinutesSinceOpen(cur)
    val minutesSinceOpenNorm = math.max(0.0f, math.min(1.0f, minutesSinceOpen / 390.0f)) // clamp to [0,1]

    // short momentums: (close - close[-n]) / atr
    val mom1 = computeMomentumSingle(candles, 1) / atrSafe
    val mom2 = computeMomentumSingle(candles, 2) / atrSafe
    val mom3 = computeMomentumSingle(candles, 3) / atrSafe
    val mom5 = computeMomentumSingle(candles, 5) / atrSafe
    val mom10 = computeMomentumSingle(candles, 10) / atrSafe

    // short vol: std of log returns over window, scaled by atr
    val vol5 = computeVolatilitySingle(candles, 5) / atrSafe
    val vol10 = computeVolatilitySingle(candles, 10) / atrSafe

    // RSI(7)
    val rsi7 = computeRsi(candles, Seq(7)).headOption.getOrElse(50.0f)

    // Volume features
    val volumeZ5 = computeVolumeSpike(candles, Seq(5), useZScore = true).headOption.getOrElse(0.0f)
    val relVol10 = computeRelativeVolume(candles, 10)

    // EMAs and price vs EMA in ATR units
    val priceVsEma5 = priceVsEMAInAtr(candles, 5, atrSafe)
    val priceVsEma10 = priceVsEMAInAtr(candles, 10, atrSafe)

    // distance to recent extremes (10 bars) in ATR units
    val distHigh10 = computeDistanceToRecentHighInAtr(candles, 10, atrSafe)
    val distLow10 = computeDistanceToRecentLowInAtr(candles, 10, atrSafe)

    // trend alignment 5 vs 15
    val trendAlign = computeTrendAlignment(candles, Seq((5, 15))).headOption.getOrElse(0.0f)

    MarketFeaturesNormalized(
      atr = atrSafe.toFloat,
      candleBodyRatio = bodyRatio,
      candlePositionInRange = posInRange,
      upperWickRatio = upWick,
      lowerWickRatio = lowWick,
      timeSin = timeSin,
      timeCos = timeCos,
      minutesSinceOpenNorm = minutesSinceOpenNorm,
      mom_1 = mom1.toFloat,
      mom_2 = mom2.toFloat,
      mom_3 = mom3.toFloat,
      mom_5 = mom5.toFloat,
      mom_10 = mom10.toFloat,
      vol_5 = vol5.toFloat,
      vol_10 = vol10.toFloat,
      rsi_7 = rsi7,
      volume_z_5 = volumeZ5,
      relative_volume_10 = relVol10,
      price_vs_ema_5 = priceVsEma5.toFloat,
      price_vs_ema_10 = priceVsEma10.toFloat,
      dist_to_high_10_atr = distHigh10.toFloat,
      dist_to_low_10_atr = distLow10.toFloat,
      trend_align_5_15 = trendAlign
    )
  }

  /**
   * Compute regression labels for several minute horizons. Labels are (future_close - entry_close) / atr10.
   * Uses only causal data to compute ATR (same as for features) but requires future bars for labels.
   */
  def computeRegressionLabels(state: SystemState, horizons: Seq[Int] = defaultHorizons): RegressionLabels = {
    require(state.contractSymbol.nonEmpty, "SystemState does not contain a base contract.")

    // futureCandles: [entry, future1, future2, ...] where length = lagCandles + 1 in caller
    val futureCandles = state.tradingCandles.takeRight(lagCandles + 1)
    require(futureCandles.nonEmpty, "Not enough candles to compute labels")

    val entry = futureCandles.head
    val futures = futureCandles.tail

    // compute atr on the past series up to entry (causal). Reconstruct past candles same as computeMarketFeatures
    val pastCandles = state.tradingCandles.dropRight(lagCandles)
    val atrVal = computeAtr(pastCandles, Seq(atrPeriod)).headOption.getOrElse(0.0f)
    val atrSafe = if (atrVal <= 0.0f) 1.0f else atrVal.toDouble

    val moves = horizons.map { h =>
      if (futures.length >= h) {
        val futureClose = futures(h - 1).close
        ((futureClose - entry.close) / atrSafe).toFloat
      } else {
        0.0f
      }
    }

    RegressionLabels(entry.timestamp, horizons, moves)
  }

  /**
   * Backward compatibility alias for computeRegressionLabels
   */
  def computeLabels(state: SystemState): RegressionLabels = {
    computeRegressionLabels(state, defaultHorizons)
  }

  // ====== Helper (reused/ported from original) ======

  private def computeMomentumSingle(candles: Seq[Candle], horizon: Int): Double = {
    if (candles.length <= horizon) return 0.0
    val past = candles(candles.length - 1 - horizon).close
    (candles.last.close - past)
  }

  private def computeVolatilitySingle(candles: Seq[Candle], horizon: Int): Double = {
    if (candles.length <= horizon) return 0.0
    val closes = candles.map(_.close)
    val logReturns = closes.zip(closes.drop(1)).map { case (p1, p2) => 
      if (p1 <= 0.0 || p2 <= 0.0) 0.0 else math.log(p2 / p1) 
    }
    val window = logReturns.takeRight(horizon)
    if (window.isEmpty) 0.0 else {
      val mean = window.sum / window.size
      val variance = window.map(r => math.pow(r - mean, 2)).sum / window.size
      if (variance < 0.0 || variance.isNaN) 0.0 else math.sqrt(variance)
    }
  }

  private def computeRsi(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    if (candles.length < 2) return horizons.map(_ => 50.0f)
    val closes = candles.map(_.close)
    val diffs = closes.zip(closes.tail).map { case (a, b) => b - a }
    horizons.map { n =>
      if (diffs.length < n) 50.0f
      else {
        val window = diffs.takeRight(n)
        val gains = window.map(d => math.max(d, 0.0)).sum
        val losses = window.map(d => math.max(-d, 0.0)).sum
        val avgG = gains / n
        val avgL = losses / n
        if (avgL == 0.0 && avgG == 0.0) 50.0f
        else if (avgL == 0.0) 100.0f
        else if (avgG == 0.0) 0.0f
        else {
          val rs = avgG / avgL
          (100.0 - (100.0 / (1.0 + rs))).toFloat
        }
      }
    }
  }

  private def computeVolumeSpike(candles: Seq[Candle], horizons: Seq[Int], useZScore: Boolean = true): Seq[Float] = {
    if (candles.isEmpty) return horizons.map(_ => 0.0f)
    val volumes = candles.map(_.volume.toDouble)
    val cur = volumes.last
    horizons.map { n =>
      val prev = if (volumes.length > n) volumes.takeRight(n + 1).init else volumes.init
      val mean = if (prev.isEmpty) 0.0 else prev.sum / prev.size
      val std = if (prev.isEmpty) 0.0 else math.sqrt(prev.map(v => math.pow(v - mean, 2)).sum / prev.size)
      if (useZScore) {
        if (std > 0.0) ((cur - mean) / std).toFloat
        else if (mean > 0.0) ((cur / mean) - 1.0).toFloat
        else 0.0f
      } else {
        if (mean > 0.0) (cur / mean).toFloat else 0.0f
      }
    }
  }

  private def computeRelativeVolume(candles: Seq[Candle], n: Int): Float = {
    if (candles.length < n + 1) return 1.0f
    val recent = candles.takeRight(n + 1).init.map(_.volume.toDouble)
    val avg = if (recent.isEmpty) 1.0 else recent.sum / recent.length
    if (avg <= 0.0) 1.0f else ((candles.last.volume.toDouble / avg).toFloat)
  }

  private def priceVsEMAInAtr(candles: Seq[Candle], emaPeriod: Int, atrSafe: Double): Double = {
    val closes = candles.map(_.close.toDouble)
    if (closes.length < emaPeriod) return 0.0
    val alpha = 2.0 / (emaPeriod + 1.0)
    var ema = closes.take(emaPeriod).sum / math.max(1, emaPeriod)
    var i = emaPeriod
    while (i < closes.length) {
      ema = alpha * closes(i) + (1.0 - alpha) * ema
      i += 1
    }
    (closes.last - ema) / atrSafe
  }

  private def computeDistanceToRecentHighInAtr(candles: Seq[Candle], lookback: Int, atrSafe: Double): Double = {
    if (candles.length < lookback) return 0.0
    val recentHigh = candles.takeRight(lookback).map(_.high).max
    (candles.last.close - recentHigh) / atrSafe
  }

  private def computeDistanceToRecentLowInAtr(candles: Seq[Candle], lookback: Int, atrSafe: Double): Double = {
    if (candles.length < lookback) return 0.0
    val recentLow = candles.takeRight(lookback).map(_.low).min
    (candles.last.close - recentLow) / atrSafe
  }

  private def computeTrendAlignment(candles: Seq[Candle], horizonPairs: Seq[(Int, Int)]): Seq[Float] = {
    if (candles.isEmpty) return horizonPairs.map(_ => 0.0f)
    horizonPairs.map { case (s, l) =>
      if (candles.length <= l) 0.0f
      else {
        val shortMom = computeMomentumSingle(candles, s)
        val longMom = computeMomentumSingle(candles, l)
        if (shortMom > 0 && longMom > 0) 1.0f
        else if (shortMom < 0 && longMom < 0) -1.0f
        else 0.0f
      }
    }
  }

  // Reuse original computeAtr implementation but expose Option return
  private def computeAtr(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    if (candles.isEmpty) return horizons.map(_ => 0.0f)
    val highs = candles.map(_.high)
    val lows = candles.map(_.low)
    val closes = candles.map(_.close)
    val trueRanges = (1 until candles.length).map { i =>
      val high = highs(i); val low = lows(i); val prevClose = closes(i - 1)
      Seq(high - low, math.abs(high - prevClose), math.abs(low - prevClose)).max
    }
    horizons.map { n =>
      val window = trueRanges.takeRight(n)
      if (window.isEmpty) 0.0f else (window.sum / window.size).toFloat
    }
  }

  // Time components: return tuple (sin, cos)
  private def computeTimeComponents(candle: Candle): (Float, Float) = {
    val time = Instant.ofEpochMilli(candle.timestamp).atZone(TimestampUtils.NewYorkZone).toLocalTime
    val minuteOfDay = time.getHour * 60 + time.getMinute
    val radians = (minuteOfDay.toDouble / 1440.0) * 2.0 * math.Pi
    (math.sin(radians).toFloat, math.cos(radians).toFloat)
  }

  // Candle body helpers (same as original, causal)
  private def computeCandleBodyRatio(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.0f else (math.abs(candle.close - candle.open) / range).toFloat
  }

  private def computeCandlePositionInRange(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.5f else ((candle.close - candle.low) / range).toFloat
  }

  private def computeUpperWickRatio(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.0f else {
      val top = math.max(candle.open, candle.close)
      ((candle.high - top) / range).toFloat
    }
  }

  private def computeLowerWickRatio(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.0f else {
      val bot = math.min(candle.open, candle.close)
      ((bot - candle.low) / range).toFloat
    }
  }

  private def computeMinutesSinceOpen(candle: Candle): Float = {
    val time = Instant.ofEpochMilli(candle.timestamp).atZone(TimestampUtils.NewYorkZone).toLocalTime
    val marketOpen = LocalTime.of(9, 30)
    java.time.Duration.between(marketOpen, time).toMinutes.toFloat
  }
}
