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
import bmps.core.models.Order
import bmps.core.services.OrderService
import cats.instances.order
import bmps.core.models.SystemStatePhase
import bmps.core.io.PolygonAPISource
import bmps.core.models.CandleDuration

class TradingEventGenerator(swingService: SwingService = new SwingService(1)) extends EventGenerator with TradingDate {

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val tradingDirection = OrderService.determineTradingDirection(state)
        state.copy(systemStatePhase = SystemStatePhase.Trading, tradingDirection = Some(tradingDirection))
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val updatedCandles = state.tradingCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)

        val withOrders = if (OrderService.isEndOfDay(candle)) {
            val updatedOrders = withSwings.orders.map(_.adjustState(candle)).map(_.cancelOrSell(candle))
            withSwings.copy(orders = updatedOrders)
        } else {
            val updatedOrders = withSwings.orders.map(_.adjustState(candle))
            val withOldOrders = withSwings.copy(orders = updatedOrders)
            val newTradingDirection = OrderService.determineTradingDirection(withOldOrders)
            val withNewTradingDirection = if (!state.tradingDirection.contains(newTradingDirection)) {
                withOldOrders.copy(tradingDirection = Some(newTradingDirection))
            } else withOldOrders
            val withNewOrders = OrderService.buildOrders(withNewTradingDirection)
            OrderService.placeOrders(withNewOrders)
        }

        val newSwingPoints = withOrders.tradingSwingPoints.drop(state.tradingSwingPoints.length)
        val swingEvents = newSwingPoints.map(Event.fromSwingPoint)

        val changedOrders = withOrders.orders.filterNot(state.orders.contains)
        val orderEvents = changedOrders.map(Event.fromOrder)
        val tradingDirectionEvent = if (!state.tradingDirection.equals(withOrders.tradingDirection)) {
            List(Event.fromTradeDirection(withOrders.tradingCandles.last, withOrders.tradingDirection))
        } else List.empty

        val allEvents = swingEvents ++ orderEvents ++ tradingDirectionEvent
        (withOrders, allEvents)
    }
}

class TradingSource extends CandleSource {
    // lazy val source = new PolygonAPISource(CandleDuration.OneMinute)
    lazy val source = new ParquetSource(CandleDuration.OneMinute)

    def candles(state: SystemState): Stream[IO, Candle] = {
        val (startMs, endMs, zoneId) = computeTradingWindow(state)
        source.candlesInRangeStream(startMs, endMs, zoneId)
    }

    private def computeTradingWindow(state: SystemState): (Long, Long, java.time.ZoneId) = {
        import java.time.{ZoneId, ZonedDateTime, LocalTime}
        val zoneId = ZoneId.of("America/New_York")
        val tradingDay = state.tradingDay
        val startTime = ZonedDateTime.of(tradingDay, LocalTime.of(9, 30), zoneId)
        val endTime = ZonedDateTime.of(tradingDay, LocalTime.of(16, 0), zoneId)
        val startMs = startTime.toInstant().toEpochMilli
        val endMs = endTime.toInstant.toEpochMilli
        (startMs, endMs, zoneId)
    }
}

object TradingPhaseBuilder {
    def build() = new PhaseRunner(new TradingSource(), new TradingEventGenerator())
}
