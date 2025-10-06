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
import bmps.core.io.PolygonAPISource
import bmps.core.models.CandleDuration

class PreparingEventGenerator(swingService: SwingService = new SwingService(1)) extends EventGenerator with TradingDate {

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val earliestPlanZone = state.planZones.filter(_.isActive).map(_.startTime).minOption
        val earliestExtreme = state.daytimeExtremes.map(_.timestamp).minOption
        val earliest = (earliestPlanZone.toList ++ earliestExtreme.toList).minOption
        state.copy(systemStatePhase = SystemStatePhase.Preparing, tradingReplayStartTime = earliest)
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val updatedCandles = state.tradingCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)
        val (updatedState, _) = if (isInTradingDate(withSwings.tradingDay, candle.timestamp)) {
            LiquidityZoneService.processLiquidityZones(withSwings, candle)
        } else (withSwings, List.empty)

        val newSwingPoints = updatedState.tradingSwingPoints.drop(state.tradingSwingPoints.length)
        val allEvents = newSwingPoints.map(Event.fromSwingPoint)

        (updatedState, allEvents)
    }

    override def finalize(state: SystemState): (SystemState, List[Event]) = {
        val zoneEvents = state.planZones.map(Event.fromPlanZone)
        val liquidEvents = state.daytimeExtremes.filter(_.endTime.isEmpty).map(Event.fromDaytimeExtreme)
        (state, zoneEvents ++ liquidEvents)
    }
}

class PreparingSource extends CandleSource {
    // lazy val source = new PolygonAPISource(CandleDuration.OneMinute)
    lazy val source = new ParquetSource(CandleDuration.OneMinute)

    def candles(state: SystemState): Stream[IO, Candle] = {
        val (startMs, endMs, zoneId) = computePreparingWindow(state)
        source.candlesInRangeStream(startMs, endMs, zoneId)
    }

    private def computePreparingWindow(state: SystemState): (Long, Long, java.time.ZoneId) = {
        import java.time.{ZoneId, ZonedDateTime, LocalTime}
        val zoneId = ZoneId.of("America/New_York")
        val tradingDay = state.tradingDay
        val endDateTime = ZonedDateTime.of(tradingDay, LocalTime.of(9, 30), zoneId)
        val defaultStartTime = endDateTime.minusHours(12).toInstant().toEpochMilli()
        val startMs = state.tradingReplayStartTime.getOrElse(defaultStartTime)
        val endMs = endDateTime.toInstant.toEpochMilli
        (startMs, endMs, zoneId)
    }
}

object PreparingPhaseBuilder {
    def build() = new PhaseRunner(new PreparingSource(), new PreparingEventGenerator())
}

