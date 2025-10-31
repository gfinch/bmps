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
import bmps.core.io.DataSource
import bmps.core.utils.TimestampUtils.midnight
import bmps.core.utils.TimestampUtils
import breeze.linalg.min

class PreparingEventGenerator(swingService: SwingService = new SwingService(5, 0.5f)) extends EventGenerator {

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val earliestPlanZone = state.planZones.filter(_.isActive).map(_.startTime).minOption
        val earliestExtreme = state.daytimeExtremes.map(_.timestamp).minOption
        val earliest = (earliestPlanZone.toList ++ earliestExtreme.toList).minOption
        state.copy(systemStatePhase = SystemStatePhase.Preparing, tradingReplayStartTime = earliest)
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val updatedCandles = state.tradingCandles :+ candle
        // val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)
        val (updatedState, _) = LiquidityZoneService.processLiquidityZones(withSwings, candle)

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

class PreparingSource(dataSource: DataSource) extends CandleSource {
    def candles(state: SystemState): Stream[IO, Candle] = {
        val (plannedStart, endMs) = computePreparingWindow(state)
        // val minPrestart = state.tradingReplayStartTime.map(s => if (s < plannedStart) s else plannedStart).getOrElse(plannedStart)
        // val startMs = state.tradingCandles.lastOption.map(_.timestamp + 1).getOrElse(minPrestart)
        // println(s"Planned: $plannedStart, MinPrestart: $minPrestart, StartMs: $startMs")

        val startMs = state.tradingCandles.lastOption.map(_.timestamp + 1).getOrElse(plannedStart)
        dataSource.candlesInRangeStream(startMs, endMs)
    }

    private def computePreparingWindow(state: SystemState): (Long, Long) = {
        // val startMs = TimestampUtils.midnight(state.tradingDay)
        val startMs = TimestampUtils.sevenThirty(state.tradingDay)
        val endMs = TimestampUtils.newYorkOpen(state.tradingDay)
        
        (startMs, endMs)
    }
}

object PreparingPhaseBuilder {
    def build(dataSource: DataSource) = new PhaseRunner(new PreparingSource(dataSource), new PreparingEventGenerator())
}

