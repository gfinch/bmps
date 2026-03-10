package bmps.console

import cats.effect.{IO, IOApp, ExitCode}
import cats.implicits._
import cats.effect.unsafe.implicits.global
import bmps.core.io.ParquetSource
import bmps.core.models.{Candle, CandleDuration}
import bmps.core.services.analysis.{Trend, Volatility, Momentum, TrendAnalysis, VolatilityAnalysis, MomentumAnalysis, RegimeDetection, RegimeAnalysis, MarketRegime}
import java.io.{BufferedWriter, FileWriter, File}
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.io.Source
import scala.util.{Try, Using}

/**
 * Console app to compute technical indicators at each order's entry timestamp.
 * 
 * Uses historical 1-minute candle data from parquet to retroactively compute
 * TrendAnalysis (ADX, plusDI, minusDI) and VolatilityAnalysis (Keltner, Bollinger)
 * at the exact moment of each entry.
 * 
 * Usage: sbt "console/runMain bmps.console.OrderIndicatorAnalyzer <orders.csv> [output.csv]"
 * 
 * Input CSV format (from download_orders.py):
 *   Open Time (ET), Close Time (ET), Trading Date, Direction, Entry Price, 
 *   Exit Price, Profit/Loss, Daily Running Total, Overall Running Total, Status
 * 
 * Output CSV adds:
 *   Duration (min), ADX, PlusDI, MinusDI, Trend Direction, Trend Strength,
 *   Keltner Upper, Keltner Middle, Keltner Lower, Entry vs Keltner,
 *   Bollinger Upper, Bollinger Lower, Volatility Level
 */
object OrderIndicatorAnalyzer extends IOApp {

  private val ET = ZoneId.of("America/New_York")
  private val inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  
  // How many candles to load before entry for indicator calculation
  private val LOOKBACK_CANDLES = 100
  
  // Minimum lookback period (60 min = 1 hour before entry)
  private val MIN_LOOKBACK_MS = 60 * 60 * 1000L
  
  case class OrderEntry(
    openTimeET: String,
    closeTimeET: String,
    tradingDate: String,
    direction: String,
    entryPrice: Double,
    exitPrice: Double,
    profitLoss: Double,
    status: String,
    openTimestampMs: Long,
    closeTimestampMs: Long
  ) {
    def durationMinutes: Long = (closeTimestampMs - openTimestampMs) / 60000
    def isQuickStop: Boolean = durationMinutes < 15
    def isWinner: Boolean = profitLoss > 0
  }
  
  case class OrderWithIndicators(
    order: OrderEntry,
    trend: Option[TrendAnalysis],
    volatility: Option[VolatilityAnalysis],
    momentum: Option[MomentumAnalysis],
    regime: Option[RegimeAnalysis],
    entryVsKeltner: String,  // "Above Upper", "Below Lower", "Inside"
    relativeVolume: Option[Double]  // Current candle volume / avg of previous 20 candles
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val inputFile = args.headOption.getOrElse("orders.csv")
    val outputFile = args.lift(1).getOrElse("orders_with_indicators.csv")
    
    for {
      _ <- IO.println("=" * 80)
      _ <- IO.println("ORDER INDICATOR ANALYZER")
      _ <- IO.println("=" * 80)
      _ <- IO.println(s"Input:  $inputFile")
      _ <- IO.println(s"Output: $outputFile")
      _ <- IO.println("")
      
      orders <- IO(parseOrdersCsv(inputFile))
      _ <- IO.println(s"Loaded ${orders.size} orders")
      
      // Filter to orders within parquet data range
      validOrders = orders.filter(o => o.openTimestampMs > 0)
      _ <- IO.println(s"Valid orders with timestamps: ${validOrders.size}")
      
      // Find timestamp range
      minTs = validOrders.map(_.openTimestampMs).min
      maxTs = validOrders.map(_.openTimestampMs).max
      _ <- IO.println(s"Date range: ${Instant.ofEpochMilli(minTs)} to ${Instant.ofEpochMilli(maxTs)}")
      _ <- IO.println("")
      
      // Process orders and compute indicators
      _ <- IO.println("Computing technical indicators at each entry...")
      results <- processOrders(validOrders)
      
      _ <- IO.println(s"Successfully computed indicators for ${results.count(_.trend.isDefined)} orders")
      _ <- IO.println("")
      
      // Write output
      _ <- IO(writeOutputCsv(results, outputFile))
      _ <- IO.println(s"Written to: $outputFile")
      _ <- IO.println("")
      
      // Print summary analysis
      _ <- printAnalysisSummary(results)
      
    } yield ExitCode.Success
  }.handleErrorWith { err =>
    IO.println(s"Error: ${err.getMessage}") *>
    IO(err.printStackTrace()) *>
    IO.pure(ExitCode.Error)
  }

  private def parseOrdersCsv(path: String): List[OrderEntry] = {
    Using(Source.fromFile(path)) { source =>
      source.getLines().drop(1).flatMap { line =>
        Try {
          val parts = line.split(",", -1).map(_.trim)
          if (parts.length >= 10) {
            val openTimeET = parts(0)
            val closeTimeET = parts(1)
            val tradingDate = parts(2)
            val direction = parts(3)
            val entryPrice = parts(4).toDouble
            val exitPrice = parts(5).toDouble
            val profitLoss = parts(6).replace("$", "").replace(",", "").toDouble
            val status = parts(9)
            
            val openTs = parseETTimestamp(openTimeET)
            val closeTs = parseETTimestamp(closeTimeET)
            
            Some(OrderEntry(
              openTimeET, closeTimeET, tradingDate, direction,
              entryPrice, exitPrice, profitLoss, status,
              openTs, closeTs
            ))
          } else None
        }.toOption.flatten
      }.toList
    }.getOrElse(List.empty)
  }
  
  private def parseETTimestamp(timeStr: String): Long = {
    if (timeStr.isEmpty) return 0L
    Try {
      val ldt = LocalDateTime.parse(timeStr, inputFormatter)
      ldt.atZone(ET).toInstant.toEpochMilli
    }.getOrElse(0L)
  }

  private def processOrders(orders: List[OrderEntry]): IO[List[OrderWithIndicators]] = {
    val trend = new Trend()
    val volatility = new Volatility()
    val momentum = new Momentum()
    val parquetSource = new ParquetSource(CandleDuration.OneMinute)
    
    // Process in batches to avoid memory issues
    val batchSize = 50
    val batches = orders.grouped(batchSize).toList
    
    batches.zipWithIndex.foldLeft(IO.pure(List.empty[OrderWithIndicators])) { case (accIO, (batch, batchIdx)) =>
      for {
        acc <- accIO
        _ <- IO.println(s"Processing batch ${batchIdx + 1}/${batches.size}...")
        batchResults <- processBatch(batch, parquetSource, trend, volatility, momentum)
      } yield acc ++ batchResults
    }
  }
  
