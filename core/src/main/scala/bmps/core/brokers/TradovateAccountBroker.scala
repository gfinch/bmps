package bmps.core.brokers

import bmps.core.brokers.rest.TradovateBroker
import bmps.core.models.Order
import bmps.core.models.Candle
import bmps.core.brokers.rest.PlannedOrder
import bmps.core.models.ContractType
import bmps.core.models.OrderStatus
import java.time.Instant
import scala.collection.concurrent.TrieMap
import bmps.core.brokers.rest.PlaceOsoResponse
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus._
import bmps.core.models.CancelReason
import bmps.core.utils.TimestampUtils
import bmps.core.models.DetermineContracts

case class TradovateOrder(order: Order, osoResult: PlaceOsoResponse)

class TradovateAccountBroker(val accountId: String, val riskPerTrade: Double, tradovateBroker: TradovateBroker) extends AccountBroker with DetermineContracts {
    val brokerType: BrokerType = BrokerType.TradovateAccountBroker

    //Keeps a private version of each order for this broker to use.
    private val tradovateOrders = TrieMap[Long, TradovateOrder]()

    def placeOrder(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            println(s"Attempting to place order with Tradovate: ${order.timestamp} - ${order.orderType} - ${order.entryPoint}")
            val (osoResult, newOrder) = doPlaceOrder(order, candle)
            println(s"OSO order id: ${osoResult.orderId}, profit id: ${osoResult.oso1Id}, loss id: ${osoResult.oso2Id}")

            val systemOrderId = order.timestamp
            val tradovateOrder = TradovateOrder(order, osoResult)
            tradovateOrders.put(systemOrderId, tradovateOrder)

            order
        } else {
            println(s"[TradovateAccountBroker] Not placing order - back testing or catching up.")
            println(s"[TradovateAccountBroker] ${TimestampUtils.toNewYorkTimeString(candle.timestamp)} - ${TimestampUtils.toNewYorkTimeString(candle.createdAt)}")
            order //don't place an order with Tradovate if we're back testing or catching up
        }
    }
    def fillOrder(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            doUpdateOrder(order, candle)
        } else order
    }
    def takeProfit(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            doUpdateOrder(order, candle)
        } else order
    }
    def takeLoss(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            doUpdateOrder(order, candle)
        } else order
    }
    def exitOrder(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            doExitOrder(order, candle, CancelReason.EndOfDay)
        } else order
    }
    def cancelOrder(order: Order, candle: Candle, cancelReason: String): Order = {
        if (candle.isLive) {
            doExitOrder(order, candle, cancelReason)
        } else order
    }

    private def doExitOrder(order: Order, candle: Candle, cancelReason: String): Order = {
        println(s"[TradovateAccountBroker] Exiting order ${order.timestamp} - Reason: $cancelReason")
        tradovateOrders.get(order.timestamp).flatMap { tradovateOrder =>
            println(s"[TradovateAccountBroker] Tradovate order id: ${tradovateOrder.osoResult.orderId} profit id: ${tradovateOrder.osoResult.oso1Id}, loss id: ${tradovateOrder.osoResult.oso2Id}")
            tradovateOrder.osoResult.orderId.map { oid => 
                val newOrder = doCancelOrLiquidate(tradovateOrder.order, oid, candle, cancelReason)
                val newTradovateOrder = tradovateOrder.copy(order = newOrder)
                tradovateOrders.put(order.timestamp, newTradovateOrder)
                newOrder
            }
        }.getOrElse(order)
    }

    private def doUpdateOrder(order: Order, candle: Candle): Order = {
        updateOrder(order, candle).map { tradovateOrder => 
            val key = tradovateOrder.order.timestamp
            tradovateOrders.put(key, tradovateOrder)
            tradovateOrder.order
        }.getOrElse(order)
    }

    private def updateOrder(order: Order, candle: Candle): Option[TradovateOrder] = {
        tradovateOrders.get(order.timestamp) match {
            case Some(tradovateOrder) if tradovateOrder.order.closeTimestamp.isEmpty =>
                (tradovateOrder.osoResult.orderId, tradovateOrder.osoResult.oso1Id, tradovateOrder.osoResult.oso2Id) match {
                    case (Some(mainOrder), Some(profitOrder), Some(lossOrder)) =>
                        val orderState = tradovateBroker.orderStatus(mainOrder)
                        val newOrder = mapStatusToOrder(tradovateOrder.order, orderState, mainOrder, profitOrder, lossOrder, candle)
                        Some(tradovateOrder.copy(order = newOrder))
                    case (Some(mainOrder), _, _) => 
                        println(s"[TradovateAccountBroker] Unexpected order state. Main order with no child orders.")
                        val newOrder = doCancelOrLiquidate(tradovateOrder.order, mainOrder, candle, "Main order has no stop or loss orders associated.")
                        Some(tradovateOrder.copy(order = newOrder))
                    case _ => 
                        println(s"[TradovateAccountBroker] There are no Tradovate orders to update.")
                        Some(tradovateOrder)
                }
            case Some(tradovateOrder) => 
                println(s"[TradovateAccountBroker] Attempting to update an already closed order.")
                Some(tradovateOrder)
            case None => 
                println(s"[TradovateAccountBroker] Unexpected udpateOrder request. The order has not been seen before.")
                None
        }
    }


    private def mapStatusToOrder(order: Order, orderState: OrderState, mainOrderId: Long, profitOrder: Long, lossOrder: Long, candle: Candle): Order = {
        (order.status, orderState.status) match {
            case (Placed, Placed) | (Cancelled, Cancelled) => order //No change
            case (_, Filled) => 
                val profitOrderStatus = tradovateBroker.orderStatus(profitOrder)
                val lossOrderStatus = tradovateBroker.orderStatus(lossOrder)
                println(s"[TradovateAccountBroker] Tradovate order ${mainOrderId} has been filled.")
                println(s"[TradovateAccountBroker] Profit order ($profitOrder) status: ${profitOrderStatus.status}, Loss order ($lossOrder) status: ${lossOrderStatus.status}")
                (profitOrderStatus.status, lossOrderStatus.status) match { 
                    case (Placed, Placed) if order.status == Placed => order //No change
                    case (Placed, Placed) => 
                        order.copy(status = Placed, placedTimestamp = Some(candle.timestamp))
                    case (Filled, _) => 
                        order.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
                    case (_, Filled) =>
                        order.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
                    case _ => //Unexpected state - cancel or liquidate
                        println(s"[TradovateAccountBroker] .")
                        doCancelOrLiquidate(order, orderState.orderId, candle: Candle, "Profit or Loss are in unexpected state.")
                        doCancelOrLiquidate(order, profitOrder, candle: Candle, "Profit or Loss are in unexpected state.")
                        doCancelOrLiquidate(order, lossOrder, candle: Candle, "Profit or Loss are in unexpected state.")
                }
                //Check the state of the profitOrder and the lossOrder
            case (_, Cancelled) =>
                order.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp), cancelReason = Some("The backing tradovate order has been cancelled."))
            case _ => 
                println(s"[TradovateAccountBroker] Unexpected: TradovateBroker returned Profit or Loss state.")
                order
        }
    }

    private def doPlaceOrder(order: Order, candle: Candle): (PlaceOsoResponse, Order) = {
        require(order.status == OrderStatus.Planned, "Attempting to place an order that was already placed.")
        require(tradovateOrders.get(order.timestamp).isEmpty, "Attempting to place an order whose tradovate order ids are missing.")
        val (contract, contracts) = determineContracts(order, riskPerTrade)
        val plannedOrder = PlannedOrder(
            entry = order.entryPoint, 
            stopLoss = order.stopLoss, 
            takeProfit = order.takeProfit, 
            orderType = order.orderType, 
            contractId = contract, 
            contracts = contracts
        )

        val osoResult = tradovateBroker.placeOrder(plannedOrder)
        
        val newOrder = if (osoResult.failureReason.isDefined) {
            val failText = osoResult.failureReason.get + osoResult.failureText.getOrElse("")
            println(s"[TradovateAccountBroker] Failed to place order: $failText.")
            order.copy(
                status = OrderStatus.Cancelled, 
                closeTimestamp = Some(candle.timestamp),
                cancelReason = Some(failText)
            )
        } else if (osoResult.orderId.isDefined) {
            println(s"[TradovateAccountBroker] Order ${osoResult.orderId.get}.")
            order.copy(
                status = OrderStatus.Placed,
                placedTimestamp = Some(candle.timestamp)
            )
        } else {
            println(s"[TradovateAccountBroker] Unexpected placeOrder result: $osoResult.")
            order
        }

        (osoResult, newOrder)
    }

    private def doCancelOrLiquidate(order: Order, orderId: Long, candle: Candle, cancelReason: String): Order = {
        tradovateBroker.cancelOrLiquidate(orderId)
        order.copy(status = OrderStatus.Cancelled, closeTimestamp = Some(candle.timestamp), cancelReason = Some(cancelReason))
    }
}
