package bmps.console

import cats.effect.{IO, IOApp, ExitCode}
import cats.syntax.all._
import io.circe.parser._
import io.circe.generic.auto._
import io.circe.syntax._
import java.time.{LocalDate, Instant, ZoneId}
import java.time.format.DateTimeFormatter
import bmps.core.models.{Event, EventType, Candle, SystemState, Order, OrderType, CandleDuration}
import bmps.core.services.{TechnicalAnalysisOrderService, LiquidityZoneService}
import bmps.core.utils.TimestampUtils
import bmps.core.io.ParquetSource
import java.io.{File, PrintWriter}
import scala.util.Using
import fs2.Stream

object StrategyAnalyzer extends IOApp {
  
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/New_York"))
  private val taService = new TechnicalAnalysisOrderService()

  case class SimulationRow(
    timestamp: Long,
    date: String,
    time: String,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
    
    // Indicators
    rsi: Double,
    adx: Double,
    trendStrength: Double,
    maSpread: Double,
    atr: Double,
    volume: Double,
    
    // Rules (1-12)
    rule1: Boolean, rule2: Boolean, rule3: Boolean, rule4: Boolean, rule5: Boolean,
    rule6: Boolean, rule7: Boolean, rule8: Boolean, rule9: Boolean, rule10: Boolean,
    rule11: Boolean, rule12: Boolean,

    // New Features (History)
    spreadChange1: Double, spreadChange2: Double, spreadChange3: Double, 
    spreadChange5: Double, spreadChange10: Double,
    
    rsiChange1: Double, rsiChange2: Double, rsiChange3: Double, 
    rsiChange5: Double, rsiChange10: Double,

    // New "Intersection" Features
    minutesSinceGoldenCross: Int,
    minutesSinceDeathCross: Int,
    atrSlope3: Double,
    trendStrengthSlope3: Double,
    rsiSlope3: Double,
    longCrossoverGap: Double, // TrendStrength - (100 - RSI)
    shortCrossoverGap: Double, // TrendStrength - RSI

    // Future Outcomes (Long)
    longProfit2ATR: Boolean, // Did it hit +2 ATR before -1 ATR?
    longMaxProfitATR: Double, // Max profit in ATRs in next 60 mins
    longMaxDrawdownATR: Double, // Max drawdown in ATRs in next 60 mins

    // Future Outcomes (Short)
    shortProfit2ATR: Boolean,
    shortMaxProfitATR: Double,
    shortMaxDrawdownATR: Double
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val source = new ParquetSource(CandleDuration.OneMinute)
    
    // Parse args or default to 2024-2025
    val (startStr, endStr) = args match {
        case s :: e :: _ => (if (s.contains("T")) s else s"${s}T00:00:00Z", if (e.contains("T")) e else s"${e}T23:59:59Z")
        case _ => ("2024-01-01T00:00:00Z", "2025-12-31T23:59:59Z")
    }
    
    val start = Instant.parse(startStr).toEpochMilli
    val end = Instant.parse(endStr).toEpochMilli
    
    for {
      _ <- IO.println(s"Starting Strategy Analyzer with Parquet Source...")
      _ <- IO.println(s"Reading candles from $startStr to $endStr...")
      
      allRows <- source.candlesInRangeStream(start, end)
        .zipWithIndex
        .sliding(61) // Current + 60 future candles
        .evalMapAccumulate(SystemState(tradingDay = LocalDate.of(2024,1,1))) { case (state, chunk) =>
            val candlesWithIndex = chunk.toList
            val (currentCandle, index) = candlesWithIndex.head
            val futureCandles = candlesWithIndex.tail.map(_._1)
            
            val logEffect = if (index % 5000 == 0) IO.println(s"Processed $index candles (${Instant.ofEpochMilli(currentCandle.timestamp)})...") else IO.unit
            
            logEffect *> IO {
                val candleDate = LocalDate.ofInstant(Instant.ofEpochMilli(currentCandle.timestamp), ZoneId.of("America/New_York"))
                
                // Handle day change and pruning
                val baseState = if (candleDate != state.tradingDay) {
                   state.copy(tradingDay = candleDate)
                } else state
                
                val prunedState = if (baseState.tradingCandles.size > 500) {
                   baseState.copy(tradingCandles = baseState.tradingCandles.takeRight(500))
                } else baseState
                
                // 1. Update Liquidity Zones (Extremes)
                val (stateWithLZ, _) = LiquidityZoneService.processLiquidityZones(prunedState, currentCandle)
                
                // 2. Update Indicators
                val stateWithCandle = updateState(stateWithLZ, currentCandle)
                
                // 3. Analyze
                // Only analyze if we have enough history
                val rowOption = if (stateWithCandle.tradingCandles.size > 20) {
                   Some(createSimulationRow(candleDate.toString, stateWithCandle, currentCandle, futureCandles))
                } else None
                
                (stateWithCandle, rowOption)
            }
        }
        .map(_._2)
        .unNone
        .compile
        .toList

      _ <- writeCsv(allRows, "strategy_analysis_full.csv")
      _ <- IO.println(s"Analysis complete. Wrote ${allRows.size} rows to strategy_analysis_full.csv")
    } yield ExitCode.Success
  }