  private def processBatch(
    orders: List[OrderEntry],
    parquetSource: ParquetSource,
    trend: Trend,
    volatility: Volatility,
    momentum: Momentum
  ): IO[List[OrderWithIndicators]] = {
    val regimeDetector = new RegimeDetection()
    
    IO {
      orders.map { order =>
        // Load candles ending at the order entry time
        val endMs = order.openTimestampMs
        val startMs = endMs - (LOOKBACK_CANDLES * 60 * 1000L) - MIN_LOOKBACK_MS
        
        // Synchronously collect candles (blocking for simplicity in batch processing)
        val candles = parquetSource.candlesInRangeStream(startMs, endMs)
          .compile.toList.unsafeRunSync()
          .filter(_.duration == CandleDuration.OneMinute)
          .sortBy(_.timestamp)
        
        if (candles.size >= 30) {
          val trendAnalysis = Try(trend.doTrendAnalysis(candles)).toOption
          val volAnalysis = Try(volatility.doVolatilityAnalysis(candles)).toOption
          val momAnalysis = Try(momentum.doMomentumAnalysis(candles)).toOption
          
          // Compute regime: need multiple analyses over time
          // Use sliding windows of candles to get 10 recent analyses
          val regimeAnalysis = Try {
            if (candles.size >= 50) {
              val recentTrends = (0 until 10).flatMap { i =>
                val windowEnd = candles.size - i * 5
                if (windowEnd >= 30) {
                  val windowCandles = candles.take(windowEnd)
                  Try(trend.doTrendAnalysis(windowCandles)).toOption
                } else None
              }.toList.reverse
              
              val recentVols = (0 until 10).flatMap { i =>
                val windowEnd = candles.size - i * 5
                if (windowEnd >= 30) {
                  val windowCandles = candles.take(windowEnd)
                  Try(volatility.doVolatilityAnalysis(windowCandles)).toOption
                } else None
              }.toList.reverse
              
              if (recentTrends.size >= 5 && recentVols.size >= 5) {
                Some(regimeDetector.detectRegime(recentTrends, recentVols, None))
              } else None
            } else None
          }.toOption.flatten
          
          // Calculate relative volume: last candle volume / avg of previous 20 candles
          val relVol = if (candles.size >= 21) {
            val lastVol = candles.last.volume.toDouble
            val prevCandles = candles.dropRight(1).takeRight(20)
            val avgVol = prevCandles.map(_.volume).sum.toDouble / prevCandles.size
            if (avgVol > 0) Some(lastVol / avgVol) else None
          } else None
          
          val entryVsKeltner = volAnalysis.map { v =>
            val entry = order.entryPrice
            if (entry > v.keltnerChannels.upperBand) "Above Upper"
            else if (entry < v.keltnerChannels.lowerBand) "Below Lower"
            else "Inside"
          }.getOrElse("Unknown")
          
          OrderWithIndicators(order, trendAnalysis, volAnalysis, momAnalysis, regimeAnalysis, entryVsKeltner, relVol)
        } else {
          OrderWithIndicators(order, None, None, None, None, "Insufficient Data", None)
        }
      }
    }
  }

  private def writeOutputCsv(results: List[OrderWithIndicators], path: String): Unit = {
    val writer = new BufferedWriter(new FileWriter(path))
    try {
      // Header
      writer.write(List(
        "Open Time (ET)", "Close Time (ET)", "Trading Date", "Direction",
        "Entry Price", "Exit Price", "Profit/Loss", "Status",
        "Duration (min)", "Is Quick Stop",
        "ADX", "Plus DI", "Minus DI", "Trend Direction", "Trend Strength",
        "Is Strong Trend", "Is Golden Cross", "Is Death Cross",
        "Keltner Upper", "Keltner Middle", "Keltner Lower", "Entry vs Keltner",
        "Bollinger Upper", "Bollinger Lower", "BB Bandwidth", "Is Squeezing",
        "Volatility Level", "ATR"
      ).mkString(","))
      writer.newLine()
      
      results.foreach { r =>
        val o = r.order
        val t = r.trend
        val v = r.volatility
        
        val row = List(
          o.openTimeET,
          o.closeTimeET,
          o.tradingDate,
          o.direction,
          f"${o.entryPrice}%.2f",
          f"${o.exitPrice}%.2f",
          f"${o.profitLoss}%.2f",
          o.status,
          o.durationMinutes.toString,
          o.isQuickStop.toString,
          t.map(x => f"${x.adx}%.2f").getOrElse(""),
          t.map(x => f"${x.plusDI}%.2f").getOrElse(""),
          t.map(x => f"${x.minusDI}%.2f").getOrElse(""),
          t.map(_.direction.toString).getOrElse(""),
          t.map(_.trendStrength).getOrElse(""),
          t.map(_.isStrongTrend.toString).getOrElse(""),
          t.map(_.isGoldenCross.toString).getOrElse(""),
          t.map(_.isDeathCross.toString).getOrElse(""),
          v.map(x => f"${x.keltnerChannels.upperBand}%.2f").getOrElse(""),
          v.map(x => f"${x.keltnerChannels.centerLine}%.2f").getOrElse(""),
          v.map(x => f"${x.keltnerChannels.lowerBand}%.2f").getOrElse(""),
          r.entryVsKeltner,
          v.map(x => f"${x.bollingerBands.upperBand}%.2f").getOrElse(""),
          v.map(x => f"${x.bollingerBands.lowerBand}%.2f").getOrElse(""),
          v.map(x => f"${x.bollingerBands.bandwidth}%.4f").getOrElse(""),
          v.map(_.bollingerBands.isSqueezing.toString).getOrElse(""),
          v.map(_.overallVolatility).getOrElse(""),
          v.map(x => f"${x.trueRange.atr}%.2f").getOrElse("")
        )
        
        writer.write(row.mkString(","))
        writer.newLine()
      }
    } finally {
      writer.close()
    }
  }

  // Helper functions for analysis - defined at object level to avoid large for-comprehension
  private def diSpread(r: OrderWithIndicators): Double = 
    r.trend.map(t => math.abs(t.plusDI - t.minusDI)).getOrElse(0.0)
  
  private def atrValue(r: OrderWithIndicators): Double = 
    r.volatility.map(_.trueRange.atr).getOrElse(0.0)
  
  private def kcWidth(r: OrderWithIndicators): Double = 
    r.volatility.map(_.keltnerChannels.channelWidth).getOrElse(0.0)
  
  private def rsiValue(r: OrderWithIndicators): Double = 
    r.momentum.map(_.rsi).getOrElse(50.0)
  
  private def cciValue(r: OrderWithIndicators): Double = 
    r.momentum.map(_.cci).getOrElse(0.0)
  
  private def stochK(r: OrderWithIndicators): Double = 
    r.momentum.map(_.stochastics.percentK).getOrElse(50.0)
  
  private def relVol(r: OrderWithIndicators): Double = 
    r.relativeVolume.getOrElse(1.0)
  
  private def trendScore(r: OrderWithIndicators): Double =
    r.regime.map(_.trendScore).getOrElse(50.0)
  
  private def volatilityScore(r: OrderWithIndicators): Double =
    r.regime.map(_.volatilityScore).getOrElse(50.0)
  
  private def regimeConfidence(r: OrderWithIndicators): Double =
    r.regime.map(_.confidence).getOrElse(0.0)

  private def printAnalysisSummary(results: List[OrderWithIndicators]): IO[Unit] = {
    val withTrend = results.filter(_.trend.isDefined)
    if (withTrend.isEmpty) {
      return IO.println("No orders with computed indicators")
    }
    
    val quickStops = withTrend.filter(_.order.isQuickStop)
    val winners1h = withTrend.filter(r => r.order.durationMinutes >= 60 && r.order.isWinner)
    val longOrders = withTrend.filter(_.order.direction == "Long")
    val shortOrders = withTrend.filter(_.order.direction == "Short")
    
    for {
      _ <- printBasicStats(withTrend, quickStops, winners1h)
      _ <- printTrendAnalysis(withTrend, quickStops, winners1h)
      _ <- printMomentumAnalysis(withTrend, quickStops, winners1h, longOrders, shortOrders)
      _ <- printRegimeAnalysis(withTrend, quickStops, winners1h)
      _ <- printPeriodComparison(withTrend)
      _ <- printFilterSummary(withTrend, longOrders, shortOrders)
    } yield ()
  }

