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
import bmps.core.io.DatabentoSource
import bmps.core.utils.TimestampUtils
import bmps.core.io.DataSource

class PlanningEventGenerator(dataSource: DataSource, swingService: SwingService = new SwingService(1)) extends EventGenerator {
    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val contractSymbol = dataSource.currentContractSymbol
        options.get("tradingDate") match {
            case Some(tradeDate) =>
                val localDate = TimestampUtils.toNewYorkLocalDate(tradeDate)
                state.copy(systemStatePhase = SystemStatePhase.Planning, tradingDay = localDate, contractSymbol = Some(contractSymbol))
            case None =>
                state.copy(systemStatePhase = SystemStatePhase.Planning, contractSymbol = Some(contractSymbol))
        }
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val updatedCandles = state.planningCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(planningCandles = updatedCandles, planningSwingPoints = swings, swingDirection = newDirection)
        val (withZones, zoneEvents) = PlanZoneService.processPlanZones(withSwings)
        val (updatedState, liquidEvents) = LiquidityZoneService.processLiquidityZones(withZones, candle)
        
        val newSwingPoints = updatedState.planningSwingPoints.drop(state.planningSwingPoints.length)
        val allEvents = newSwingPoints.map(Event.fromSwingPoint) ++ zoneEvents ++ liquidEvents 

        (updatedState, allEvents)
    }
}

class PlanningSource(dataSource: DataSource) extends CandleSource {
    def candles(state: SystemState): Stream[IO, Candle] = {
        val (plannedStart, endMs) = computePlanningWindow(state)

        //On restart, start at the last candle received
        val startMs = state.planningCandles.lastOption.map(_.timestamp + 1).getOrElse(plannedStart)
        dataSource.candlesInRangeStream(startMs, endMs)
    }

    private def computePlanningWindow(state: SystemState): (Long, Long) = {
        import java.time.{ZoneId, ZonedDateTime, LocalTime}
        import bmps.core.utils.MarketCalendar

        val tradingDay = state.tradingDay
        val startDate = MarketCalendar.getTradingDaysBack(tradingDay, 2)
        
        val startMs = TimestampUtils.midnight(startDate)
        val endMs = TimestampUtils.nineAM(tradingDay)
        (startMs, endMs)
    }
}

object PlanningPhaseBuilder {
    def build(dataSource: DataSource) = new PhaseRunner(new PlanningSource(dataSource), new PlanningEventGenerator(dataSource))
}