  // Removed fetchAvailableDates, fetchEvents, processDate, analyzeEvents

  private def updateState(state: SystemState, candle: Candle): SystemState = {
    // 1. Add candle
    val stateWithCandle = state.copy(tradingCandles = state.tradingCandles :+ candle)
    
    // 2. Run Analysis
    val trend = calculateTrend(stateWithCandle)
    val momentum = calculateMomentum(stateWithCandle)
    val volatility = calculateVolatility(stateWithCandle)
    
    stateWithCandle.copy(
      recentTrendAnalysis = state.recentTrendAnalysis :+ trend,
      recentMomentumAnalysis = state.recentMomentumAnalysis :+ momentum,
      recentVolatilityAnalysis = state.recentVolatilityAnalysis :+ volatility
    )
  }

  // --- Analysis Calculation Helpers ---
  
  import bmps.core.services.analysis.{Trend, Momentum, Volatility}
  import bmps.core.services.analysis.{TrendAnalysis, MomentumAnalysis, VolatilityAnalysis}

  private val trendService = new Trend()
  private val momentumService = new Momentum()
  private val volatilityService = new Volatility()

  private def calculateTrend(state: SystemState): TrendAnalysis = {
    if (state.tradingCandles.size < 2) {
      TrendAnalysis(0, 0, 0, 0, 0, 0, 0, 0, 0L)
    } else {
      trendService.doTrendAnalysis(state.tradingCandles)
    }
  }

  private def calculateMomentum(state: SystemState): MomentumAnalysis = {
    if (state.tradingCandles.size < 14) { 
       MomentumAnalysis(50, bmps.core.services.analysis.StochasticsResult(50, 50), -50, 0)
    } else {
      momentumService.doMomentumAnalysis(state.tradingCandles)
    }
  }

  private def calculateVolatility(state: SystemState): VolatilityAnalysis = {
    if (state.tradingCandles.size < 2) {
       VolatilityAnalysis(
        bmps.core.services.analysis.TrueRangeAnalysis(0, 0, "Stable", "Normal"), 
        bmps.core.services.analysis.KeltnerChannels(0,0,0,0,"Inside"), 
        bmps.core.services.analysis.BollingerBands(0,0,0,0,0,"Inside"),
        bmps.core.services.analysis.StandardDeviationAnalysis(0,0,0,0,0,0,0)
      )
    } else {
      volatilityService.doVolatilityAnalysis(state.tradingCandles)
    }
  }

