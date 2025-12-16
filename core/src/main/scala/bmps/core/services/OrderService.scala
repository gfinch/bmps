package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderStatus
import bmps.core.models.Order
import bmps.core.models.Event
import bmps.core.models.PlanZoneType
import bmps.core.models.Direction
import breeze.numerics.nextPower
import bmps.core.models.EntryType
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant
import java.time.Duration
import bmps.core.models.Candle
import bmps.core.models.ContractType
import bmps.core.models.CandleDuration
import bmps.core.models.OrderType
import bmps.core.utils.TimestampUtils

class OrderService(val technicalAnalysisService: TechnicalAnalysisService, val mlStrategyService: MLStrategyService) {
    lazy val techAnalysisOrderService: TechnicalAnalysisOrderService = new TechnicalAnalysisOrderService()

    lazy val secondBySecondProcessors = Seq(
        technicalAnalysisService.processOneSecondState(_),
        // BouncingOrderBlockService.processState(_),
        // MomentumOrderBlockService.processState(_)
    )

    lazy val minuteByMinuteProcessors = Seq(
        technicalAnalysisService.processOneMinuteState(_),
        techAnalysisOrderService.processOneMinuteState(_),
        // mlStrategyService.processOneMinuteState(_)
        // EngulfingOrderBlockService.processState(_),
        // FairValueGapOrderBlockService.processState(_)
    )

    def determineTradingDirection(state: SystemState): Direction = {
        MarketTrendService.determineDirection(state.tradingCandles)
    }

    def buildOrders(state: SystemState, candle: Candle): SystemState = {
        val processors = if (candle.duration == CandleDuration.OneSecond) secondBySecondProcessors else minuteByMinuteProcessors
        processors.foldLeft(state) { (lastState, nextProcess) => nextProcess(lastState) }
    }
    
    //Allows single order to be placed
    def findOrderToPlace(state: SystemState, candle: Candle): Option[Order] = {
        state.orders.find { order => 
            (order.status == OrderStatus.PlaceNow || order.status == OrderStatus.Planned) && shouldPlaceOrder(order, state, candle)
        }
    }

    //Allows multiple orders to be placed
    def findOrdersToPlace(state: SystemState, candle: Candle): List[Order] = {
        state.orders.filter { order =>
            order.status == OrderStatus.PlaceNow || (order.status == OrderStatus.Planned && priceInProperPosition(order, candle))
        }
    }

    private def shouldPlaceOrder(order: Order, state: SystemState, candle: Candle): Boolean = {
        val activeOrders = state.orders.count(_.isActive)
        if (activeOrders == 0 && order.status == OrderStatus.PlaceNow) true
        else {
            val isOrderReady = if (order.entryType == EntryType.FairValueGapOrderBlock) {
                FairValueGapOrderBlockService.shouldPlaceOrder(order, candle)
            } else if (order.entryType == EntryType.EngulfingOrderBlock) {
                EngulfingOrderBlockService.shouldPlaceOrder(order, state, candle)
            } else true
            (activeOrders == 0 && isOrderReady && priceInProperPosition(order, candle))
        }
    }

    private def priceInProperPosition(order: Order, candle: Candle): Boolean = {
        order.orderType match {
            case OrderType.Long => 
                candle.close >= order.entryPoint
            case OrderType.Short => 
                candle.close <= order.entryPoint
        }
    }
}
