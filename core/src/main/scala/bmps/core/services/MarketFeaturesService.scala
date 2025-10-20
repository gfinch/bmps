package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.Candle

case class TradeOutcomeLabels(
    candleTimestamp: Long, // Trade happens after candle closes
    long_xx_move_win: Boolean,
    long_yy_move_win: Boolean,
    long_zz_move_win: Boolean,
    short_xx_move_win: Boolean,
    short_yy_move_win: Boolean,
    short_zz_move_win: Boolean
)

case class MarketFeatures(
    // Current candle features
    lastCandle: Candle,
    candleBodyRatio: Float,              // body size / total range
    candlePositionInRange: Float,        // where close is in the high-low range
    upperWickRatio: Float,               // upper wick / total range
    lowerWickRatio: Float,               // lower wick / total range
    
    // Time features
    timeComponents: Seq[Float],
    minutesSinceOpen: Float,
    minutesUntilClose: Float,
    
    // Very short-term price action
    previousFiveClose: Seq[Float],
    recentCandleDirections: Seq[Float],  // last 3 candles: 1.0=up, -1.0=down
    recentCandleSizes: Seq[Float],       // last 3 candles body sizes as % of price
    
    // Multi-horizon momentum
    ultraShortMomentums: Seq[Float],     // 1, 2, 3 min
    shortMomentums: Seq[Float],          // 5, 10, 15 min
    mediumMomentums: Seq[Float],         // 30, 60 min
    
    // Momentum acceleration
    momentumAcceleration: Seq[Float],    // change in momentum
    
    // Volatility across horizons
    ultraShortVolatility: Seq[Float],    // 5, 10, 15 min
    mediumVolatility: Seq[Float],        // 30, 60 min
    
    // RSI - short to medium term
    rsi: Seq[Float],                     // 7, 14, 30 min
    
    // Volume features
    volumeSpikes: Seq[Float],            // 5, 10, 20 min z-scores
    volumeTrend: Float,                  // is volume increasing or decreasing
    relativeVolume: Float,               // current vs recent average
    
    // Price vs moving averages
    priceVsSMA: Seq[Float],              // 10, 20, 60 min
    priceVsEMA: Seq[Float],              // 10, 20, 60 min
    
    // Volatility indicators
    atr: Seq[Float],                     // 7, 14 min
    atrPercent: Float,                   // ATR as % of current price
    
    // Recent extremes
    distanceToRecentHigh: Seq[Float],    // distance to max high in last 10, 30, 60 min
    distanceToRecentLow: Seq[Float],     // distance to min low in last 10, 30, 60 min
    pricePercentileRank: Seq[Float],     // where is current price in recent range (10, 30, 60 min)
    
    // Trend alignment
    trendAlignment: Seq[Float]           // are short/medium/long trends aligned?
) {
  
  /** Flatten all features into a single array for ML model input */
  def toFeatureVector: Array[Float] = {
    Array(
      // Candle OHLCV
      lastCandle.open.toFloat,
      lastCandle.high.toFloat,
      lastCandle.low.toFloat,
      lastCandle.close.toFloat,
      lastCandle.volume.toFloat,
      
      // Candle structure
      candleBodyRatio,
      candlePositionInRange,
      upperWickRatio,
      lowerWickRatio,
      
      // Time features
      minutesSinceOpen,
      minutesUntilClose
    ) ++
    timeComponents ++
    previousFiveClose ++
    recentCandleDirections ++
    recentCandleSizes ++
    ultraShortMomentums ++
    shortMomentums ++
    mediumMomentums ++
    momentumAcceleration ++
    ultraShortVolatility ++
    mediumVolatility ++
    rsi ++
    volumeSpikes ++
    Array(volumeTrend, relativeVolume) ++
    priceVsSMA ++
    priceVsEMA ++
    atr ++
    Array(atrPercent) ++
    distanceToRecentHigh ++
    distanceToRecentLow ++
    pricePercentileRank ++
    trendAlignment
  }
}

object MarketFeatures {
  