  private def createSimulationRow(
    dateStr: String, 
    state: SystemState, 
    currentCandle: Candle, 
    futureCandles: List[Candle]
  ): SimulationRow = {
    
    // Calculate Rules
    val rules = (1 to 21).map { id =>
        id match {
            case r if r <= 12 => taService.applyScenario(r, List(r), state)
            case _ => false 
        }
    }

    // Calculate Outcomes
    val atr = state.recentVolatilityAnalysis.last.trueRange.atr
    val (long2ATR, longMaxP, longMaxDD) = simulateTrade(currentCandle, futureCandles, OrderType.Long, atr)
    val (short2ATR, shortMaxP, shortMaxDD) = simulateTrade(currentCandle, futureCandles, OrderType.Short, atr)

    // Helper to get historical values safely
    def getSpread(idx: Int): Double = {
        if (idx >= 0 && idx < state.recentTrendAnalysis.size) {
            val ta = state.recentTrendAnalysis(idx)
            math.abs(ta.shortTermMA - ta.longTermMA)
        } else 0.0
    }
    
    def getRsi(idx: Int): Double = {
        if (idx >= 0 && idx < state.recentMomentumAnalysis.size) {
            state.recentMomentumAnalysis(idx).rsi
        } else 50.0
    }

    val currentIdx = state.recentTrendAnalysis.size - 1
    val currentSpread = getSpread(currentIdx)
    val currentRsi = getRsi(currentIdx)

    // --- New Feature Calculations ---
    
    // 1. Minutes Since Cross
    // Iterate backwards to find the last cross
    def findMinutesSince(predicate: TrendAnalysis => Boolean): Int = {
        state.recentTrendAnalysis.reverseIterator.zipWithIndex.find { case (ta, idx) =>
            predicate(ta)
        }.map(_._2).getOrElse(999) // 999 if not found
    }
    
    val minutesSinceGoldenCross = findMinutesSince(_.isGoldenCross)
    val minutesSinceDeathCross = findMinutesSince(_.isDeathCross)
    
    // 2. Slopes (3-minute lookback)
    def calculateSlope(current: Double, past: Double, minutes: Int): Double = {
        (current - past) / minutes.toDouble
    }
    
    val currentAtr = atr
    val pastAtr = if (state.recentVolatilityAnalysis.size > 3) state.recentVolatilityAnalysis(state.recentVolatilityAnalysis.size - 4).trueRange.atr else currentAtr
    val atrSlope3 = calculateSlope(currentAtr, pastAtr, 3)
    
    val currentTrendStrength = taService.calculateTrendStrength(state, 0)
    val pastTrendStrength = taService.calculateTrendStrength(state, 3)
    val trendStrengthSlope3 = calculateSlope(currentTrendStrength, pastTrendStrength, 3)
    
    val pastRsi = getRsi(currentIdx - 3)
    val rsiSlope3 = calculateSlope(currentRsi, pastRsi, 3)
    
    // 3. Crossover Gaps
    // Long: TrendStrength vs (100 - RSI). Intersection when TrendStrength > (100 - RSI)
    // Gap = TrendStrength - (100 - RSI). Positive means "crossed over" (Trend is stronger than Inverse RSI)
    val longCrossoverGap = currentTrendStrength - (100.0 - currentRsi)
    
    // Short: TrendStrength vs RSI. Intersection when TrendStrength > RSI
    // Gap = TrendStrength - RSI. Positive means "crossed over"
    val shortCrossoverGap = currentTrendStrength - currentRsi

    SimulationRow(
      timestamp = currentCandle.timestamp,
      date = dateStr,
      time = timeFormatter.format(Instant.ofEpochMilli(currentCandle.timestamp)),
      open = currentCandle.open,
      high = currentCandle.high,
      low = currentCandle.low,
      close = currentCandle.close,
      
      rsi = state.recentMomentumAnalysis.last.rsi,
      adx = state.recentTrendAnalysis.last.adx,
      trendStrength = currentTrendStrength,
      maSpread = math.abs(state.recentTrendAnalysis.last.shortTermMA - state.recentTrendAnalysis.last.longTermMA),
      atr = atr,
      volume = currentCandle.volume.toDouble,
      
      rule1 = rules(0), rule2 = rules(1), rule3 = rules(2), rule4 = rules(3), rule5 = rules(4),
      rule6 = rules(5), rule7 = rules(6), rule8 = rules(7), rule9 = rules(8), rule10 = rules(9),
      rule11 = rules(10), rule12 = rules(11), 
      
      spreadChange1 = currentSpread - getSpread(currentIdx - 1),
      spreadChange2 = currentSpread - getSpread(currentIdx - 2),
      spreadChange3 = currentSpread - getSpread(currentIdx - 3),
      spreadChange5 = currentSpread - getSpread(currentIdx - 5),
      spreadChange10 = currentSpread - getSpread(currentIdx - 10),
      
      rsiChange1 = currentRsi - getRsi(currentIdx - 1),
      rsiChange2 = currentRsi - getRsi(currentIdx - 2),
      rsiChange3 = currentRsi - getRsi(currentIdx - 3),
      rsiChange5 = currentRsi - getRsi(currentIdx - 5),
      rsiChange10 = currentRsi - getRsi(currentIdx - 10),
      
      minutesSinceGoldenCross = minutesSinceGoldenCross,
      minutesSinceDeathCross = minutesSinceDeathCross,
      atrSlope3 = atrSlope3,
      trendStrengthSlope3 = trendStrengthSlope3,
      rsiSlope3 = rsiSlope3,
      longCrossoverGap = longCrossoverGap,
      shortCrossoverGap = shortCrossoverGap,

      longProfit2ATR = long2ATR,
      longMaxProfitATR = longMaxP,
      longMaxDrawdownATR = longMaxDD,
      
      shortProfit2ATR = short2ATR,
      shortMaxProfitATR = shortMaxP,
      shortMaxDrawdownATR = shortMaxDD
    )
  }

