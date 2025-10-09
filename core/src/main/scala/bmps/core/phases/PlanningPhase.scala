package bmps.core.phases

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import fs2.Stream
import cats.effect.IO
import bmps.core.services.{SwingService, PlanZoneService, LiquidityZoneService}
import bmps.core.io.ParquetSource
import bmps.core.models.{SystemState, Event, Candle}
import bmps.core.api.intf.{EventGenerator, CandleSource}
import bmps.core.api.run.PhaseRunner
import bmps.core.models.SystemStatePhase
import bmps.core.models.CandleDuration
import bmps.core.io.PolygonAPISource
import bmps.core.io.DatabentoSource

class PlanningEventGenerator(swingService: SwingService = new SwingService(1)) extends EventGenerator with TradingDate {
    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        options.get("tradingDate") match {
            case Some(tradeDate) =>
                val localDate = parseTradingDate(tradeDate)
                state.copy(systemStatePhase = SystemStatePhase.Planning, tradingDay = localDate)
            case None =>
                state.copy(systemStatePhase = SystemStatePhase.Planning)
        }
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val updatedCandles = state.planningCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(planningCandles = updatedCandles, planningSwingPoints = swings, swingDirection = newDirection)
        val (withZones, zoneEvents) = PlanZoneService.processPlanZones(withSwings)
        val (updatedState, liquidEvents) = if (!isInTradingDate(withSwings.tradingDay, candle.timestamp)) {
            LiquidityZoneService.processLiquidityZones(withZones, candle)
        } else (withZones, List.empty)
        val newSwingPoints = updatedState.planningSwingPoints.drop(state.planningSwingPoints.length)

        val allEvents = newSwingPoints.map(Event.fromSwingPoint) ++ zoneEvents ++ liquidEvents 

        (updatedState, allEvents)
    }
}

class PlanningSource extends CandleSource {
    // lazy val source = new PolygonAPISource(CandleDuration.OneHour)
    // lazy val source = new ParquetSource(CandleDuration.OneHour)
    lazy val source = new DatabentoSource(CandleDuration.OneHour)

    def candles(state: SystemState): Stream[IO, Candle] = {
        val (startMs, endMs, zoneId) = computePlanningWindow(state)
        source.candlesInRangeStream(startMs, endMs, zoneId)
    }

    private def computePlanningWindow(state: SystemState): (Long, Long, java.time.ZoneId) = {
        import java.time.{ZoneId, ZonedDateTime, LocalTime}
        import bmps.core.utils.MarketCalendar
        
        val zoneId = ZoneId.of("America/New_York")
        val tradingDay = state.tradingDay
        
        // Calculate the date that's 2 trading days before tradingDay
        val startDate = MarketCalendar.getTradingDaysBack(tradingDay, 2)
        
        val startDateTime = ZonedDateTime.of(startDate, LocalTime.of(9, 0), zoneId)
        val endDateTime = ZonedDateTime.of(tradingDay, LocalTime.of(9, 0), zoneId)
        val startMs = startDateTime.toInstant.toEpochMilli
        val endMs = endDateTime.toInstant.toEpochMilli
        (startMs, endMs, zoneId)
    }
}

object PlanningPhaseBuilder {
    def build() = new PhaseRunner(new PlanningSource(), new PlanningEventGenerator())
}