  /** Get feature names in the same order as toFeatureVector */
  def featureNames: Array[String] = Array(
    // Candle OHLCV (5)
    "candle_open",
    "candle_high",
    "candle_low",
    "candle_close",
    "candle_volume",
    
    // Candle structure (4)
    "candle_body_ratio",
    "candle_position_in_range",
    "candle_upper_wick_ratio",
    "candle_lower_wick_ratio",
    
    // Time features (2)
    "minutes_since_open",
    "minutes_until_close"
  ) ++
  // Time components (2)
  Array("time_sin", "time_cos") ++
  
  // Previous 5 closes (5)
  (0 until 5).map(i => s"prev_close_${i}").toArray ++
  
  // Recent candle directions (3)
  (0 until 3).map(i => s"recent_direction_${i}").toArray ++
  
  // Recent candle sizes (3)
  (0 until 3).map(i => s"recent_size_${i}").toArray ++
  
  // Ultra short momentum (3: 1, 2, 3 min)
  Array("momentum_1min", "momentum_2min", "momentum_3min") ++
  
  // Short momentum (3: 5, 10, 15 min)
  Array("momentum_5min", "momentum_10min", "momentum_15min") ++
  
  // Medium momentum (2: 30, 60 min)
  Array("momentum_30min", "momentum_60min") ++
  
  // Momentum acceleration (3: 5, 10, 15 min)
  Array("momentum_accel_5min", "momentum_accel_10min", "momentum_accel_15min") ++
  
  // Ultra short volatility (3: 5, 10, 15 min)
  Array("volatility_5min", "volatility_10min", "volatility_15min") ++
  
  // Medium volatility (2: 30, 60 min)
  Array("volatility_30min", "volatility_60min") ++
  
  // RSI (3: 7, 14, 30 min)
  Array("rsi_7min", "rsi_14min", "rsi_30min") ++
  
  // Volume spikes (3: 5, 10, 20 min)
  Array("volume_spike_5min", "volume_spike_10min", "volume_spike_20min") ++
  
  // Volume features (2)
  Array("volume_trend", "relative_volume") ++
  
  // Price vs SMA (3: 10, 20, 60 min)
  Array("price_vs_sma_10min", "price_vs_sma_20min", "price_vs_sma_60min") ++
  
  // Price vs EMA (3: 10, 20, 60 min)
  Array("price_vs_ema_10min", "price_vs_ema_20min", "price_vs_ema_60min") ++
  
  // ATR (2: 7, 14 min)
  Array("atr_7min", "atr_14min") ++
  
  // ATR percent (1)
  Array("atr_percent") ++
  
  // Distance to recent high (3: 10, 30, 60 min)
  Array("dist_to_high_10min", "dist_to_high_30min", "dist_to_high_60min") ++
  
  // Distance to recent low (3: 10, 30, 60 min)
  Array("dist_to_low_10min", "dist_to_low_30min", "dist_to_low_60min") ++
  
  // Price percentile rank (3: 10, 30, 60 min)
  Array("price_pctile_10min", "price_pctile_30min", "price_pctile_60min") ++
  
  // Trend alignment (3: 5vs15, 5vs30, 15vs60)
  Array("trend_align_5_15", "trend_align_5_30", "trend_align_15_60")
}

class MarketFeaturesService() {

