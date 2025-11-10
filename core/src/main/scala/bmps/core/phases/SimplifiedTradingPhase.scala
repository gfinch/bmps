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
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus
import bmps.core.models.TechnicalAnalysis

class SimpleTradingEventGenerator(leadAccount: LeadAccountBroker, orderService: OrderService, swingService: SwingService = new SwingService(5, 0.5f)) extends EventGenerator {
    require(leadAccount.brokerCount >= 1, "Must have at least one account broker defined.")
    val technicalAnalysisService = orderService.technicalAnalysisService

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        state.copy(systemStatePhase = SystemStatePhase.Trading)
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        candle.duration match {
            case CandleDuration.OneSecond => processOneSecond(state, candle)
            case CandleDuration.OneMinute => processOneMinute(state, candle)
            case _ => (state, List.empty[Event])
        }
    }

    def processOneSecond(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        val newState = state.copy(recentOneSecondCandles = 
            if (state.recentOneSecondCandles.size > 10) {
                state.recentOneSecondCandles.tail :+ candle
            } else state.recentOneSecondCandles :+ candle
        )
        
        (technicalAnalysisService.processOneSecondState(newState), List.empty[Event])
    }

    def processOneMinute(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        //Candle and swing processing every minute
        val updatedCandles = state.tradingCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)
        
        //Event processing
        val newSwingPoints = withSwings.tradingSwingPoints.drop(state.tradingSwingPoints.length)
        val swingEvents = newSwingPoints.map(Event.fromSwingPoint)

        val withTechAnalysis = technicalAnalysisService.processOneMinuteState(withSwings)
        val techncialAnalysisEvent = buildTechnicalAnalysisEvent(withTechAnalysis)

        val allEvents = swingEvents ++ techncialAnalysisEvent
        (withTechAnalysis, allEvents)
    }

    private def buildTechnicalAnalysisEvent(state: SystemState): Option[Event] = {
        if (state.recentTrendAnalysis.nonEmpty &&
            state.recentMomentumAnalysis.nonEmpty &&
            state.recentVolumeAnalysis.nonEmpty &&
            state.recentVolatilityAnalysis.nonEmpty) Some(Event.fromTechnicalAnalysis(TechnicalAnalysis(
                state.tradingCandles.last.timestamp,
                state.recentTrendAnalysis.last,
                state.recentMomentumAnalysis.last,
                state.recentVolumeAnalysis.last,
                state.recentVolatilityAnalysis.last
            ))) else None
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
        new PhaseRunner(new TradingSource(dataSource), new SimpleTradingEventGenerator(leadAccount, orderService))
    }
}