  private def printBasicStats(withTrend: List[OrderWithIndicators], 
                              quickStops: List[OrderWithIndicators], 
                              winners1h: List[OrderWithIndicators]): IO[Unit] = {
    def avgAdx(orders: List[OrderWithIndicators]): Double = {
      val adxValues = orders.flatMap(_.trend.map(_.adx))
      if (adxValues.nonEmpty) adxValues.sum / adxValues.size else 0.0
    }
    
    def avgPlusDI(orders: List[OrderWithIndicators]): Double = {
      val values = orders.flatMap(_.trend.map(_.plusDI))
      if (values.nonEmpty) values.sum / values.size else 0.0
    }
    
    def avgMinusDI(orders: List[OrderWithIndicators]): Double = {
      val values = orders.flatMap(_.trend.map(_.minusDI))
      if (values.nonEmpty) values.sum / values.size else 0.0
    }
    
    def strongTrendPct(orders: List[OrderWithIndicators]): Double = {
      val strong = orders.count(_.trend.exists(_.isStrongTrend))
      if (orders.nonEmpty) strong.toDouble / orders.size * 100 else 0.0
    }
    
    def avgRelVol(orders: List[OrderWithIndicators]): Double = {
      val values = orders.flatMap(_.relativeVolume)
      if (values.nonEmpty) values.sum / values.size else 0.0
    }

    for {
      _ <- IO.println("=" * 80)
      _ <- IO.println("INDICATOR ANALYSIS SUMMARY")
      _ <- IO.println("=" * 80)
      _ <- IO.println("")
      _ <- IO.println(s"Total orders analyzed: ${withTrend.size}")
      _ <- IO.println(s"Quick stops (<15 min): ${quickStops.size}")
      _ <- IO.println(s"Winners 1h+: ${winners1h.size}")
      _ <- IO.println("")
      _ <- IO.println("--- ADX COMPARISON ---")
      _ <- IO.println(f"Quick stops avg ADX: ${avgAdx(quickStops)}%.2f")
      _ <- IO.println(f"Winners 1h+ avg ADX: ${avgAdx(winners1h)}%.2f")
      _ <- IO.println(f"All orders avg ADX:  ${avgAdx(withTrend)}%.2f")
      _ <- IO.println("")
      _ <- IO.println("--- TREND STRENGTH AT ENTRY ---")
      _ <- IO.println(f"Quick stops with strong trend (ADX>25): ${strongTrendPct(quickStops)}%.1f%%")
      _ <- IO.println(f"Winners 1h+ with strong trend (ADX>25): ${strongTrendPct(winners1h)}%.1f%%")
      _ <- IO.println("")
      _ <- IO.println("--- PLUS DI / MINUS DI ---")
      _ <- IO.println(f"Quick stops - PlusDI: ${avgPlusDI(quickStops)}%.2f, MinusDI: ${avgMinusDI(quickStops)}%.2f")
      _ <- IO.println(f"Winners 1h+ - PlusDI: ${avgPlusDI(winners1h)}%.2f, MinusDI: ${avgMinusDI(winners1h)}%.2f")
      _ <- IO.println("")
      _ <- IO.println("--- RELATIVE VOLUME ANALYSIS ---")
      _ <- IO.println("(Relative Volume = entry candle volume / avg of prior 20 candles)")
      _ <- IO.println(f"Quick stops avg relative volume: ${avgRelVol(quickStops)}%.2f")
      _ <- IO.println(f"Winners 1h+ avg relative volume: ${avgRelVol(winners1h)}%.2f")
      _ <- IO.println(f"All orders avg relative volume:  ${avgRelVol(withTrend)}%.2f")
      // Volume buckets
      highVol = withTrend.filter(r => r.relativeVolume.exists(_ > 1.5))
      lowVol = withTrend.filter(r => r.relativeVolume.exists(_ < 0.5))
      normalVol = withTrend.filter(r => r.relativeVolume.exists(v => v >= 0.5 && v <= 1.5))
      _ <- IO.println(f"High volume (>1.5x): ${highVol.size} trades, P/L: $$${highVol.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (highVol.nonEmpty) highVol.count(_.order.isWinner).toDouble / highVol.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"Normal volume (0.5-1.5x): ${normalVol.size} trades, P/L: $$${normalVol.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (normalVol.nonEmpty) normalVol.count(_.order.isWinner).toDouble / normalVol.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"Low volume (<0.5x): ${lowVol.size} trades, P/L: $$${lowVol.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (lowVol.nonEmpty) lowVol.count(_.order.isWinner).toDouble / lowVol.size * 100 else 0.0}%.1f%%")
      _ <- IO.println("")
    } yield ()
  }

  private def printTrendAnalysis(withTrend: List[OrderWithIndicators],
                                  quickStops: List[OrderWithIndicators],
                                  winners1h: List[OrderWithIndicators]): IO[Unit] = {
    val isDiAligned = (r: OrderWithIndicators) => r.trend.exists { t =>
      (r.order.direction == "Long" && t.plusDI > t.minusDI) ||
      (r.order.direction == "Short" && t.minusDI > t.plusDI)
    }
    
    val avgQsSpread = if (quickStops.nonEmpty) quickStops.map(diSpread).sum / quickStops.size else 0.0
    val avgWinSpread = if (winners1h.nonEmpty) winners1h.map(diSpread).sum / winners1h.size else 0.0
    val avgAllSpread = if (withTrend.nonEmpty) withTrend.map(diSpread).sum / withTrend.size else 0.0
    
    val avgQsAtr = if (quickStops.nonEmpty) quickStops.map(atrValue).sum / quickStops.size else 0.0
    val avgWinAtr = if (winners1h.nonEmpty) winners1h.map(atrValue).sum / winners1h.size else 0.0

    for {
      _ <- IO.println("--- DI ALIGNMENT ANALYSIS ---")
      _ <- IO.println("(Aligned = Long when PlusDI > MinusDI, Short when MinusDI > PlusDI)")
      qsAligned = quickStops.filter(isDiAligned)
      qsNotAligned = quickStops.filterNot(isDiAligned)
      _ <- IO.println(f"Quick Stops - Aligned: ${qsAligned.size} (P/L: $$${qsAligned.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"Quick Stops - NOT Aligned: ${qsNotAligned.size} (P/L: $$${qsNotAligned.map(_.order.profitLoss).sum}%.0f)")
      winAligned = winners1h.filter(isDiAligned)
      winNotAligned = winners1h.filterNot(isDiAligned)
      _ <- IO.println(f"Winners 1h+ - Aligned: ${winAligned.size} (P/L: $$${winAligned.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"Winners 1h+ - NOT Aligned: ${winNotAligned.size} (P/L: $$${winNotAligned.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println("")
      
      _ <- IO.println("--- DI SPREAD ANALYSIS ---")
      _ <- IO.println(f"Quick stops avg DI spread: $avgQsSpread%.2f")
      _ <- IO.println(f"Winners 1h+ avg DI spread: $avgWinSpread%.2f")
      _ <- IO.println(f"All orders avg DI spread:  $avgAllSpread%.2f")
      _ <- IO.println("")
      
      _ <- IO.println("--- ATR ANALYSIS ---")
      _ <- IO.println(f"Quick stops avg ATR: $avgQsAtr%.2f")
      _ <- IO.println(f"Winners 1h+ avg ATR: $avgWinAtr%.2f")
      _ <- IO.println("")
      
      _ <- IO.println("--- KC WIDTH ANALYSIS ---")
      avgQsKcWidth = if (quickStops.nonEmpty) quickStops.map(kcWidth).sum / quickStops.size else 0.0
      avgWinKcWidth = if (winners1h.nonEmpty) winners1h.map(kcWidth).sum / winners1h.size else 0.0
      _ <- IO.println(f"Quick stops avg KC width: $avgQsKcWidth%.4f")
      _ <- IO.println(f"Winners 1h+ avg KC width: $avgWinKcWidth%.4f")
      _ <- IO.println("")
    } yield ()
  }

  private def printMomentumAnalysis(withTrend: List[OrderWithIndicators],
                                     quickStops: List[OrderWithIndicators],
                                     winners1h: List[OrderWithIndicators],
                                     longOrders: List[OrderWithIndicators],
                                     shortOrders: List[OrderWithIndicators]): IO[Unit] = {
    val avgQsRsi = if (quickStops.nonEmpty) quickStops.map(rsiValue).sum / quickStops.size else 50.0
    val avgWinRsi = if (winners1h.nonEmpty) winners1h.map(rsiValue).sum / winners1h.size else 50.0
    val avgQsCci = if (quickStops.nonEmpty) quickStops.map(cciValue).sum / quickStops.size else 0.0
    val avgWinCci = if (winners1h.nonEmpty) winners1h.map(cciValue).sum / winners1h.size else 0.0
    val avgQsStoch = if (quickStops.nonEmpty) quickStops.map(stochK).sum / quickStops.size else 50.0
    val avgWinStoch = if (winners1h.nonEmpty) winners1h.map(stochK).sum / winners1h.size else 50.0

    for {
      _ <- IO.println("--- RSI ANALYSIS ---")
      _ <- IO.println(f"Quick stops avg RSI: $avgQsRsi%.1f")
      _ <- IO.println(f"Winners 1h+ avg RSI: $avgWinRsi%.1f")
      _ <- IO.println("")
      
      _ <- IO.println("RSI DIRECTION ALIGNMENT:")
      longHighRsi = longOrders.filter(r => rsiValue(r) >= 50)
      longLowRsi = longOrders.filter(r => rsiValue(r) < 50)
      shortHighRsi = shortOrders.filter(r => rsiValue(r) >= 50)
      shortLowRsi = shortOrders.filter(r => rsiValue(r) < 50)
      _ <- IO.println(f"  Longs with RSI >= 50: ${longHighRsi.size} (P/L: $$${longHighRsi.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"  Longs with RSI < 50:  ${longLowRsi.size} (P/L: $$${longLowRsi.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"  Shorts with RSI < 50: ${shortLowRsi.size} (P/L: $$${shortLowRsi.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"  Shorts with RSI >= 50: ${shortHighRsi.size} (P/L: $$${shortHighRsi.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println("")
      
      _ <- IO.println("--- CCI ANALYSIS ---")
      _ <- IO.println(f"Quick stops avg CCI: $avgQsCci%.1f")
      _ <- IO.println(f"Winners 1h+ avg CCI: $avgWinCci%.1f")
      longPosCci = longOrders.filter(r => cciValue(r) > 0)
      longNegCci = longOrders.filter(r => cciValue(r) <= 0)
      shortPosCci = shortOrders.filter(r => cciValue(r) > 0)
      shortNegCci = shortOrders.filter(r => cciValue(r) <= 0)
      _ <- IO.println(f"  Longs with CCI > 0: ${longPosCci.size} (P/L: $$${longPosCci.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"  Longs with CCI <= 0: ${longNegCci.size} (P/L: $$${longNegCci.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"  Shorts with CCI < 0: ${shortNegCci.size} (P/L: $$${shortNegCci.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println(f"  Shorts with CCI >= 0: ${shortPosCci.size} (P/L: $$${shortPosCci.map(_.order.profitLoss).sum}%.0f)")
      _ <- IO.println("")
      
      _ <- IO.println("--- STOCHASTICS ANALYSIS ---")
      _ <- IO.println(f"Quick stops avg Stoch %%K: $avgQsStoch%.1f")
      _ <- IO.println(f"Winners 1h+ avg Stoch %%K: $avgWinStoch%.1f")
      stochOversold = withTrend.filter(r => r.momentum.exists(_.stochastics.isOversold))
      stochOverbought = withTrend.filter(r => r.momentum.exists(_.stochastics.isOverbought))
      stochBullishCross = withTrend.filter(r => r.momentum.exists(_.stochastics.isBullishCrossover))
      _ <- IO.println(f"Oversold (K<20):  ${stochOversold.size} trades, P/L: $$${stochOversold.map(_.order.profitLoss).sum}%.0f")
      _ <- IO.println(f"Overbought (K>80): ${stochOverbought.size} trades, P/L: $$${stochOverbought.map(_.order.profitLoss).sum}%.0f")
      _ <- IO.println(f"Bullish Crossover (K>D): ${stochBullishCross.size} trades, P/L: $$${stochBullishCross.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (stochBullishCross.nonEmpty) stochBullishCross.count(_.order.isWinner).toDouble / stochBullishCross.size * 100 else 0.0}%.1f%%")
      _ <- IO.println("")
      
      // Williams %R
      _ <- IO.println("--- WILLIAMS %R ANALYSIS ---")
      avgQsWilliams = if (quickStops.nonEmpty) quickStops.flatMap(_.momentum.map(_.williamsR)).sum / quickStops.size else -50.0
      avgWinWilliams = if (winners1h.nonEmpty) winners1h.flatMap(_.momentum.map(_.williamsR)).sum / winners1h.size else -50.0
      _ <- IO.println(f"Quick stops avg Williams %%R: $avgQsWilliams%.1f")
      _ <- IO.println(f"Winners 1h+ avg Williams %%R: $avgWinWilliams%.1f")
      willOversold = withTrend.filter(r => r.momentum.exists(_.williamsROversold))
      willOverbought = withTrend.filter(r => r.momentum.exists(_.williamsROverbought))
      _ <- IO.println(f"Oversold (R<-80):  ${willOversold.size} trades, P/L: $$${willOversold.map(_.order.profitLoss).sum}%.0f")
      _ <- IO.println(f"Overbought (R>-20): ${willOverbought.size} trades, P/L: $$${willOverbought.map(_.order.profitLoss).sum}%.0f")
      _ <- IO.println("")
      
      // Bollinger %B and Squeeze
      _ <- IO.println("--- BOLLINGER BANDS ANALYSIS ---")
      avgQsPercentB = if (quickStops.nonEmpty) quickStops.flatMap(_.volatility.map(_.bollingerBands.percentB)).sum / quickStops.size else 0.5
      avgWinPercentB = if (winners1h.nonEmpty) winners1h.flatMap(_.volatility.map(_.bollingerBands.percentB)).sum / winners1h.size else 0.5
      _ <- IO.println(f"Quick stops avg %%B: $avgQsPercentB%.2f")
      _ <- IO.println(f"Winners 1h+ avg %%B: $avgWinPercentB%.2f")
      bbSqueezing = withTrend.filter(r => r.volatility.exists(_.bollingerBands.isSqueezing))
      bbExpanding = withTrend.filter(r => r.volatility.exists(_.bollingerBands.isExpanding))
      _ <- IO.println(f"In Squeeze (low vol): ${bbSqueezing.size} trades, P/L: $$${bbSqueezing.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (bbSqueezing.nonEmpty) bbSqueezing.count(_.order.isWinner).toDouble / bbSqueezing.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"Expanding (high vol): ${bbExpanding.size} trades, P/L: $$${bbExpanding.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (bbExpanding.nonEmpty) bbExpanding.count(_.order.isWinner).toDouble / bbExpanding.size * 100 else 0.0}%.1f%%")
      _ <- IO.println("")
      
      // ATR Trend
      _ <- IO.println("--- ATR TREND ANALYSIS ---")
      atrIncreasing = withTrend.filter(r => r.volatility.exists(_.trueRange.atrTrend == "Increasing"))
      atrDecreasing = withTrend.filter(r => r.volatility.exists(_.trueRange.atrTrend == "Decreasing"))
      atrStable = withTrend.filter(r => r.volatility.exists(_.trueRange.atrTrend == "Stable"))
      _ <- IO.println(f"ATR Increasing: ${atrIncreasing.size} trades, P/L: $$${atrIncreasing.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (atrIncreasing.nonEmpty) atrIncreasing.count(_.order.isWinner).toDouble / atrIncreasing.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"ATR Decreasing: ${atrDecreasing.size} trades, P/L: $$${atrDecreasing.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (atrDecreasing.nonEmpty) atrDecreasing.count(_.order.isWinner).toDouble / atrDecreasing.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"ATR Stable: ${atrStable.size} trades, P/L: $$${atrStable.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (atrStable.nonEmpty) atrStable.count(_.order.isWinner).toDouble / atrStable.size * 100 else 0.0}%.1f%%")
      _ <- IO.println("")
      
      // Volatility Level
      _ <- IO.println("--- VOLATILITY LEVEL ANALYSIS ---")
      volLow = withTrend.filter(r => r.volatility.exists(_.trueRange.volatilityLevel == "Low"))
      volNormal = withTrend.filter(r => r.volatility.exists(_.trueRange.volatilityLevel == "Normal"))
      volHigh = withTrend.filter(r => r.volatility.exists(_.trueRange.volatilityLevel == "High"))
      volExtreme = withTrend.filter(r => r.volatility.exists(_.trueRange.volatilityLevel == "Extreme"))
      _ <- IO.println(f"Low volatility: ${volLow.size} trades, P/L: $$${volLow.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (volLow.nonEmpty) volLow.count(_.order.isWinner).toDouble / volLow.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"Normal volatility: ${volNormal.size} trades, P/L: $$${volNormal.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (volNormal.nonEmpty) volNormal.count(_.order.isWinner).toDouble / volNormal.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"High volatility: ${volHigh.size} trades, P/L: $$${volHigh.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (volHigh.nonEmpty) volHigh.count(_.order.isWinner).toDouble / volHigh.size * 100 else 0.0}%.1f%%")
      _ <- IO.println(f"Extreme volatility: ${volExtreme.size} trades, P/L: $$${volExtreme.map(_.order.profitLoss).sum}%.0f, " +
                     f"Win Rate: ${if (volExtreme.nonEmpty) volExtreme.count(_.order.isWinner).toDouble / volExtreme.size * 100 else 0.0}%.1f%%")
      _ <- IO.println("")
    } yield ()
  }

  private def printRegimeAnalysis(withTrend: List[OrderWithIndicators],
                                   quickStops: List[OrderWithIndicators],
                                   winners1h: List[OrderWithIndicators]): IO[Unit] = {
    val withRegime = withTrend.filter(_.regime.isDefined)
    
    if (withRegime.isEmpty) {
      return IO.println("--- REGIME ANALYSIS ---\nNo orders with computed regime data")
    }
    
    def regimeBreakdown(orders: List[OrderWithIndicators]): Map[MarketRegime, List[OrderWithIndicators]] = {
      orders.filter(_.regime.isDefined).groupBy(_.regime.get.regime)
    }
    
    val avgQsTrend = if (quickStops.nonEmpty) quickStops.flatMap(_.regime.map(_.trendScore)).sum / quickStops.size else 0.0
    val avgWinTrend = if (winners1h.nonEmpty) winners1h.flatMap(_.regime.map(_.trendScore)).sum / winners1h.size else 0.0
    val avgQsVol = if (quickStops.nonEmpty) quickStops.flatMap(_.regime.map(_.volatilityScore)).sum / quickStops.size else 0.0
    val avgWinVol = if (winners1h.nonEmpty) winners1h.flatMap(_.regime.map(_.volatilityScore)).sum / winners1h.size else 0.0
    val avgQsConf = if (quickStops.nonEmpty) quickStops.flatMap(_.regime.map(_.confidence)).sum / quickStops.size else 0.0
    val avgWinConf = if (winners1h.nonEmpty) winners1h.flatMap(_.regime.map(_.confidence)).sum / winners1h.size else 0.0

    for {
      _ <- IO.println("--- REGIME ANALYSIS ---")
      _ <- IO.println(s"Orders with regime data: ${withRegime.size} / ${withTrend.size}")
      _ <- IO.println("")
      
      _ <- IO.println("REGIME SCORES COMPARISON:")
      _ <- IO.println(f"Quick stops avg Trend Score: $avgQsTrend%.1f, Volatility Score: $avgQsVol%.1f, Confidence: $avgQsConf%.2f")
      _ <- IO.println(f"Winners 1h+ avg Trend Score: $avgWinTrend%.1f, Volatility Score: $avgWinVol%.1f, Confidence: $avgWinConf%.2f")
      _ <- IO.println("")
      
      _ <- IO.println("REGIME TYPE BREAKDOWN:")
      allRegimes = regimeBreakdown(withTrend)
      _ <- IO.println(f"${"Regime Type"}%-20s ${"Trades"}%-8s ${"P/L"}%-12s ${"Win Rate"}%-10s ${"QS Count"}%-10s ${"Win1h Cnt"}%-10s")
      _ <- IO.println("-" * 75)
      _ <- List(
        MarketRegime.TrendingHigh,
        MarketRegime.TrendingLow,
        MarketRegime.RangingTight,
        MarketRegime.RangingWide,
        MarketRegime.Breakout,
        MarketRegime.Unknown
      ).traverse_ { regime =>
        val orders = allRegimes.getOrElse(regime, Nil)
        val pl = orders.map(_.order.profitLoss).sum
        val wr = if (orders.nonEmpty) orders.count(_.order.isWinner).toDouble / orders.size * 100 else 0.0
        val wrStr = f"$wr%.1f%%"
        val qsCount = orders.count(_.order.isQuickStop)
        val winCount = orders.count(r => r.order.durationMinutes >= 60 && r.order.isWinner)
        IO.println(f"${regime.toString}%-20s ${orders.size}%-8d $$$pl%-11.0f $wrStr%-10s $qsCount%-10d $winCount%-10d")
      }
      _ <- IO.println("")
      
      // Trending vs Ranging comparison
      _ <- IO.println("TRENDING vs RANGING COMPARISON:")
      trending = withRegime.filter(r => r.regime.exists(_.isTrending))
      ranging = withRegime.filter(r => r.regime.exists(_.isRanging))
      trendingPL = trending.map(_.order.profitLoss).sum
      rangingPL = ranging.map(_.order.profitLoss).sum
      trendingWR = if (trending.nonEmpty) trending.count(_.order.isWinner).toDouble / trending.size * 100 else 0.0
      rangingWR = if (ranging.nonEmpty) ranging.count(_.order.isWinner).toDouble / ranging.size * 100 else 0.0
      _ <- IO.println(f"Trending: ${trending.size} trades, P/L: $$$trendingPL%.0f, Win Rate: $trendingWR%.1f%%")
      _ <- IO.println(f"Ranging:  ${ranging.size} trades, P/L: $$$rangingPL%.0f, Win Rate: $rangingWR%.1f%%")
      _ <- IO.println("")
      
      // Trend Score buckets
      _ <- IO.println("TREND SCORE BUCKETS:")
      _ <- IO.println(f"${"Trend Score"}%-15s ${"Trades"}%-8s ${"P/L"}%-12s ${"Win Rate"}%-10s")
      _ <- IO.println("-" * 50)
      trendScoreBuckets = List(
        ("Score < 30", (r: OrderWithIndicators) => trendScore(r) < 30),
        ("Score 30-50", (r: OrderWithIndicators) => trendScore(r) >= 30 && trendScore(r) < 50),
        ("Score 50-70", (r: OrderWithIndicators) => trendScore(r) >= 50 && trendScore(r) < 70),
        ("Score >= 70", (r: OrderWithIndicators) => trendScore(r) >= 70)
      )
      _ <- trendScoreBuckets.traverse_ { case (label, filter) =>
        val filtered = withRegime.filter(filter)
        val pl = filtered.map(_.order.profitLoss).sum
        val wr = if (filtered.nonEmpty) filtered.count(_.order.isWinner).toDouble / filtered.size * 100 else 0.0
        IO.println(f"$label%-15s ${filtered.size}%-8d $$$pl%-11.0f $wr%.1f%%")
      }
      _ <- IO.println("")
      
      // Regime transitioning
      _ <- IO.println("REGIME TRANSITIONING:")
      transitioning = withRegime.filter(r => r.regime.exists(_.isTransitioning))
      stable = withRegime.filter(r => r.regime.exists(!_.isTransitioning))
      transPL = transitioning.map(_.order.profitLoss).sum
      stablePL = stable.map(_.order.profitLoss).sum
      transWR = if (transitioning.nonEmpty) transitioning.count(_.order.isWinner).toDouble / transitioning.size * 100 else 0.0
      stableWR = if (stable.nonEmpty) stable.count(_.order.isWinner).toDouble / stable.size * 100 else 0.0
      _ <- IO.println(f"Transitioning: ${transitioning.size} trades, P/L: $$$transPL%.0f, Win Rate: $transWR%.1f%%")
      _ <- IO.println(f"Stable regime: ${stable.size} trades, P/L: $$$stablePL%.0f, Win Rate: $stableWR%.1f%%")
      _ <- IO.println("")
    } yield ()
  }

  private def printFilterSummary(withTrend: List[OrderWithIndicators],
                                  longOrders: List[OrderWithIndicators],
                                  shortOrders: List[OrderWithIndicators]): IO[Unit] = {
    val origWinRate = withTrend.count(_.order.isWinner).toDouble / withTrend.size * 100
    val origPL = withTrend.map(_.order.profitLoss).sum
    
    // Define all filters
    val filters: List[(String, OrderWithIndicators => Boolean)] = List(
      // DI Spread filters
      ("DI Spread >= 5", (r: OrderWithIndicators) => diSpread(r) >= 5),
      ("DI Spread >= 7.5", (r: OrderWithIndicators) => diSpread(r) >= 7.5),
      ("DI Spread >= 10", (r: OrderWithIndicators) => diSpread(r) >= 10),
      // ATR filters
      ("ATR >= 3.0", (r: OrderWithIndicators) => atrValue(r) >= 3.0),
      ("ATR >= 3.5", (r: OrderWithIndicators) => atrValue(r) >= 3.5),
      ("ATR >= 4.0", (r: OrderWithIndicators) => atrValue(r) >= 4.0),
      ("ATR >= 4.5", (r: OrderWithIndicators) => atrValue(r) >= 4.5),
      ("ATR >= 5.0", (r: OrderWithIndicators) => atrValue(r) >= 5.0),
      ("ATR >= 5.5", (r: OrderWithIndicators) => atrValue(r) >= 5.5),
      // KC Width filters
      ("KC Width >= 0.0012", (r: OrderWithIndicators) => kcWidth(r) >= 0.0012),
      ("KC Width >= 0.0014", (r: OrderWithIndicators) => kcWidth(r) >= 0.0014),
      // Volume filters
      ("Relative Volume >= 0.8", (r: OrderWithIndicators) => relVol(r) >= 0.8),
      ("Relative Volume >= 1.0", (r: OrderWithIndicators) => relVol(r) >= 1.0),
      ("Relative Volume >= 1.5", (r: OrderWithIndicators) => relVol(r) >= 1.5),
      ("Relative Volume 0.5-2.0 (normal)", (r: OrderWithIndicators) => relVol(r) >= 0.5 && relVol(r) <= 2.0),
      // RSI filters
      ("Long + RSI >= 50", (r: OrderWithIndicators) => r.order.direction == "Long" && rsiValue(r) >= 50),
      ("Short + RSI < 50", (r: OrderWithIndicators) => r.order.direction == "Short" && rsiValue(r) < 50),
      ("Direction-aligned RSI", (r: OrderWithIndicators) => 
        (r.order.direction == "Long" && rsiValue(r) >= 50) ||
        (r.order.direction == "Short" && rsiValue(r) < 50)),
      // CCI filters
      ("Long + CCI > 0", (r: OrderWithIndicators) => r.order.direction == "Long" && cciValue(r) > 0),
      ("Short + CCI < 0", (r: OrderWithIndicators) => r.order.direction == "Short" && cciValue(r) < 0),
      ("Direction-aligned CCI", (r: OrderWithIndicators) => 
        (r.order.direction == "Long" && cciValue(r) > 0) ||
        (r.order.direction == "Short" && cciValue(r) < 0)),
      // Combined filters
      ("DI >= 5 AND ATR >= 3.0", (r: OrderWithIndicators) => diSpread(r) >= 5 && atrValue(r) >= 3.0),
      ("DI >= 5 AND ATR >= 4.0", (r: OrderWithIndicators) => diSpread(r) >= 5 && atrValue(r) >= 4.0),
      ("DI >= 5 AND ATR >= 4.5", (r: OrderWithIndicators) => diSpread(r) >= 5 && atrValue(r) >= 4.5),
      ("DI >= 5 AND ATR >= 5.0", (r: OrderWithIndicators) => diSpread(r) >= 5 && atrValue(r) >= 5.0),
      ("DI >= 5 AND ATR NOT Dec AND ATR >= 4.5", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && atrValue(r) >= 4.5 && r.volatility.exists(_.trueRange.atrTrend != "Decreasing")),
      ("DI >= 5 AND ATR NOT Dec AND ATR >= 5.0", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && atrValue(r) >= 5.0 && r.volatility.exists(_.trueRange.atrTrend != "Decreasing")),
      ("DI >= 5 AND KC >= 0.0014", (r: OrderWithIndicators) => diSpread(r) >= 5 && kcWidth(r) >= 0.0014),
      ("DI >= 5 AND RelVol >= 0.8", (r: OrderWithIndicators) => diSpread(r) >= 5 && relVol(r) >= 0.8),
      ("DI >= 5 AND Dir-aligned RSI", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && (
          (r.order.direction == "Long" && rsiValue(r) >= 50) ||
          (r.order.direction == "Short" && rsiValue(r) < 50))),
      ("DI >= 5 AND Dir-aligned CCI", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && (
          (r.order.direction == "Long" && cciValue(r) > 0) ||
          (r.order.direction == "Short" && cciValue(r) < 0))),
      // Williams %R filters
      ("Williams R > -80 (not oversold)", (r: OrderWithIndicators) => 
        r.momentum.exists(_.williamsR > -80)),
      ("Williams R < -20 (overbought)", (r: OrderWithIndicators) => 
        r.momentum.exists(_.williamsR > -20)),
      ("Long + Williams R > -50", (r: OrderWithIndicators) => 
        r.order.direction == "Long" && r.momentum.exists(_.williamsR > -50)),
      ("Short + Williams R < -50", (r: OrderWithIndicators) => 
        r.order.direction == "Short" && r.momentum.exists(_.williamsR < -50)),
      // Stochastics filters
      ("Stoch bullish crossover (K>D)", (r: OrderWithIndicators) => 
        r.momentum.exists(_.stochastics.isBullishCrossover)),
      ("Long + Stoch bullish cross", (r: OrderWithIndicators) => 
        r.order.direction == "Long" && r.momentum.exists(_.stochastics.isBullishCrossover)),
      ("Short + Stoch bearish cross", (r: OrderWithIndicators) => 
        r.order.direction == "Short" && r.momentum.exists(!_.stochastics.isBullishCrossover)),
      // Bollinger %B filters
      ("BB %B 0.2-0.8 (inside bands)", (r: OrderWithIndicators) => 
        r.volatility.exists(v => v.bollingerBands.percentB >= 0.2 && v.bollingerBands.percentB <= 0.8)),
      ("NOT in BB squeeze", (r: OrderWithIndicators) => 
        r.volatility.exists(!_.bollingerBands.isSqueezing)),
      ("BB expanding (high vol)", (r: OrderWithIndicators) => 
        r.volatility.exists(_.bollingerBands.isExpanding)),
      // ATR Trend filters
      ("ATR Increasing (expanding vol)", (r: OrderWithIndicators) => 
        r.volatility.exists(_.trueRange.atrTrend == "Increasing")),
      ("ATR NOT Decreasing", (r: OrderWithIndicators) => 
        r.volatility.exists(_.trueRange.atrTrend != "Decreasing")),
      // Volatility Level filters
      ("Vol level NOT Low", (r: OrderWithIndicators) => 
        r.volatility.exists(_.trueRange.volatilityLevel != "Low")),
      ("Vol level High or Extreme", (r: OrderWithIndicators) => 
        r.volatility.exists(v => v.trueRange.volatilityLevel == "High" || v.trueRange.volatilityLevel == "Extreme")),
      // Combined with DI >= 5
      ("DI >= 5 AND NOT BB squeeze", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && r.volatility.exists(!_.bollingerBands.isSqueezing)),
      ("DI >= 5 AND ATR NOT Decreasing", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && r.volatility.exists(_.trueRange.atrTrend != "Decreasing")),
      ("DI >= 5 AND Vol NOT Low", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && r.volatility.exists(_.trueRange.volatilityLevel != "Low")),
      ("DI >= 5 AND Stoch dir-aligned", (r: OrderWithIndicators) => 
        diSpread(r) >= 5 && (
          (r.order.direction == "Long" && r.momentum.exists(_.stochastics.isBullishCrossover)) ||
          (r.order.direction == "Short" && r.momentum.exists(!_.stochastics.isBullishCrossover)))),
      // Regime filters
      ("Regime: Trending (High or Low)", (r: OrderWithIndicators) =>
        r.regime.exists(_.isTrending)),
      ("Regime: TrendingHigh only", (r: OrderWithIndicators) =>
        r.regime.exists(_.regime == MarketRegime.TrendingHigh)),
      ("Regime: TrendingLow only", (r: OrderWithIndicators) =>
        r.regime.exists(_.regime == MarketRegime.TrendingLow)),
      ("Regime: NOT Ranging", (r: OrderWithIndicators) =>
        r.regime.exists(!_.isRanging)),
      ("Regime: NOT RangingWide", (r: OrderWithIndicators) =>
        r.regime.exists(_.regime != MarketRegime.RangingWide)),
      ("Regime: NOT transitioning", (r: OrderWithIndicators) =>
        r.regime.exists(!_.isTransitioning)),
      ("Trend Score >= 40", (r: OrderWithIndicators) =>
        trendScore(r) >= 40),
      ("Trend Score >= 50", (r: OrderWithIndicators) =>
        trendScore(r) >= 50),
      ("Trend Score >= 60", (r: OrderWithIndicators) =>
        trendScore(r) >= 60),
      ("Regime confidence >= 0.3", (r: OrderWithIndicators) =>
        regimeConfidence(r) >= 0.3),
      ("Regime confidence >= 0.4", (r: OrderWithIndicators) =>
        regimeConfidence(r) >= 0.4),
      // Combined DI + Regime
      ("DI >= 5 AND Trending", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && r.regime.exists(_.isTrending)),
      ("DI >= 5 AND NOT Ranging", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && r.regime.exists(!_.isRanging)),
      ("DI >= 5 AND NOT transitioning", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && r.regime.exists(!_.isTransitioning)),
      ("DI >= 5 AND Trend Score >= 50", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && trendScore(r) >= 50),
      ("DI >= 5 AND ATR!Dec AND Trending", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && r.volatility.exists(_.trueRange.atrTrend != "Decreasing") && r.regime.exists(_.isTrending)),
      // Combined: Skip RangingWide + Stable ATR (choppy conditions)
      ("NOT (RangingWide + Stable ATR)", (r: OrderWithIndicators) =>
        !(r.regime.exists(_.regime == MarketRegime.RangingWide) && r.volatility.exists(_.trueRange.atrTrend == "Stable"))),
      ("NOT (RangingWide + ATR < 5)", (r: OrderWithIndicators) =>
        !(r.regime.exists(_.regime == MarketRegime.RangingWide) && atrValue(r) < 5.0)),
      ("DI >= 5 AND NOT (RangingWide + Stable)", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && !(r.regime.exists(_.regime == MarketRegime.RangingWide) && r.volatility.exists(_.trueRange.atrTrend == "Stable"))),
      ("DI >= 5 AND ATR!Dec AND NOT (RgWide+Stable)", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && 
        r.volatility.exists(_.trueRange.atrTrend != "Decreasing") && 
        !(r.regime.exists(_.regime == MarketRegime.RangingWide) && r.volatility.exists(_.trueRange.atrTrend == "Stable"))),
      // Require Breakout OR ATR Increasing
      ("Breakout OR ATR Increasing", (r: OrderWithIndicators) =>
        r.regime.exists(_.regime == MarketRegime.Breakout) || r.volatility.exists(_.trueRange.atrTrend == "Increasing")),
      ("DI >= 5 AND (Breakout OR ATR Inc)", (r: OrderWithIndicators) =>
        diSpread(r) >= 5 && (r.regime.exists(_.regime == MarketRegime.Breakout) || r.volatility.exists(_.trueRange.atrTrend == "Increasing")))
    )
    
    for {
      _ <- IO.println("=" * 80)
      _ <- IO.println("FILTER RECOMMENDATION SUMMARY")
      _ <- IO.println("=" * 80)
      _ <- IO.println(f"Original: ${withTrend.size} trades, P/L: $$$origPL%.0f, Win Rate: $origWinRate%.1f%%")
      _ <- IO.println("-" * 80)
      _ <- IO.println(f"${"Filter"}%-45s ${"Trades"}%-8s ${"Skip P/L"}%-12s ${"Keep P/L"}%-12s ${"WinRate"}%-8s")
      _ <- IO.println("-" * 80)
      _ <- filters.traverse_ { case (label, filter) =>
        val pass = withTrend.filter(filter)
        val fail = withTrend.filterNot(filter)
        val skipPL = fail.map(_.order.profitLoss).sum
        val keepPL = pass.map(_.order.profitLoss).sum
        val wr = if (pass.nonEmpty) pass.count(_.order.isWinner).toDouble / pass.size * 100 else 0.0
        IO.println(f"$label%-45s ${pass.size}%-8d $$$skipPL%-11.0f $$$keepPL%-11.0f $wr%.1f%%")
      }
      _ <- IO.println("")
      _ <- IO.println("=" * 80)
    } yield ()
  }

  private def printPeriodComparison(withTrend: List[OrderWithIndicators]): IO[Unit] = {
    // Split by date - January drawdown vs rest of year
    val janCutoff = "2025-02-01"
    val januaryPeriod = withTrend.filter(_.order.tradingDate < janCutoff)
    val restOfYear = withTrend.filter(_.order.tradingDate >= janCutoff)
    
    def periodStats(label: String, orders: List[OrderWithIndicators]): IO[Unit] = {
      if (orders.isEmpty) return IO.println(s"$label: No trades")
      
      val pl = orders.map(_.order.profitLoss).sum
      val wr = orders.count(_.order.isWinner).toDouble / orders.size * 100
      val qsCount = orders.count(_.order.isQuickStop)
      val qsRate = qsCount.toDouble / orders.size * 100
      val win1h = orders.count(r => r.order.durationMinutes >= 60 && r.order.isWinner)
      
      // Averages
      val avgADX = orders.flatMap(_.trend.map(_.adx)).sum / orders.count(_.trend.isDefined).max(1)
      val avgDISpread = orders.flatMap(_.trend.map(t => math.abs(t.plusDI - t.minusDI))).sum / orders.count(_.trend.isDefined).max(1)
      val avgATR = orders.flatMap(_.volatility.map(_.trueRange.atr)).sum / orders.count(_.volatility.isDefined).max(1)
      val avgKCWidth = orders.flatMap(_.volatility.map(v => v.keltnerChannels.channelWidth)).sum / orders.count(_.volatility.isDefined).max(1)
      val avgRelVol = orders.flatMap(_.relativeVolume).sum / orders.count(_.relativeVolume.isDefined).max(1)
      
      // ATR Trend breakdown
      val atrIncreasing = orders.count(r => r.volatility.exists(_.trueRange.atrTrend == "Increasing"))
      val atrDecreasing = orders.count(r => r.volatility.exists(_.trueRange.atrTrend == "Decreasing"))
      val atrStable = orders.count(r => r.volatility.exists(_.trueRange.atrTrend == "Stable"))
      
      // Volatility level breakdown
      val volLow = orders.count(r => r.volatility.exists(_.trueRange.volatilityLevel == "Low"))
      val volNormal = orders.count(r => r.volatility.exists(_.trueRange.volatilityLevel == "Normal"))
      val volHigh = orders.count(r => r.volatility.exists(_.trueRange.volatilityLevel == "High"))
      val volExtreme = orders.count(r => r.volatility.exists(_.trueRange.volatilityLevel == "Extreme"))
      
      // Regime breakdown
      val trendingHigh = orders.count(r => r.regime.exists(_.regime == MarketRegime.TrendingHigh))
      val trendingLow = orders.count(r => r.regime.exists(_.regime == MarketRegime.TrendingLow))
      val rangingWide = orders.count(r => r.regime.exists(_.regime == MarketRegime.RangingWide))
      val rangingTight = orders.count(r => r.regime.exists(_.regime == MarketRegime.RangingTight))
      val breakout = orders.count(r => r.regime.exists(_.regime == MarketRegime.Breakout))
      val transitioning = orders.count(r => r.regime.exists(_.isTransitioning))
      
      // RSI/CCI/Stochastics
      val avgRSI = orders.flatMap(_.momentum.map(_.rsi)).sum / orders.count(_.momentum.isDefined).max(1)
      val avgCCI = orders.flatMap(_.momentum.map(_.cci)).sum / orders.count(_.momentum.isDefined).max(1)
      val avgStochK = orders.flatMap(_.momentum.map(_.stochastics.percentK)).sum / orders.count(_.momentum.isDefined).max(1)
      
      // Direction alignment
      val diAligned = orders.count { r =>
        r.trend.exists { t =>
          (r.order.direction == "Long" && t.plusDI > t.minusDI) ||
          (r.order.direction == "Short" && t.minusDI > t.plusDI)
        }
      }
      
      // Stoch direction aligned
      val stochAligned = orders.count { r =>
        r.momentum.exists { m =>
          (r.order.direction == "Long" && m.stochastics.percentK > m.stochastics.percentD) ||
          (r.order.direction == "Short" && m.stochastics.percentK < m.stochastics.percentD)
        }
      }
      
      for {
        _ <- IO.println(s"\n$label (${orders.size} trades):")
        _ <- IO.println(f"  P/L: $$$pl%.0f, Win Rate: $wr%.1f%%, Quick Stops: $qsCount ($qsRate%.1f%%), Winners 1h+: $win1h")
        _ <- IO.println(f"  Avg ADX: $avgADX%.1f, Avg DI Spread: $avgDISpread%.1f, Avg ATR: $avgATR%.2f")
        _ <- IO.println(f"  Avg KC Width: $avgKCWidth%.4f, Avg Relative Volume: $avgRelVol%.2f")
        _ <- IO.println(f"  Avg RSI: $avgRSI%.1f, Avg CCI: $avgCCI%.1f, Avg Stoch K: $avgStochK%.1f")
        _ <- IO.println(f"  ATR Trend - Increasing: $atrIncreasing, Decreasing: $atrDecreasing, Stable: $atrStable")
        _ <- IO.println(f"  Vol Level - Low: $volLow, Normal: $volNormal, High: $volHigh, Extreme: $volExtreme")
        _ <- IO.println(f"  Regime - TrendHigh: $trendingHigh, TrendLow: $trendingLow, RangeWide: $rangingWide, RangeTight: $rangingTight, Breakout: $breakout")
        _ <- IO.println(f"  Transitioning: $transitioning, DI-Aligned: $diAligned, Stoch-Aligned: $stochAligned")
      } yield ()
    }
    
    def topLosers(label: String, orders: List[OrderWithIndicators], n: Int = 5): IO[Unit] = {
      val sorted = orders.sortBy(_.order.profitLoss).take(n)
      IO.println(s"\n$label - Top $n biggest losses:") *>
      sorted.traverse_ { r =>
        val trend = r.trend.map(t => f"ADX=${t.adx}%.1f DI=${math.abs(t.plusDI - t.minusDI)}%.1f").getOrElse("N/A")
        val vol = r.volatility.map(v => f"ATR=${v.trueRange.atr}%.2f ${v.trueRange.atrTrend}").getOrElse("N/A")
        val regime = r.regime.map(_.regime.toString).getOrElse("N/A")
        IO.println(f"  ${r.order.tradingDate} ${r.order.direction}%-5s P/L: $$${r.order.profitLoss}%-8.0f $trend, $vol, $regime")
      }
    }
    
    for {
      _ <- IO.println("")
      _ <- IO.println("=" * 80)
      _ <- IO.println("PERIOD COMPARISON: January vs Rest of Year")
      _ <- IO.println("=" * 80)
      _ <- periodStats("JANUARY 2025", januaryPeriod)
      _ <- periodStats("REST OF YEAR (Feb-Dec)", restOfYear)
      _ <- topLosers("JANUARY 2025", januaryPeriod, 20)
      _ <- IO.println("")
    } yield ()
  }
}
