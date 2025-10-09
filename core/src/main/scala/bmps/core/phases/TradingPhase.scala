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
import bmps.core.models.Order
import bmps.core.services.OrderService
import cats.instances.order
import bmps.core.models.SystemStatePhase
import bmps.core.io.PolygonAPISource
import bmps.core.models.CandleDuration
import bmps.core.brokers.LeadAccountBroker
import bmps.core.io.DatabentoSource

class TradingEventGenerator(leadAccount: LeadAccountBroker, swingService: SwingService = new SwingService(1)) extends EventGenerator with TradingDate {
    require(leadAccount.brokerCount >= 1, "Must have at least one account broker defined.")

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val tradingDirection = OrderService.determineTradingDirection(state)
        state.copy(systemStatePhase = SystemStatePhase.Trading, tradingDirection = Some(tradingDirection))
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        //Candle and swing processing
        val updatedCandles = state.tradingCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)

        //Order processing
        val withNewOrders = OrderService.buildOrders(withSwings)
        val withPlacedOrders = placeOrders(withNewOrders, candle)
        val withOrders = adjustOrderState(withPlacedOrders, candle)
        
        //Event processing
        val newSwingPoints = withOrders.tradingSwingPoints.drop(state.tradingSwingPoints.length)
        val swingEvents = newSwingPoints.map(Event.fromSwingPoint)
        val changedOrders = withOrders.orders.filterNot(state.orders.contains)
        val orderEvents = changedOrders.map(Event.fromOrder(_, leadAccount.riskPerTrade))

        val allEvents = swingEvents ++ orderEvents
        (withOrders, allEvents)
    }

    private def adjustOrderState(state: SystemState, candle: Candle): SystemState = {
        val adjustedOrders = state.orders.map(o => leadAccount.updateOrderStatus(o, candle))
        state.copy(orders = adjustedOrders)
    }
    
    private def placeOrders(state: SystemState, candle: Candle): SystemState = {
        val placedOrders = OrderService.findOrderToPlace(state).map(leadAccount.placeOrder(_, candle)) match {
            case Some(placedOrder) =>
                state.orders.map(order => if (order.timestamp == placedOrder.timestamp) placedOrder else order)
            case None =>
                state.orders
        }
        state.copy(orders = placedOrders)
    }
}

class TradingSource extends CandleSource {
    // lazy val source = new PolygonAPISource(CandleDuration.OneMinute)
    // lazy val source = new ParquetSource(CandleDuration.OneMinute)
    lazy val source = new DatabentoSource(CandleDuration.OneMinute)

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
    def build(leadAccount: LeadAccountBroker) = new PhaseRunner(new TradingSource(), new TradingEventGenerator(leadAccount))
}
