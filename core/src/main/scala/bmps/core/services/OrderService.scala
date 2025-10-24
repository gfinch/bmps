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

class OrderService(aiOrderBlockService: AIOrderBlockService) {

    def determineTradingDirection(state: SystemState): Direction = {
        MarketTrendService.determineDirection(state.tradingCandles)
    }

    def buildOrders(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "buildOrders called before there are candles in Trade state.")
        val processors = Seq(
            // EngulfingOrderBlockService.processState(_), 
            // FairValueGapOrderBlockService.processState(_),
            aiOrderBlockService.processState(_)
        )

        processors.foldLeft(state) { (lastState, nextProcess) => nextProcess(lastState) }
    }
    
    def findOrderToPlace(state: SystemState, candle: Candle): Option[Order] = {
        state.orders.find { order => 
            order.status == OrderStatus.Planned && shouldPlaceOrder(order, state, candle)
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
            (activeOrders == 0 && isOrderReady)
        }
    }
}
