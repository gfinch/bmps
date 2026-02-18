package bmps.core.brokers

import bmps.core.brokers.rest.TradovateBroker
import bmps.core.models.Order
import bmps.core.models.Candle
import bmps.core.models.ContractType
import bmps.core.models.OrderStatus
import java.time.Instant
import scala.collection.concurrent.TrieMap
import bmps.core.brokers.rest.PlaceOsoResponse
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus._
import bmps.core.utils.TimestampUtils
import bmps.core.models.OrderType

case class TradovateOrder(order: Order, osoResult: PlaceOsoResponse)

class TradovateAccountBroker(val accountId: String, 
                             val riskPerTrade: Double, 
                             val feePerESContract: Double,
                             val feePerMESContract: Double,
                             tradovateBroker: TradovateBroker) extends AccountBroker {
    val brokerType: BrokerType = BrokerType.TradovateAccountBroker

    lazy val accountBalance: Option[Double] = tradovateBroker.getCashBalances().headOption.map(_.amount)

    //Keeps a private version of each order for this broker to use.
    private val tradovateOrders = TrieMap[Long, TradovateOrder]()

    def placeOrder(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            println(s"Attempting to place order with Tradovate: ${order.log(candle)}")
            val (osoResult, newOrder) = doPlaceOrder(order, candle)
            println(s"OSO order id: ${osoResult.orderId}, stop id: ${osoResult.oso1Id}")

            val systemOrderId = order.timestamp
            val tradovateOrder = TradovateOrder(order, osoResult)
            tradovateOrders.put(systemOrderId, tradovateOrder)

            order
        } else {
            //don't place an order with Tradovate if we're back testing or catching up
            order 
        }
    }

    def fillOrder(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            doUpdateOrder(order, candle)
        } else order
    }
    
    def exitOrder(order: Order, candle: Candle, exitPrice: Double): Order = {
        if (candle.isLive) {
            doExitOrder(order, candle)
        } else order
    }

    def cancelOrder(order: Order, candle: Candle): Order = {
        if (candle.isLive) {
            doExitOrder(order, candle)
        } else order
    }

    def reconcileOrder(order: Order): Order = order
    def resetStop(order: Order, stop: Double, candle: Candle): Order = order

    private def doExitOrder(order: Order, candle: Candle): Order = {
        println(s"[TradovateAccountBroker] Exiting order ${order.log(candle)}")
        tradovateOrders.get(order.timestamp).flatMap { tradovateOrder =>
            println(s"[TradovateAccountBroker] Tradovate order id: ${tradovateOrder.osoResult.orderId} profit id: ${tradovateOrder.osoResult.oso1Id}, loss id: ${tradovateOrder.osoResult.oso2Id}")
            tradovateOrder.osoResult.orderId.map { oid => 
                val newOrder = doCancelOrLiquidate(tradovateOrder.order, oid, candle)
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
                    case (Some(mainOrder), Some(lossOrder), Some(profitOrder)) =>
                        val orderState = tradovateBroker.orderStatus(mainOrder)
                        val newOrder = mapStatusToOrder(tradovateOrder.order, orderState, mainOrder, profitOrder, lossOrder, candle)
                        Some(tradovateOrder.copy(order = newOrder))
                    case (Some(mainOrder), _, _) => 
                        println(s"[TradovateAccountBroker] Unexpected order state. Main order with no child orders.")
                        val newOrder = doCancelOrLiquidate(tradovateOrder.order, mainOrder, candle)
                        Some(tradovateOrder.copy(order = newOrder))
                    case _ => 
                        println(s"[TradovateAccountBroker] There are no Tradovate orders to update.")
                        Some(tradovateOrder)
                }
            case Some(tradovateOrder) => 
                println(s"[TradovateAccountBroker] Attempting to update an already closed order.")
                Some(tradovateOrder)
            case None => 
                println(s"[TradovateAccountBroker] This order was never placed and cannot be updated.")
                None
        }
    }


    private def mapStatusToOrder(order: Order, orderState: OrderState, mainOrderId: Long, profitOrder: Long, lossOrder: Long, candle: Candle): Order = {
        (order.status, orderState.status) match {
            case (Placed, Placed) | (Cancelled, Cancelled) => order //No change
            case (_, Filled) => 
                val profitOrderStatus = tradovateBroker.orderStatus(profitOrder)
                val stopOrderStatus = tradovateBroker.orderStatus(lossOrder)
                
                println(s"[TradovateAccountBroker] Tradovate order ${mainOrderId} has been filled.")
                println(s"[TradovateAccountBroker] Profit order ($profitOrder) status: ${profitOrderStatus.status}, Loss order ($lossOrder) status: ${stopOrderStatus.status}")
                (profitOrderStatus.status, stopOrderStatus.status) match { 
                    case (Placed, Placed) if order.status == Placed => order //No change
                    case (Placed, Placed) => 
                        order.copy(status = Placed, placedTimestamp = Some(candle.timestamp))
                    case (Filled, _) => 
                        val exitPrice = profitOrderStatus.fill
                        val fillTimestamp = profitOrderStatus.fillTimestamp
                        order.copy(status = Profit, closeTimestamp = fillTimestamp, exitPrice = exitPrice)
                    case (_, Filled) =>
                        val status = determineProfitOrLoss(order, stopOrderStatus)
                        val exitPrice = stopOrderStatus.fill
                        val fillTimestamp = stopOrderStatus.fillTimestamp
                        order.copy(status = status, closeTimestamp = fillTimestamp, exitPrice = exitPrice)
                    case _ => //Unexpected state - cancel or liquidate
                        println(s"[TradovateAccountBroker] - cancelling or liquidating order because of unexpected state.")
                        doCancelOrLiquidate(order, orderState.orderId, candle: Candle)
                        doCancelOrLiquidate(order, profitOrder, candle: Candle)
                        doCancelOrLiquidate(order, lossOrder, candle: Candle)
                }
                //Check the state of the profitOrder and the lossOrder
            case (_, Cancelled) =>
                order.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case _ => 
                println(s"[TradovateAccountBroker] Unexpected: TradovateBroker returned Profit or Loss state.")
                order
        }
    }

    private def determineProfitOrLoss(order: Order, orderState: OrderState): OrderStatus = {
        (order.orderType, orderState.fill) match {
            case (OrderType.Long, Some(fill)) if fill > order.entryPrice => OrderStatus.Profit
            case (OrderType.Long, Some(fill)) if fill <= order.entryPrice => OrderStatus.Loss
            case (OrderType.Short, Some(fill)) if fill < order.entryPrice => OrderStatus.Profit
            case (OrderType.Short, Some(fill)) if fill >= order.entryPrice => OrderStatus.Loss
            case _ => OrderStatus.Loss //default
        }
    }

    private def roundToTickSize(price: Float, tickSize: Float = 0.25f): Float = {
        // Use integer arithmetic to avoid floating point precision errors
        // For 0.25 tick: multiply by 4, round, then divide by 4
        val multiplier = (1.0 / tickSize).toInt
        (math.round(price * multiplier).toFloat / multiplier)
    }

    private def doPlaceOrder(order: Order, candle: Candle): (PlaceOsoResponse, Order) = {
        require(order.status == OrderStatus.Planned || order.status == OrderStatus.PlaceNow, "Attempting to place an order that was already placed.")
        require(tradovateOrders.get(order.timestamp).isEmpty, "Attempting to place an order that was already placed on Tradovate.")
        val osoResult = tradovateBroker.placeOSOOrder(order)
        
        val newOrder = if (osoResult.failureReason.isDefined) {
            val failText = osoResult.failureReason.get + osoResult.failureText.getOrElse("")
            println(s"[TradovateAccountBroker] Failed to place order: $failText.")
            order.copy(
                status = OrderStatus.Cancelled, 
                closeTimestamp = Some(candle.timestamp)
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

    private def doCancelOrLiquidate(order: Order, orderId: Long, candle: Candle): Order = {
        tradovateBroker.cancelOrLiquidate(orderId)
        order.copy(status = OrderStatus.Cancelled, closeTimestamp = Some(candle.timestamp))
    }
}
