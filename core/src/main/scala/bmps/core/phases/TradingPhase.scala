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

class TradingEventGenerator(leadAccount: LeadAccountBroker, orderService: OrderService, swingService: SwingService = new SwingService(1)) extends EventGenerator {
    require(leadAccount.brokerCount >= 1, "Must have at least one account broker defined.")

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val tradingDirection = orderService.determineTradingDirection(state)
        state.copy(systemStatePhase = SystemStatePhase.Trading, tradingDirection = Some(tradingDirection))
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        candle.duration match {
            case CandleDuration.OneSecond => processOneSecond(state, candle)
            case CandleDuration.OneMinute => processOneMinute(state, candle)
            case _ => (state, List.empty[Event])
        }
    }

    def processOneSecond(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val newState = placeOrders(state, candle)
        (newState, List.empty[Event])
    }

    def processOneMinute(state: SystemState, candle: Candle): (SystemState, List[Event]) = {

        //Candle and swing processing
        val updatedCandles = state.tradingCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)

        //Order processing
        val withNewOrders = orderService.buildOrders(withSwings)
        val withOrders = adjustOrderState(withNewOrders, candle)

        val (withPlacedOrders, _) = processOneSecond(withOrders, candle)
        
        //Event processing
        val newSwingPoints = withPlacedOrders.tradingSwingPoints.drop(state.tradingSwingPoints.length)
        val swingEvents = newSwingPoints.map(Event.fromSwingPoint)
        val changedOrders = withPlacedOrders.orders.filterNot(state.orders.contains)
        val orderEvents = changedOrders.map(Event.fromOrder(_, leadAccount.riskPerTrade))
        val newModelPredictions = withPlacedOrders.tradingModelPredictions.drop(state.tradingModelPredictions.length)
        val predictionEvents = newModelPredictions.map(p => Event.fromModelPrediction(candle, p))

        val allEvents = swingEvents ++ orderEvents ++ predictionEvents
        (withOrders, allEvents)
    }

    private def adjustOrderState(state: SystemState, candle: Candle): SystemState = {
        val adjustedOrders = state.orders.map(o => leadAccount.updateOrderStatus(o, candle))
        state.copy(orders = adjustedOrders)
    }
    
    private def placeOrders(state: SystemState, candle: Candle): SystemState = {
        val placedOrders = orderService.findOrderToPlace(state, candle: Candle).map(leadAccount.placeOrder(_, candle)) match {
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
    def build(leadAccount: LeadAccountBroker, dataSource: DataSource, orderService: OrderService) = {
        new PhaseRunner(new TradingSource(dataSource), new TradingEventGenerator(leadAccount, orderService))
    }
}
