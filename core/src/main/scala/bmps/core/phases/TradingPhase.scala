package bmps.core.phases

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import fs2.Stream
import cats.effect.IO
import bmps.core.services.{SwingService, PlanZoneService, LiquidityZoneService}
import bmps.core.services.TechnicalAnalysisService
import bmps.core.io.ParquetSource
import bmps.core.models.{SystemState, Event, Candle}
import bmps.core.api.intf.{EventGenerator, CandleSource}
import bmps.core.api.run.PhaseRunner
import bmps.core.models.Order
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
import bmps.core.io.OrderSink
import bmps.core.strategies.lifecycle.OrderManager

class TradingEventGenerator(orderManager: OrderManager,
                            orderSink: OrderSink,
                            techAnalysisService: TechnicalAnalysisService,
                            swingService: SwingService = new SwingService(5, 0.5f)) extends EventGenerator {

    def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
        val tradingState = state.copy(systemStatePhase = SystemStatePhase.Trading)
        // orderSink.loadPastOrders(tradingState, 10)
        orderSink.loadAllPastOrders(tradingState)
    }

    override def finalize(state: SystemState): (SystemState, List[Event]) = {
        orderSink.saveOrders(state)
        super.finalize(state)
    }

    def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        if (candle.timestamp <= state.tradingCandles.last.timestamp) {
            //Candles came out of order, so discard. Cna happen during catchup.
            (state, List.empty[Event])
        } else candle.duration match {
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

        val withUpdatedOrderState = orderManager.manageOrders(newState, candle)
        val withTechAnalysis = techAnalysisService.processOneSecondState(withUpdatedOrderState)

        val changedOrders = withTechAnalysis.orders.filterNot(state.orders.contains)
        val orderEvents = changedOrders.map(Event.fromOrder)
        
        (withTechAnalysis, orderEvents)
    }

    def processOneMinute(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
        //Candle and swing processing every minute
        val updatedCandles = state.tradingCandles :+ candle
        val (swings, directionOption) = swingService.computeSwings(updatedCandles)
        val newDirection = directionOption.getOrElse(state.swingDirection)
        val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)

        //Technical Analysis
        val withTechAnalysis = techAnalysisService.processOneMinuteState(withSwings)

        //Order processing
        val withNewOrders = orderManager.buildAndPlaceOrders(withTechAnalysis, candle)
        
        //Event processing
        val newSwingPoints = withNewOrders.tradingSwingPoints.drop(state.tradingSwingPoints.length)
        val swingEvents = newSwingPoints.map(Event.fromSwingPoint)

        val techncialAnalysisEvent = buildTechnicalAnalysisEvent(withNewOrders)
        val changedOrders = withNewOrders.orders.filterNot(state.orders.contains)
        val orderEvents = changedOrders.map(Event.fromOrder)

        val allEvents = swingEvents ++ techncialAnalysisEvent ++ orderEvents
        (withNewOrders, allEvents)
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
    def build(
        dataSource: DataSource, 
        orderManager: OrderManager,
        techAnalysisService: TechnicalAnalysisService, 
        orderSink: OrderSink) = {
        new PhaseRunner(new TradingSource(dataSource), new TradingEventGenerator(orderManager, orderSink, techAnalysisService))
    }
}