  def computeMarketFeatures(state: SystemState, candle: Candle): (MarketFeatures, SystemState) = {
    val candles = state.tradingCandles
    
    // Horizon definitions optimized for minute candles
    val ultraShortHorizons = Seq(1, 2, 3)
    val shortHorizons = Seq(5, 10, 15)
    val mediumHorizons = Seq(30, 60)
    val rsiHorizons = Seq(7, 14, 30)
    val volumeHorizons = Seq(5, 10, 20)
    val maHorizons = Seq(10, 20, 60)
    val atrHorizons = Seq(7, 14)
    val extremeHorizons = Seq(10, 30, 60)
    
    val features = MarketFeatures(
      // Current candle features
      lastCandle = candle,
      candleBodyRatio = computeCandleBodyRatio(candle),
      candlePositionInRange = computeCandlePositionInRange(candle),
      upperWickRatio = computeUpperWickRatio(candle),
      lowerWickRatio = computeLowerWickRatio(candle),
      
      // Time features
      timeComponents = computeTimeComponents(candle),
      minutesSinceOpen = computeMinutesSinceOpen(candle),
      minutesUntilClose = computeMinutesUntilClose(candle),
      
      // Recent price action
      previousFiveClose = candles.takeRight(5).map(_.close.toFloat),
      recentCandleDirections = computeRecentCandleDirections(candles, 3),
      recentCandleSizes = computeRecentCandleSizes(candles, 3),
      
      // Multi-horizon momentum
      ultraShortMomentums = computeMomentum(candles, ultraShortHorizons),
      shortMomentums = computeMomentum(candles, shortHorizons),
      mediumMomentums = computeMomentum(candles, mediumHorizons),
      
      // Momentum acceleration
      momentumAcceleration = computeMomentumAcceleration(candles, shortHorizons),
      
      // Volatility
      ultraShortVolatility = computeVolatility(candles, shortHorizons),
      mediumVolatility = computeVolatility(candles, mediumHorizons),
      
      // RSI
      rsi = computeRsi(candles, rsiHorizons),
      
      // Volume features
      volumeSpikes = computeVolumeSpike(candles, volumeHorizons, useZScore = true),
      volumeTrend = computeVolumeTrend(candles, 10),
      relativeVolume = computeRelativeVolume(candles, 20),
      
      // Price vs MAs
      priceVsSMA = priceVsMovingAverage(candles, maHorizons, maType = "SMA", useRatio = true),
      priceVsEMA = priceVsMovingAverage(candles, maHorizons, maType = "EMA", useRatio = true),
      
      // Volatility indicators
      atr = computeAtr(candles, atrHorizons),
      atrPercent = computeAtrPercent(candles, 14),
      
      // Recent extremes
      distanceToRecentHigh = computeDistanceToRecentHigh(candles, extremeHorizons),
      distanceToRecentLow = computeDistanceToRecentLow(candles, extremeHorizons),
      pricePercentileRank = computePricePercentileRank(candles, extremeHorizons),
      
      // Trend alignment
      trendAlignment = computeTrendAlignment(candles, Seq((5, 15), (5, 30), (15, 60)))
    )
    (features, state)
  }

  private def computeMomentum(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    require(candles.length > horizons.max, "Not enough candles to compute momentum for all horizons.")
    horizons.map { horizon =>
      val pastCandle = candles(candles.length - 1 - horizon)
      val momentum = (candles.last.close - pastCandle.close) / pastCandle.close
      momentum.toFloat
    }
  }

  private def computeVolatility(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    require(candles.length > horizons.max, "Not enough candles to compute volatility for all horizons.")
    val closes = candles.map(_.close)

    // Precompute log returns
    val logReturns = closes.zip(closes.drop(1)).map { case (p1, p2) =>
      math.log(p2 / p1)
    }

    horizons.map { n =>
      val window = logReturns.takeRight(n)
      val mean = window.sum / window.size
      val variance = window.map(r => math.pow(r - mean, 2)).sum / window.size
      math.sqrt(variance).toFloat
    }
  }

  // RSI quantifies momentum — how strong recent price gains are relative to recent losses — on a bounded scale from 0 to 100.
  private def computeRsi(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    require(
      horizons.forall(n => n < candles.length),
      s"Not enough candles: need at least max(${horizons.max + 1}) closes but got ${candles.length}"
    )

    val closes: Seq[Double] = candles.map(_.close)

    // Compute price differences (length = closes.length - 1)
    val diffs: Seq[Double] = closes.zip(closes.tail).map { case (prev, cur) => cur - prev }

    horizons.map { n =>
      // Last n diffs
      val window: Seq[Double] = diffs.takeRight(n)

      val gainsSum = window.map(d => if (d > 0) d else 0.0).sum
      val lossesSum = window.map(d => if (d < 0) -d else 0.0).sum

      val avgGain = gainsSum / n.toDouble
      val avgLoss = lossesSum / n.toDouble

      val rsi =
        if (avgLoss == 0.0) 100.0
        else if (avgGain == 0.0) 0.0
        else {
          val rs = avgGain / avgLoss
          100.0 - (100.0 / (1.0 + rs))
        }

      rsi.toFloat
    }
  }

