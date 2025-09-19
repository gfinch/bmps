package bmps.core.phases

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import fs2.Stream
import cats.effect.IO
import bmps.core.services.{SwingService, PlanZoneService, LiquidityZoneService}
import bmps.core.io.ParquetSource
import bmps.core.models.{SystemState, Event, Candle}
import bmps.core.api.intf.EventGenerator
import bmps.core.api.intf.CandleSource
import bmps.core.api.impl.PhaseRunner
import bmps.core.models.SystemStatePhase

class PlanningEventGenerator(swingService: SwingService = new SwingService(1)) extends EventGenerator with TradingDate {
    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        (options.get("tradingDate"), options.get("planningDays")) match {
            case (Some(tradeDate), Some(daysStr)) =>
                val localDate = parseTradingDate(tradeDate)
                val days = daysStr.toInt
                state.copy(systemStatePhase = SystemStatePhase.Planning, tradingDay = localDate, planningDays = days)
            case (Some(tradeDate), None) =>
                val localDate = parseTradingDate(tradeDate)
                state.copy(systemStatePhase = SystemStatePhase.Planning, tradingDay = localDate)
            case (None, Some(daysStr)) =>
                val days = daysStr.toInt
                state.copy(systemStatePhase = SystemStatePhase.Planning, planningDays = days)
            case (None, None) =>
                state
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
    lazy val parquetPath = "core/src/main/resources/samples/es_futures_1h_60days.parquet"

    def candles(state: SystemState): Stream[IO, Candle] = {
        val (startMs, endMs, zoneId) = computePlanningWindow(state)
        ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, startMs, endMs, zoneId)
    }

    private def computePlanningWindow(state: SystemState): (Long, Long, java.time.ZoneId) = {
        import java.time.{ZoneId, ZonedDateTime, LocalTime}
        val zoneId = ZoneId.of("America/New_York")
        val tradingDay = state.tradingDay
        val planningDays = state.planningDays
        val startDate = tradingDay.minusDays(planningDays)
        val startDateTime = ZonedDateTime.of(startDate, LocalTime.of(9, 0), zoneId)
        val endDateTime = ZonedDateTime.of(tradingDay, LocalTime.of(16, 0), zoneId)
        val startMs = startDateTime.toInstant.toEpochMilli
        val endMs = endDateTime.toInstant.toEpochMilli
        (startMs, endMs, zoneId)
    }
}

object PlanningPhaseBuilder {
    def build() = new PhaseRunner(new PlanningSource(), new PlanningEventGenerator())
}
