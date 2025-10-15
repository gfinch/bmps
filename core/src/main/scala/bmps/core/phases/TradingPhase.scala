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
import bmps.core.models.CandleDuration
import bmps.core.brokers.LeadAccountBroker
import bmps.core.io.DatabentoSource
import bmps.core.io.DataSource
import bmps.core.utils.TimestampUtils

class TradingEventGenerator(leadAccount: LeadAccountBroker, swingService: SwingService = new SwingService(1)) extends EventGenerator {
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
        val withAdjustedOrders = adjustOrderState(withNewOrders, candle)
        val withOrders = placeOrders(withAdjustedOrders, candle)
        
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

class TradingSource(dataSource: DataSource) extends CandleSource {
    
    def candles(state: SystemState): Stream[IO, Candle] = {
        val (plannedStart, endMs) = computeTradingWindow(state)
        val startMs = state.tradingCandles.lastOption.map(_.timestamp + 1).getOrElse(plannedStart)

        dataSource.candlesInRangeStream(startMs, endMs)
    }

    private def computeTradingWindow(state: SystemState): (Long, Long) = {
        val startMs = TimestampUtils.newYorkOpen(state.tradingDay)
        val endMs = TimestampUtils.newYorkClose(state.tradingDay)
        
        (startMs, endMs)
    }
}

object TradingPhaseBuilder {
    def build(leadAccount: LeadAccountBroker, dataSource: DataSource) = new PhaseRunner(new TradingSource(dataSource), new TradingEventGenerator(leadAccount))
}