      // Measure how unusual the current candle's volume is compared with the recent past.
  private def computeVolumeSpike(candles: Seq[Candle], horizons: Seq[Int], useZScore: Boolean = true): Seq[Float] = {
    require(
      horizons.forall(n => n < candles.length),
      s"Not enough candles: need candles.length > max(horizons) (${horizons.max}) but got ${candles.length}"
    )

    val volumes: Seq[Double] = candles.map(_.volume.toDouble)
    val current = volumes.last
    val L = volumes.length

    horizons.map { n =>
      // Previous window is the n volumes immediately before the last candle
      val start = L - 1 - n
      val prevWindow = volumes.slice(start, start + n)

      val mean = if (prevWindow.isEmpty) 0.0 else prevWindow.sum / prevWindow.size

      val variance = if (prevWindow.isEmpty) 0.0 else {
        val m = mean
        prevWindow.map(v => (v - m) * (v - m)).sum / prevWindow.size
      }

      val std = math.sqrt(variance)

      if (useZScore) {
        // Prefer z-score; if std == 0 fall back to ratio if mean > 0, otherwise 0.0f
        if (std > 0.0) ((current - mean) / std).toFloat
        else if (mean > 0.0) ((current / mean) - 1.0).toFloat // Normalized ratio-centered (0 means equal)
        else 0.0f
      } else {
        // Ratio form; if mean == 0 return 0.0f (no history)
        if (mean > 0.0) (current / mean).toFloat else 0.0f
      }
    }
  }

  private def priceVsMovingAverage(
      candles: Seq[Candle],
      horizons: Seq[Int],
      maType: String = "EMA",
      useRatio: Boolean = true
  ): Seq[Float] = {
    // Need at least n closes for an n-period MA (we include the last candle in the MA)
    require(
      horizons.forall(n => n <= candles.length),
      s"Not enough candles: need at least max(horizons) (${horizons.max}) closes but got ${candles.length}"
    )

    val closes: Seq[Double] = candles.map(_.close)
    val lastClose = closes.last

    def smaFor(n: Int): Double = {
      val window = closes.takeRight(n)
      if (window.isEmpty) 0.0 else window.sum / window.size
    }

    def emaFor(n: Int): Double = {
      // Seed EMA with the SMA of the first n closes, then iterate forward to the end.
      // If exactly n closes available, return that SMA as the EMA.
      val alpha = 2.0 / (n + 1.0)
      val seedWindow = closes.take(n)
      var ema = if (seedWindow.nonEmpty) seedWindow.sum / seedWindow.size else 0.0

      // Iterate from index n (0-based) to last index
      var i = n
      while (i < closes.length) {
        val price = closes(i)
        ema = alpha * price + (1.0 - alpha) * ema
        i += 1
      }
      ema
    }

    horizons.map { n =>
      val ma = maType match {
        case "SMA" => smaFor(n)
        case "EMA" => emaFor(n)
      }

      val value: Float =
        if (ma == 0.0) 0.0f
        else if (useRatio) ((lastClose - ma) / ma).toFloat
        else (lastClose - ma).toFloat

      value
    }
  }

