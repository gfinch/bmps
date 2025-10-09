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

object OrderService {

    def determineTradingDirection(state: SystemState): Direction = {
        MarketTrendService.determineDirection(state.tradingCandles)
    }

    def buildOrders(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "buildOrders called before there are candles in Trade state.")
        val processors = Seq(
            EngulfingOrderBlockService.processState(_), 
            // FairValueGapOrderBlockService.processState(_)
        )

        processors.foldLeft(state) { (lastState, nextProcess) => nextProcess(lastState) }
    }
    
    def findOrderToPlace(state: SystemState): Option[Order] = {
        require(state.tradingCandles.nonEmpty, "placeOrders called before there are candles in Trade state.")
        state.orders.find { order => 
            order.status == OrderStatus.Planned && shouldPlaceOrder(order, state)
        }
    }

    private def shouldPlaceOrder(order: Order, state: SystemState): Boolean = {
        val activeOrders = state.orders.count(_.isActive)
        val isOrderReady = if (order.entryType == EntryType.FairValueGapOrderBlock) {
            FairValueGapOrderBlockService.shouldPlaceOrder(order, state.tradingCandles.last)
        } else if (order.entryType == EntryType.EngulfingOrderBlock) {
            EngulfingOrderBlockService.shouldPlaceOrder(order, state)
        } else true
        (activeOrders == 0 && isOrderReady)
    }
}