  private def simulateTrade(
    entryCandle: Candle, 
    futureCandles: List[Candle], 
    direction: OrderType, 
    atr: Double
  ): (Boolean, Double, Double) = {
    
    if (futureCandles.isEmpty) return (false, 0.0, 0.0)

    val entryPrice = entryCandle.close
    val tpDist = 2.0 * atr
    val slDist = 1.0 * atr 

    var hitTP = false
    var hitSL = false
    var maxProfit = 0.0
    var maxDrawdown = 0.0

    for (c <- futureCandles if !hitTP && !hitSL) {
      val (high, low) = (c.high, c.low)
      
      if (direction == OrderType.Long) {
        val currentProfit = high - entryPrice
        val currentDD = entryPrice - low
        
        maxProfit = math.max(maxProfit, currentProfit)
        maxDrawdown = math.max(maxDrawdown, currentDD)
        
        if (currentProfit >= tpDist) hitTP = true
        if (currentDD >= slDist) hitSL = true 
      } else {
        val currentProfit = entryPrice - low
        val currentDD = high - entryPrice
        
        maxProfit = math.max(maxProfit, currentProfit)
        maxDrawdown = math.max(maxDrawdown, currentDD)
        
        if (currentProfit >= tpDist) hitTP = true
        if (currentDD >= slDist) hitSL = true
      }
    }

    (hitTP && !hitSL, maxProfit / atr, maxDrawdown / atr)
  }

  private def writeCsv(rows: List[SimulationRow], filename: String): IO[Unit] = IO.blocking {
    val pw = new PrintWriter(new File(filename))
    try {
      // Header
      pw.println("timestamp,date,time,open,high,low,close,rsi,adx,trendStrength,maSpread,atr,volume," +
        (1 to 12).map(i => s"rule$i").mkString(",") + "," +
        "spreadChange1,spreadChange2,spreadChange3,spreadChange5,spreadChange10," +
        "rsiChange1,rsiChange2,rsiChange3,rsiChange5,rsiChange10," +
        "minutesSinceGoldenCross,minutesSinceDeathCross,atrSlope3,trendStrengthSlope3,rsiSlope3,longCrossoverGap,shortCrossoverGap," +
        "longProfit2ATR,longMaxProfitATR,longMaxDrawdownATR," +
        "shortProfit2ATR,shortMaxProfitATR,shortMaxDrawdownATR")
      
      // Data
      rows.foreach { r =>
        pw.println(s"${r.timestamp},${r.date},${r.time},${r.open},${r.high},${r.low},${r.close}," +
          s"${r.rsi},${r.adx},${r.trendStrength},${r.maSpread},${r.atr},${r.volume}," +
          s"${r.rule1},${r.rule2},${r.rule3},${r.rule4},${r.rule5}," +
          s"${r.rule6},${r.rule7},${r.rule8},${r.rule9},${r.rule10}," +
          s"${r.rule11},${r.rule12}," +
          s"${r.spreadChange1},${r.spreadChange2},${r.spreadChange3},${r.spreadChange5},${r.spreadChange10}," +
          s"${r.rsiChange1},${r.rsiChange2},${r.rsiChange3},${r.rsiChange5},${r.rsiChange10}," +
          s"${r.minutesSinceGoldenCross},${r.minutesSinceDeathCross},${r.atrSlope3},${r.trendStrengthSlope3},${r.rsiSlope3},${r.longCrossoverGap},${r.shortCrossoverGap}," +
          s"${r.longProfit2ATR},${r.longMaxProfitATR},${r.longMaxDrawdownATR}," +
          s"${r.shortProfit2ATR},${r.shortMaxProfitATR},${r.shortMaxDrawdownATR}")
      }
    } finally {
      pw.close()
    }
  }
}