  //ATR measures recent true range (how big candles and gaps have been), so it captures volatility including overnight/price gaps and intrabar range.
  private def computeAtr(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    require(candles.nonEmpty, "candles must not be empty")
    require(horizons.nonEmpty, "horizons must not be empty")
    // For an ATR with period n we need at least n TR values -> n < candles.length
    require(horizons.forall(n => n < candles.length),
      s"Not enough candles: need at least max(horizons) + 1 closes (got ${candles.length})")

    val highs = candles.map(_.high)
    val lows  = candles.map(_.low)
    val closes = candles.map(_.close)

    // Compute true ranges for indices 1 .. L-1 (length = candles.length - 1)
    val trueRanges: Seq[Double] = (1 until candles.length).map { i =>
      val high = highs(i)
      val low  = lows(i)
      val prevClose = closes(i - 1)
      val tr1 = high - low
      val tr2 = math.abs(high - prevClose)
      val tr3 = math.abs(low - prevClose)
      Seq(tr1, tr2, tr3).max
    }

    // For each horizon n, take the last n TR values and average them
    horizons.map { n =>
      val window = trueRanges.takeRight(n)
      if (window.isEmpty) 0.0f
      else (window.sum / window.size).toFloat
    }
  }

  // ========== NEW FEATURES FOR SHORT-TERM TRADING ==========

  // Time components: encode time of day cyclically
  private def computeTimeComponents(candle: Candle): Seq[Float] = {
    val time = candle.timestamp.toLocalTime
    val minuteOfDay = time.getHour * 60 + time.getMinute
    
    // Encode as sin/cos for cyclical nature (0-1440 minutes in a day)
    val radians = (minuteOfDay.toDouble / 1440.0) * 2.0 * math.Pi
    Seq(
      math.sin(radians).toFloat,
      math.cos(radians).toFloat
    )
  }

  // Candle body features
  private def computeCandleBodyRatio(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.0f
    else (math.abs(candle.close - candle.open) / range).toFloat
  }

  private def computeCandlePositionInRange(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.5f  // midpoint if no range
    else ((candle.close - candle.low) / range).toFloat
  }

  private def computeUpperWickRatio(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.0f
    else {
      val topOfBody = math.max(candle.open, candle.close)
      ((candle.high - topOfBody) / range).toFloat
    }
  }

  private def computeLowerWickRatio(candle: Candle): Float = {
    val range = candle.high - candle.low
    if (range == 0.0) 0.0f
    else {
      val bottomOfBody = math.min(candle.open, candle.close)
      ((bottomOfBody - candle.low) / range).toFloat
    }
  }

  // Time-of-day features (assumes US market hours: 9:30 AM - 4:00 PM ET)
  private def computeMinutesSinceOpen(candle: Candle): Float = {
    val time = candle.timestamp.toLocalTime
    val marketOpen = java.time.LocalTime.of(9, 30)
    val minutesSinceOpen = java.time.Duration.between(marketOpen, time).toMinutes
    minutesSinceOpen.toFloat
  }

  private def computeMinutesUntilClose(candle: Candle): Float = {
    val time = candle.timestamp.toLocalTime
    val marketClose = java.time.LocalTime.of(16, 0)
    val minutesUntilClose = java.time.Duration.between(time, marketClose).toMinutes
    minutesUntilClose.toFloat
  }

  // Recent candle patterns
  private def computeRecentCandleDirections(candles: Seq[Candle], n: Int): Seq[Float] = {
    if (candles.length < n) return Seq.fill(n)(0.0f)
    candles.takeRight(n).map { c =>
      if (c.close > c.open) 1.0f
      else if (c.close < c.open) -1.0f
      else 0.0f
    }
  }

  private def computeRecentCandleSizes(candles: Seq[Candle], n: Int): Seq[Float] = {
    if (candles.length < n) return Seq.fill(n)(0.0f)
    candles.takeRight(n).map { c =>
      val bodySize = math.abs(c.close - c.open)
      (bodySize / c.close).toFloat  // as percentage of price
    }
  }

  // Momentum acceleration (rate of change of momentum)
  private def computeMomentumAcceleration(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    if (candles.length <= horizons.max + 1) return Seq.fill(horizons.length)(0.0f)
    
    horizons.map { h =>
      // Current momentum
      val currentMom = (candles.last.close - candles(candles.length - 1 - h).close) / candles(candles.length - 1 - h).close
      
      // Previous momentum (h periods ago)
      if (candles.length > h * 2) {
        val prevMom = (candles(candles.length - 1 - h).close - candles(candles.length - 1 - 2 * h).close) / candles(candles.length - 1 - 2 * h).close
        (currentMom - prevMom).toFloat
      } else {
        0.0f
      }
    }
  }

