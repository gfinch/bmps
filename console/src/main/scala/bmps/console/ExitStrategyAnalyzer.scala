package bmps.console

import cats.effect.{IO, IOApp, ExitCode}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import java.time.{Instant, LocalDateTime, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.io.Source
import scala.util.Using
import bmps.core.io.ParquetSource
import bmps.core.models.{CandleDuration, Candle}
import bmps.core.services.analysis.{Trend, Volatility, Momentum}

/**
 * Analyzes historical trades to evaluate exit strategy effectiveness.
 * 
 * For each trade, this analyzer:
 * 1. Loads candles from entry to exit
 * 2. Calculates max unrealized profit during the trade
 * 3. Simulates different exit triggers to compare strategies
 * 4. Reports profit capture efficiency and potential improvements
 */
object ExitStrategyAnalyzer extends IOApp {
  
  private val ET_ZONE = ZoneId.of("America/New_York")
  private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  
  case class TradeAnalysis(
    tradingDate: String,
    direction: String,
    entryPrice: Double,
    actualExitPrice: Double,
    actualPL: Double,
    durationMinutes: Long,
    // Max profit analysis
    maxUnrealizedProfit: Double,
    maxProfitPrice: Double,
    maxProfitMinute: Long,  // Minutes after entry when max profit occurred
    // Profit capture
    profitCaptured: Double,  // actualPL / maxUnrealizedProfit (if max > 0)
    profitGivenBack: Double, // maxUnrealizedProfit - actualPL
    // Exit trigger analysis
    keltnerCrossMinute: Option[Long],  // When price first crossed opposite Keltner
    maCrossMinute: Option[Long],  // When MA cross occurred against position
    atrReversalMinute: Option[Long],  // When 1 ATR reversal from max profit
    twoAtrReversalMinute: Option[Long],  // When 2 ATR reversal from max profit
    // What-if exits
    exitAtMaxPL: Double,
    exitAt1AtrReversalPL: Option[Double],
    exitAt2AtrReversalPL: Option[Double],
    exitAtKeltnerCrossPL: Option[Double],
    exitAtMACrossPL: Option[Double]
  )
  
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
    def isWinner: Boolean = profitLoss > 0
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val inputFile = args.headOption.getOrElse("orders.csv")
    
    for {
      _ <- IO.println("=" * 80)
      _ <- IO.println("EXIT STRATEGY ANALYZER")
      _ <- IO.println("=" * 80)
      _ <- IO.println(s"Input: $inputFile")
      _ <- IO.println("")
      
      orders <- IO(parseOrdersCsv(inputFile))
      _ <- IO.println(s"Loaded ${orders.size} orders")
      
      // Filter to winners only for exit analysis (losers hit stops)
      winners = orders.filter(_.isWinner)
      _ <- IO.println(s"Winners to analyze: ${winners.size}")
      _ <- IO.println("")
      
      // Analyze each trade
      _ <- IO.println("Analyzing trade exits...")
      analyses <- analyzeExits(winners)
      
      _ <- IO.println(s"Successfully analyzed ${analyses.size} trades")
      _ <- IO.println("")
      
      _ <- printExitAnalysisSummary(analyses, orders.filterNot(_.isWinner))
      
    } yield ExitCode.Success
  }.handleErrorWith { err =>
    IO.println(s"Error: ${err.getMessage}") *>
    IO(err.printStackTrace()) *>
    IO.pure(ExitCode.Error)
  }

  private def parseOrdersCsv(path: String): List[OrderEntry] = {
    Using(Source.fromFile(path)) { source =>
      source.getLines().drop(1).flatMap { line =>
        val parts = line.split(",").map(_.trim)
        if (parts.length >= 10) {
          val openTimeET = parts(0)
          val closeTimeET = parts(1)
          val tradingDate = parts(2)
          val direction = parts(3)
          val entryPrice = parts(4).toDoubleOption.getOrElse(0.0)
          val exitPrice = parts(5).toDoubleOption.getOrElse(0.0)
          val profitLoss = parts(6).toDoubleOption.getOrElse(0.0)
          val status = parts(9)
          
          val openTs = parseTimestamp(openTimeET)
          val closeTs = parseTimestamp(closeTimeET)
          
          Some(OrderEntry(openTimeET, closeTimeET, tradingDate, direction, 
                         entryPrice, exitPrice, profitLoss, status, openTs, closeTs))
        } else None
      }.toList
    }.getOrElse(Nil)
  }

  private def parseTimestamp(dateTimeStr: String): Long = {
    try {
      val ldt = LocalDateTime.parse(dateTimeStr, DATE_FORMATTER)
      val zdt = ZonedDateTime.of(ldt, ET_ZONE)
      zdt.toInstant.toEpochMilli
    } catch {
      case _: Exception => 0L
    }
  }

  private def analyzeExits(orders: List[OrderEntry]): IO[List[TradeAnalysis]] = {
    val parquet = new ParquetSource(CandleDuration.OneMinute)
    val trend = new Trend()
    val volatility = new Volatility()
    
    orders.traverse { order =>
      IO {
        // Load candles from 60 min before entry to exit
        val startMs = order.openTimestampMs - (60 * 60 * 1000L)
        val endMs = order.closeTimestampMs + (60 * 1000L)  // Extra minute buffer
        
        val allCandles = parquet.candlesInRangeStream(startMs, endMs)
          .compile.toList.unsafeRunSync()
          .filter(_.duration == CandleDuration.OneMinute)
          .sortBy(_.timestamp)
        
        // Find candles during the trade
        val tradeCandles = allCandles.filter { c =>
          c.endTime >= order.openTimestampMs &&
          c.endTime <= order.closeTimestampMs
        }
        
        if (tradeCandles.isEmpty) {
          None
        } else {
          // Calculate max unrealized profit
          val isLong = order.direction == "Long"
          val contractMultiplier = 50.0  // ES = $50/point
          
          val (maxProfitPrice: Double, maxProfitCandle: Candle, maxProfitIdx: Int) = if (isLong) {
            val maxC = tradeCandles.zipWithIndex.maxBy(_._1.high)
            (maxC._1.high, maxC._1, maxC._2)
          } else {
            val minC = tradeCandles.zipWithIndex.minBy(_._1.low)
            (minC._1.low, minC._1, minC._2)
          }
          
          val maxUnrealizedProfit: Double = if (isLong) {
            (maxProfitPrice - order.entryPrice) * contractMultiplier
          } else {
            (order.entryPrice - maxProfitPrice) * contractMultiplier
          }
          
          val maxProfitMinute: Long = (maxProfitCandle.endTime - order.openTimestampMs) / 60000
          
          // Profit capture efficiency
          val profitCaptured = if (maxUnrealizedProfit > 0) {
            order.profitLoss / maxUnrealizedProfit * 100
          } else 100.0
          
          val profitGivenBack = maxUnrealizedProfit - order.profitLoss
          
          // Find when different exit triggers would have fired
          // For this we need volatility analysis at each candle
          val lookbackCandles = allCandles.filter(_.endTime < order.openTimestampMs).takeRight(60)
          
          var keltnerCrossMinute: Option[Long] = None
          var atrReversalMinute: Option[Long] = None
          var twoAtrReversalMinute: Option[Long] = None
          var maCrossMinute: Option[Long] = None
          
          var exitAtKeltnerCrossPL: Option[Double] = None
          var exitAt1AtrReversalPL: Option[Double] = None
          var exitAt2AtrReversalPL: Option[Double] = None
          var exitAtMACrossPL: Option[Double] = None
          
          var maxProfitSoFar = 0.0
          
          tradeCandles.zipWithIndex.foreach { case (candle, idx) =>
            val minutesSinceEntry = (candle.endTime - order.openTimestampMs) / 60000
            val candlesForAnalysis = (lookbackCandles ++ tradeCandles.take(idx + 1)).takeRight(60)
            
            if (candlesForAnalysis.size >= 30) {
              try {
                val volAnalysis = volatility.doVolatilityAnalysis(candlesForAnalysis)
                val trendAnalysis = trend.doTrendAnalysis(candlesForAnalysis)
                
                val keltner = volAnalysis.keltnerChannels
                val atr = volAnalysis.trueRange.atr
                
                // Calculate current unrealized profit
                val currentProfit = if (isLong) {
                  (candle.close - order.entryPrice) * contractMultiplier
                } else {
                  (order.entryPrice - candle.close) * contractMultiplier
                }
                
                if (currentProfit > maxProfitSoFar) {
                  maxProfitSoFar = currentProfit
                }
                
                // Check for Keltner cross (opposite band)
                if (keltnerCrossMinute.isEmpty) {
                  val crossed = if (isLong) {
                    candle.low < keltner.lowerBand
                  } else {
                    candle.high > keltner.upperBand
                  }
                  if (crossed) {
                    keltnerCrossMinute = Some(minutesSinceEntry)
                    exitAtKeltnerCrossPL = Some(if (isLong) {
                      (candle.close - order.entryPrice) * contractMultiplier
                    } else {
                      (order.entryPrice - candle.close) * contractMultiplier
                    })
                  }
                }
                
                // Check for 1 ATR reversal from max profit
                if (atrReversalMinute.isEmpty && maxProfitSoFar > 0) {
                  val reversalAmount = maxProfitSoFar - currentProfit
                  if (reversalAmount >= atr * contractMultiplier) {
                    atrReversalMinute = Some(minutesSinceEntry)
                    exitAt1AtrReversalPL = Some(currentProfit)
                  }
                }
                
                // Check for 2 ATR reversal from max profit
                if (twoAtrReversalMinute.isEmpty && maxProfitSoFar > 0) {
                  val reversalAmount = maxProfitSoFar - currentProfit
                  if (reversalAmount >= 2 * atr * contractMultiplier) {
                    twoAtrReversalMinute = Some(minutesSinceEntry)
                    exitAt2AtrReversalPL = Some(currentProfit)
                  }
                }
                
                // Check for MA cross against position
                if (maCrossMinute.isEmpty) {
                  val crossAgainst = if (isLong) {
                    trendAnalysis.isDeathCross
                  } else {
                    trendAnalysis.isGoldenCross
                  }
                  if (crossAgainst) {
                    maCrossMinute = Some(minutesSinceEntry)
                    exitAtMACrossPL = Some(currentProfit)
                  }
                }
              } catch {
                case _: Exception => // Skip this candle
              }
            }
          }
          
          Some(TradeAnalysis(
            tradingDate = order.tradingDate,
            direction = order.direction,
            entryPrice = order.entryPrice,
            actualExitPrice = order.exitPrice,
            actualPL = order.profitLoss,
            durationMinutes = order.durationMinutes,
            maxUnrealizedProfit = maxUnrealizedProfit,
            maxProfitPrice = maxProfitPrice,
            maxProfitMinute = maxProfitMinute,
            profitCaptured = profitCaptured,
            profitGivenBack = profitGivenBack,
            keltnerCrossMinute = keltnerCrossMinute,
            maCrossMinute = maCrossMinute,
            atrReversalMinute = atrReversalMinute,
            twoAtrReversalMinute = twoAtrReversalMinute,
            exitAtMaxPL = maxUnrealizedProfit,
            exitAt1AtrReversalPL = exitAt1AtrReversalPL,
            exitAt2AtrReversalPL = exitAt2AtrReversalPL,
            exitAtKeltnerCrossPL = exitAtKeltnerCrossPL,
            exitAtMACrossPL = exitAtMACrossPL
          ))
        }
      }
    }.map(_.flatten)
  }

  private def printExitAnalysisSummary(analyses: List[TradeAnalysis], losers: List[OrderEntry]): IO[Unit] = {
    if (analyses.isEmpty) {
      return IO.println("No trades to analyze")
    }
    
    val totalActualPL = analyses.map(_.actualPL).sum
    val totalMaxPL = analyses.map(_.maxUnrealizedProfit).sum
    val totalGivenBack = analyses.map(_.profitGivenBack).sum
    val avgProfitCapture = analyses.map(_.profitCaptured).sum / analyses.size
    
    // Calculate what-if totals (use actual if alternative not available)
    val totalAt1AtrReversal = analyses.map(a => a.exitAt1AtrReversalPL.getOrElse(a.actualPL)).sum
    val totalAt2AtrReversal = analyses.map(a => a.exitAt2AtrReversalPL.getOrElse(a.actualPL)).sum
    val totalAtKeltnerCross = analyses.map(a => a.exitAtKeltnerCrossPL.getOrElse(a.actualPL)).sum
    val totalAtMACross = analyses.map(a => a.exitAtMACrossPL.getOrElse(a.actualPL)).sum
    
    // How many trades had each trigger available
    val tradesWithAtrReversal = analyses.count(_.exitAt1AtrReversalPL.isDefined)
    val tradesWith2AtrReversal = analyses.count(_.exitAt2AtrReversalPL.isDefined)
    val tradesWithKeltnerCross = analyses.count(_.exitAtKeltnerCrossPL.isDefined)
    val tradesWithMACross = analyses.count(_.exitAtMACrossPL.isDefined)
    
    // Loser stats (for context - these hit stops so exit strategy matters less)
    val loserPL = losers.map(_.profitLoss).sum
    
    // Time analysis - when does max profit typically occur
    val avgMaxProfitMinute = analyses.map(_.maxProfitMinute).sum.toDouble / analyses.size
    val avgDuration = analyses.map(_.durationMinutes).sum.toDouble / analyses.size
    
    // Breakdown by profit capture buckets
    val captured90Plus = analyses.filter(_.profitCaptured >= 90)
    val captured70to90 = analyses.filter(a => a.profitCaptured >= 70 && a.profitCaptured < 90)
    val captured50to70 = analyses.filter(a => a.profitCaptured >= 50 && a.profitCaptured < 70)
    val capturedBelow50 = analyses.filter(_.profitCaptured < 50)
    
    for {
      _ <- IO.println("=" * 80)
      _ <- IO.println("EXIT STRATEGY ANALYSIS - WINNERS ONLY")
      _ <- IO.println("=" * 80)
      _ <- IO.println(f"Winning trades analyzed: ${analyses.size}")
      _ <- IO.println(f"Losing trades (hit stops): ${losers.size}, P/L: $$$loserPL%.0f")
      _ <- IO.println("")
      
      _ <- IO.println("--- PROFIT CAPTURE SUMMARY ---")
      _ <- IO.println(f"Actual P/L captured:     $$$totalActualPL%.0f")
      _ <- IO.println(f"Max possible P/L:        $$$totalMaxPL%.0f")
      _ <- IO.println(f"Profit given back:       $$$totalGivenBack%.0f")
      _ <- IO.println(f"Average capture rate:    $avgProfitCapture%.1f%%")
      _ <- IO.println("")
      
      _ <- IO.println("--- TIMING ANALYSIS ---")
      _ <- IO.println(f"Avg max profit occurs at: $avgMaxProfitMinute%.0f minutes")
      _ <- IO.println(f"Avg trade duration:       $avgDuration%.0f minutes")
      _ <- IO.println(f"Avg time after max profit before exit: ${avgDuration - avgMaxProfitMinute}%.0f minutes")
      _ <- IO.println("")
      
      _ <- IO.println("--- PROFIT CAPTURE BUCKETS ---")
      _ <- IO.println(f"Captured 90%%+:  ${captured90Plus.size} trades ($$${captured90Plus.map(_.actualPL).sum}%.0f P/L)")
      _ <- IO.println(f"Captured 70-90%%: ${captured70to90.size} trades ($$${captured70to90.map(_.actualPL).sum}%.0f P/L)")
      _ <- IO.println(f"Captured 50-70%%: ${captured50to70.size} trades ($$${captured50to70.map(_.actualPL).sum}%.0f P/L)")
      _ <- IO.println(f"Captured <50%%:  ${capturedBelow50.size} trades ($$${capturedBelow50.map(_.actualPL).sum}%.0f P/L)")
      _ <- IO.println("")
      
      _ <- IO.println("--- WHAT-IF EXIT STRATEGIES (Winners Only) ---")
      _ <- IO.println(f"Current strategy:       $$$totalActualPL%.0f")
      _ <- IO.println(f"Exit at 1 ATR reversal: $$$totalAt1AtrReversal%.0f (available in $tradesWithAtrReversal trades)")
      _ <- IO.println(f"Exit at 2 ATR reversal: $$$totalAt2AtrReversal%.0f (available in $tradesWith2AtrReversal trades)")
      _ <- IO.println(f"Exit at Keltner cross:  $$$totalAtKeltnerCross%.0f (available in $tradesWithKeltnerCross trades)")
      _ <- IO.println(f"Exit at MA cross:       $$$totalAtMACross%.0f (available in $tradesWithMACross trades)")
      _ <- IO.println(f"Perfect exit (max):     $$$totalMaxPL%.0f")
      _ <- IO.println("")
      
      // Show worst trades (most profit given back)
      _ <- IO.println("--- TOP 10 TRADES WITH MOST PROFIT GIVEN BACK ---")
      _ <- IO.println(f"${"Date"}%-12s ${"Dir"}%-6s ${"Actual"}%-10s ${"Max"}%-10s ${"Given Back"}%-12s ${"Capture"}%-8s ${"MaxMin"}%-8s")
      worstCapture = analyses.sortBy(-_.profitGivenBack).take(10)
      _ <- worstCapture.traverse_ { a =>
        IO.println(f"${a.tradingDate}%-12s ${a.direction}%-6s $$${a.actualPL}%-9.0f $$${a.maxUnrealizedProfit}%-9.0f $$${a.profitGivenBack}%-11.0f ${a.profitCaptured}%-7.1f%% ${a.maxProfitMinute}%-8d")
      }
      _ <- IO.println("")
      
      // Summary of when triggers fire relative to max profit
      _ <- IO.println("--- TRIGGER TIMING ANALYSIS ---")
      tradesWithTriggers = analyses.filter(a => 
        a.atrReversalMinute.isDefined || a.keltnerCrossMinute.isDefined || a.maCrossMinute.isDefined)
      _ <- if (tradesWithTriggers.nonEmpty) {
        val avgAtrMin = tradesWithTriggers.flatMap(_.atrReversalMinute).map(_.toDouble)
        val avgKeltnerMin = tradesWithTriggers.flatMap(_.keltnerCrossMinute).map(_.toDouble)
        val avgMAMin = tradesWithTriggers.flatMap(_.maCrossMinute).map(_.toDouble)
        
        for {
          _ <- if (avgAtrMin.nonEmpty) IO.println(f"Avg 1 ATR reversal at: ${avgAtrMin.sum / avgAtrMin.size}%.0f min") else IO.unit
          _ <- if (avgKeltnerMin.nonEmpty) IO.println(f"Avg Keltner cross at: ${avgKeltnerMin.sum / avgKeltnerMin.size}%.0f min") else IO.unit
          _ <- if (avgMAMin.nonEmpty) IO.println(f"Avg MA cross at: ${avgMAMin.sum / avgMAMin.size}%.0f min") else IO.unit
        } yield ()
      } else IO.println("No trigger data available")
      
      _ <- IO.println("")
      _ <- IO.println("=" * 80)
    } yield ()
  }
}
