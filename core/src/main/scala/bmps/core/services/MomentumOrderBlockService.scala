package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Direction._
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.models.Candle
import bmps.core.models.SwingPoint
import bmps.core.models.OrderStatus
import java.time.Duration
import breeze.numerics.round

object MomentumOrderBlockService {
    final val RetraceAmount = 0.30
    final val StopLossThreshold = 0.20
    final val MaxWickRatio = 0.20
    
    def processState(state: SystemState): SystemState = {
        if (state.tradingSwingPoints.size >= 2) {
            val lastSwingPoint = state.tradingSwingPoints.last
            val priorSwingPoint = state.tradingSwingPoints.init.last
            val lastCandle = state.recentOneSecondCandles.last
            val magnitudeOfLastSwing = math.abs(lastSwingPoint.level - priorSwingPoint.level)
            val magnitudeOfRetrace = math.abs(lastSwingPoint.level - lastCandle.close)

            if (magnitudeOfRetrace / magnitudeOfLastSwing >= RetraceAmount &&
                lastSwingPoint.direction != priorSwingPoint.direction &&
                !hasOrderSinceSwingPoint(state) &&
                !candlesAboveOrBelow(state, lastSwingPoint) &&
                hasMomentum(state)
            ) {
                val order = createOrder(state, lastCandle, lastSwingPoint, magnitudeOfRetrace)
                state.copy(orders = state.orders :+ order)
            } else state
        } else state
    }

    private def candlesAboveOrBelow(state: SystemState, swing: SwingPoint) = {
        val swingTimestamp = swing.timestamp
        val candlesSince = state.tradingCandles.filter(_.timestamp > swingTimestamp) ++
                    state.recentOneSecondCandles.init.filter(_.timestamp > swingTimestamp)
        val lastOneSecondCandle = state.recentOneSecondCandles.last
        swing.direction match {
            case Up => 
                candlesSince.exists(_.high > lastOneSecondCandle.high) ||
                candlesSince.exists(_.low < swing.level)
            case Down => 
                candlesSince.exists(_.high > lastOneSecondCandle.high) ||
                candlesSince.exists(_.low < swing.level)
            case _ => true
        }
    }

    private def hasMomentum(state: SystemState) = {
        val secondToLast = state.recentOneSecondCandles.init.last
        val last = state.recentOneSecondCandles.last
        last.bodyHeight > secondToLast.bodyHeight &&
            last.wickToBodyRatio < MaxWickRatio
    }

    private def hasOrderSinceSwingPoint(state: SystemState) = {
        state.orders.exists(_.timestamp >= state.tradingSwingPoints.last.timestamp)
    }

    private def createOrder(state: SystemState, lastCandle: Candle, lastSwingPoint: SwingPoint, magnitudeOfRetrace: Double) = {
        val contract = state.contractSymbol.getOrElse(throw new IllegalStateException("No contract in state."))
        val lastOneMinuteCandle = state.tradingCandles.last
        val stopLossFallback = (magnitudeOfRetrace * StopLossThreshold)
        val (highLevel, lowLevel, orderType): (Float, Float, OrderType) = if (lastSwingPoint.direction == Up) {
            val high = lastCandle.close
            val low = (lastCandle.close - stopLossFallback).toFloat
            (high, low, OrderType.Long)
        } else {
            val high = (lastCandle.close + stopLossFallback).toFloat
            val low = lastCandle.close
            (high, low, OrderType.Short)
        }

        Order(
            low = lowLevel, 
            high = highLevel, 
            timestamp = lastOneMinuteCandle.timestamp, 
            orderType = orderType,
            entryType = EntryType.MomentumOrderBlock,
            contract = contract,
            status = OrderStatus.PlaceNow
        )
    }
}