  // Volume trend: is volume increasing or decreasing over recent periods?
  private def computeVolumeTrend(candles: Seq[Candle], n: Int): Float = {
    if (candles.length < n + 1) return 0.0f
    
    val volumes = candles.takeRight(n + 1).map(_.volume.toDouble)
    
    // Simple linear regression slope
    val indices = (0 until volumes.length).map(_.toDouble)
    val meanX = indices.sum / indices.length
    val meanY = volumes.sum / volumes.length
    
    val numerator = indices.zip(volumes).map { case (x, y) => (x - meanX) * (y - meanY) }.sum
    val denominator = indices.map(x => math.pow(x - meanX, 2)).sum
    
    if (denominator == 0.0) 0.0f
    else (numerator / denominator).toFloat
  }

  // Relative volume: current volume vs recent average
  private def computeRelativeVolume(candles: Seq[Candle], n: Int): Float = {
    if (candles.length < n + 1) return 1.0f
    
    val recentVolumes = candles.takeRight(n + 1).init  // exclude current candle
    val avgVolume = if (recentVolumes.isEmpty) 1.0 else recentVolumes.map(_.volume).sum / recentVolumes.length.toDouble
    
    if (avgVolume == 0.0) 1.0f
    else (candles.last.volume / avgVolume).toFloat
  }

  // ATR as percentage of current price
  private def computeAtrPercent(candles: Seq[Candle], n: Int): Float = {
    if (candles.length < n + 1) return 0.0f
    
    val atrValue = computeAtr(candles, Seq(n)).head
    val currentPrice = candles.last.close
    
    if (currentPrice == 0.0) 0.0f
    else (atrValue / currentPrice.toFloat)
  }

  // Distance to recent high/low
  private def computeDistanceToRecentHigh(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    if (candles.isEmpty) return Seq.fill(horizons.length)(0.0f)
    
    val currentPrice = candles.last.close
    
    horizons.map { n =>
      if (candles.length < n) return 0.0f
      val recentHigh = candles.takeRight(n).map(_.high).max
      ((currentPrice - recentHigh) / recentHigh).toFloat
    }
  }

  private def computeDistanceToRecentLow(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    if (candles.isEmpty) return Seq.fill(horizons.length)(0.0f)
    
    val currentPrice = candles.last.close
    
    horizons.map { n =>
      if (candles.length < n) return 0.0f
      val recentLow = candles.takeRight(n).map(_.low).min
      ((currentPrice - recentLow) / recentLow).toFloat
    }
  }

  // Price percentile rank: where is current price in recent range?
  private def computePricePercentileRank(candles: Seq[Candle], horizons: Seq[Int]): Seq[Float] = {
    if (candles.isEmpty) return Seq.fill(horizons.length)(0.5f)
    
    val currentPrice = candles.last.close
    
    horizons.map { n =>
      if (candles.length < n) return 0.5f
      
      val recentPrices = candles.takeRight(n).map(_.close)
      val belowCurrent = recentPrices.count(_ <= currentPrice)
      
      (belowCurrent.toFloat / recentPrices.length.toFloat)
    }
  }

  // Trend alignment: are trends across different timeframes aligned?
  private def computeTrendAlignment(candles: Seq[Candle], horizonPairs: Seq[(Int, Int)]): Seq[Float] = {
    if (candles.isEmpty) return Seq.fill(horizonPairs.length)(0.0f)
    
    horizonPairs.map { case (short, long) =>
      if (candles.length <= long) return 0.0f
      
      val shortMom = computeMomentum(candles, Seq(short)).head
      val longMom = computeMomentum(candles, Seq(long)).head
      
      // Return 1.0 if both positive, -1.0 if both negative, 0.0 if misaligned
      if (shortMom > 0 && longMom > 0) 1.0f
      else if (shortMom < 0 && longMom < 0) -1.0f
      else 0.0f
    }
  }
}
