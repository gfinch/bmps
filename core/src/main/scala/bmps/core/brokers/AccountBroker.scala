package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.OrderType
import bmps.core.models.CancelReason
import bmps.core.models.Candle
import bmps.core.models.OrderStatus._
import java.time.Duration
import bmps.core.models.SerializableOrder

sealed trait BrokerType
object BrokerType {
    case object LeadAccountBroker extends BrokerType
    case object SimulatedAccountBroker extends BrokerType
}

trait AccountBroker {
    val accountId: String
    val riskPerTrade: Double
    val brokerType: BrokerType

    def placeOrder(order: Order, candle: Candle): Order
    def fillOrder(order: Order, candle: Candle): Order
    def takeProfit(order: Order, candle: Candle): Order
    def takeLoss(order: Order, candle: Candle): Order
    def exitOrder(order: Order, candle: Candle): Order
    def cancelOrder(order: Order, candle: Candle, cancelReason: String): Order
}

class LeadAccountBroker(brokers: List[AccountBroker], riskDollars: Double = 550.0) extends AccountBroker {
    final val TenMinutes = Duration.ofMinutes(10).toMillis()

    val accountId = "LeadAccountBroker"
    val riskPerTrade = riskDollars
    val brokerType = BrokerType.LeadAccountBroker

    def placeOrder(order: Order, candle: Candle): Order = brokers.map(_.placeOrder(order, candle)).head
    def fillOrder(order: Order, candle: Candle): Order = brokers.map(_.fillOrder(order, candle)).head
    def takeProfit(order: Order, candle: Candle): Order = brokers.map(_.takeProfit(order, candle)).head
    def takeLoss(order: Order, candle: Candle): Order = brokers.map(_.takeLoss(order, candle)).head
    def exitOrder(order: Order, candle: Candle): Order = brokers.map(_.exitOrder(order, candle)).head
    def cancelOrder(order: Order, candle: Candle, cancelReason: String): Order = brokers.map(_.cancelOrder(order, candle, cancelReason)).head

    lazy val brokerCount = brokers.size

    def updateOrderStatus(order: Order, candle: Candle): Order = {
        val operations = Seq(
            checkIfOrderWasFilled(_,_),
            takeProfitOrLossFromTallCandle(_,_),
            takeProfitFromShortCandle(_,_),
            takeLossFromShortCandle(_,_),
            exitOrderAtEndOfDay(_,_),
            cancelPlannedOrderCandleOutside(_,_),
            cancelPlacedOrderCandleOutside(_,_), 
            cancelOldPlannedOrderWickOutside(_,_),
            cancelOldPlacedOrderWickOutside(_,_)
        )

        operations.foldLeft(order) { (updatedOrder, operation) =>
            operation(updatedOrder, candle)
        }
    }

    def buildSerializableOrder(order: Order): SerializableOrder = {
        SerializableOrder.fromOrder(order, riskPerTrade)
    }

    //Rule: Fill the order if candle crosses entry point.
    private def checkIfOrderWasFilled(order: Order, candle: Candle): Order = {
        if (order.status == Placed) {
            order.orderType match {
                case OrderType.Long =>
                    if (candle.low.value <= order.entryPoint) fillOrder(order, candle)
                    else order
                case OrderType.Short => 
                    if (candle.high.value >= order.entryPoint) fillOrder(order, candle)
                    else order
            }
        } else order
    }

    //Rule: Profit or loss from order if candle crosses both take profit and stop loss.
    //Rule: Bearish candles assume went high first, so take profit on long, loss on short
    //Rule: Bullish candles assume went low first, so take loss on long, profit on short
    private def takeProfitOrLossFromTallCandle(order: Order, candle: Candle): Order = {
        if (order.status == Filled) {
            order.orderType match {
                case OrderType.Long => 
                    if (candle.low.value <= order.stopLoss && candle.high.value >= order.takeProfit) {
                        if (candle.isBearish) takeProfit(order, candle)
                        else takeLoss(order, candle)
                    } else order
                case OrderType.Short => 
                    if (candle.high.value >= order.stopLoss && candle.low.value <= order.takeProfit) {
                        if (candle.isBearish) takeLoss(order, candle)
                        else takeProfit(order, candle)
                    } else order
            }
        } else order
    }

    //Rule: Profit if candle crosses profit line.
    private def takeProfitFromShortCandle(order: Order, candle: Candle): Order = {
        if (order.status == Filled) {
            order.orderType match {
                case OrderType.Long if candle.high.value >= order.takeProfit => takeProfit(order, candle)
                case OrderType.Short if candle.low.value <= order.takeProfit => takeProfit(order, candle)
                case _ => order
            }
        } else order
    }

    //Rule: Loss if candle crosses loss line.
    private def takeLossFromShortCandle(order: Order, candle: Candle): Order = {
        if (order.status == Filled) {
            order.orderType match {
                case OrderType.Long if candle.low.value <= order.stopLoss => takeLoss(order, candle)
                case OrderType.Short if candle.high.value >= order.stopLoss => takeLoss(order, candle)
                case _ => order
            }
        } else order
    }

    //Rule: During end of day, exit order
    private def exitOrderAtEndOfDay(order: Order, candle: Candle): Order = {
        if (candle.isEndOfDay) order.status match {
            case Planned | Placed => cancelOrder(order, candle, CancelReason.EndOfDay)
            case Filled => exitOrder(order, candle)
            case _ => order
        } else order
    }

    //Rule: If the candle is fully above or below the order, cancel it.
    private def cancelPlannedOrderCandleOutside(order: Order, candle: Candle): Order = {
        if (order.status == Planned) {
            val reason = CancelReason.FullCandleOutside
            order.orderType match {
                case OrderType.Long => 
                    if (candle.low.value >= order.takeProfit) cancelOrder(order, candle, reason)
                    else if (candle.high.value < order.stopLoss) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    if (candle.high.value <= order.takeProfit) cancelOrder(order, candle, reason)
                    else if (candle.low.value > order.stopLoss) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }

    private def cancelPlacedOrderCandleOutside(order: Order, candle: Candle): Order = {
        if (order.status == Placed) {
            val reason = CancelReason.FullCandleOutside
            order.orderType match {
                case OrderType.Long => 
                    // Only cancel if gaps above take profit (profitable direction)
                    if (candle.low.value >= order.takeProfit) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    // Only cancel if gaps below take profit (profitable direction)
                    if (candle.high.value <= order.takeProfit) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }

    //Rule: If the order is planned for 10 minutes and wicks above or below, cancel it.
    private def cancelOldPlannedOrderWickOutside(order: Order, candle: Candle): Order = {
        if (order.status == Planned && order.timestamp + TenMinutes <= candle.timestamp) {
            val reason = CancelReason.TenMinuteWickOutside
            order.orderType match {
                case OrderType.Long => 
                    if (candle.high.value >= order.takeProfit) cancelOrder(order, candle, reason)
                    else if (candle.low.value < order.stopLoss) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    if (candle.low.value <= order.takeProfit) cancelOrder(order, candle, reason)
                    else if (candle.high.value > order.stopLoss) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }

    private def cancelOldPlacedOrderWickOutside(order: Order, candle: Candle): Order = {
        if (order.status == Placed && order.timestamp + TenMinutes <= candle.timestamp) {
            val reason = CancelReason.TenMinuteWickOutside
            order.orderType match {
                case OrderType.Long => 
                    // Only cancel if wick is in profitable direction (above take profit)
                    if (candle.high.value >= order.takeProfit) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    // Only cancel if wick is in profitable direction (below take profit)
                    if (candle.low.value <= order.takeProfit) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }
}
