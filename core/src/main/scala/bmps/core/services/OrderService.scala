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
        //TODO ... need to determine order direction && if we're in an area of interest.
        require(state.tradingCandles.nonEmpty, "buildOrders called before there are candles in Trade state.")
        val processors = Seq(
            EngulfingOrderBlockService.processState(_), 
            FairValueGapOrderBlockService.processState(_)
        )

        processors.foldLeft(state) { (lastState, nextProcess) => nextProcess(lastState) }
    }
    
    def placeOrders(state: SystemState): SystemState = {
        require(state.tradingCandles.nonEmpty, "placeOrders called before there are candles in Trade state.")
        val timestamp = state.tradingCandles.last.timestamp
        var orderPlaced = false
        val updatedOrders = state.orders.zipWithIndex.map { case (order, i) => 
            if (order.status == OrderStatus.Planned && !orderPlaced && shouldPlaceOrder(order, state)) {
                orderPlaced = true
                placeOrder(order, timestamp)
            } else order
        }
        state.copy(orders = updatedOrders)
    }

    def isEndOfDay(candle: Candle): Boolean = {
        val zone = ZoneId.of("UTC") //Because the candles are offset to NY Time already.
        val localDate = Instant.ofEpochMilli(candle.timestamp).atZone(zone).toLocalDate
        val closingZdt = localDate.atTime(LocalTime.of(16, 0)).atZone(zone)
        val closingMillis = closingZdt.toInstant.toEpochMilli
        val tenMinutesMillis = Duration.ofMinutes(10).toMillis

        closingMillis - candle.timestamp <= tenMinutesMillis
    }

    private def shouldPlaceOrder(order: Order, state: SystemState): Boolean = {
        val activeOrders = state.orders.count(_.isActive)
        val isRightDirection = state.tradingDirection.exists(_ == order.direction)
        val isOrderReady = if (order.entryType == EntryType.FairValueGapOrderBlock) {
            FairValueGapOrderBlockService.shouldPlaceOrder(order, state.tradingCandles.last)
        } else true
        //TODO more sophisticated logic
        (activeOrders == 0 && isRightDirection && isOrderReady)
    }

    private def placeOrder(order: Order, timestamp: Long): Order = {
        require(order.status == OrderStatus.Planned, "Tried to place an order that was not in Planned state.")
        order.copy(status = OrderStatus.Placed, placedTimestamp = Some(timestamp))
    }
}
