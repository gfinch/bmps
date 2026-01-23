package bmps.core.brokers

import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.OrderType
import bmps.core.models.CancelReason
import bmps.core.models.Candle
import bmps.core.models.OrderStatus._
import java.time.Duration
import bmps.core.models.SerializableOrder
import bmps.core.utils.TimestampUtils
import bmps.core.models.EntryType
import bmps.core.models.CandleDuration
import java.security.KeyStore.Entry

sealed trait BrokerType
object BrokerType {
    case object LeadAccountBroker extends BrokerType
    case object SimulatedAccountBroker extends BrokerType
    case object TradovateAccountBroker extends BrokerType
}

case class WinRateByOrderType(orderType: EntryType, winning: Int, losing: Int)

case class OrderReport(orders: List[SerializableOrder], winning: Int, losing: Int,
                       averageWinDollars: Double, averageLossDollars: Double,
                       maxDrawdownDollars: Double, totalFees: Double, totalPnL: Double, winRates: Seq[WinRateByOrderType])

trait AccountBroker {
    val accountId: String
    val riskPerTrade: Double
    val feePerMESContract: Double
    val feePerESContract: Double
    val brokerType: BrokerType

    val accountBalance: Option[Double]

    def placeOrder(order: Order, candle: Candle): Order
    def fillOrder(order: Order, candle: Candle): Order
    def takeProfit(order: Order, candle: Candle): Order
    def takeLoss(order: Order, candle: Candle): Order
    def exitOrder(order: Order, candle: Candle): Order
    def cancelOrder(order: Order, candle: Candle, cancelReason: String): Order
    
    def orderReport(serializableOrders: List[SerializableOrder]): OrderReport = {
        // Filter for completed orders (Profit or Loss)
        val completedOrders = serializableOrders.filter(o => 
            o.status == OrderStatus.Profit || o.status == OrderStatus.Loss
        )
        
        // Count wins and losses
        val winningOrders = completedOrders.filter(_.status == OrderStatus.Profit)
        val losingOrders = completedOrders.filter(_.status == OrderStatus.Loss)
        val winning = winningOrders.length
        val losing = losingOrders.length

        val totalFees = {
            val mesFees = completedOrders.filter(_.contract.startsWith("M")).map(_.contracts).sum * feePerMESContract
            val esFees = completedOrders.filter(_.contract.startsWith("E")).map(_.contracts).sum * feePerESContract
            mesFees + esFees
        }
        
        // Calculate average win/loss dollars
        val averageWinDollars = if (winning > 0) {
            winningOrders.map(_.potential).sum / winning
        } else 0.0
        
        val averageLossDollars = if (losing > 0) {
            losingOrders.map(_.atRisk).sum / losing
        } else 0.0
        
        // Calculate max drawdown: largest drop from peak P&L
        val maxDrawdownDollars = if (completedOrders.nonEmpty) {
            // Sort by close timestamp to get chronological order
            val chronological = completedOrders.sortBy(_.closeTimestamp.getOrElse(0L))
            
            // Calculate running P&L and track max drawdown
            val (_, _, maxDD) = chronological.foldLeft((0.0, 0.0, 0.0)) { 
                case ((runningPnL, peakPnL, currentMaxDD), order) =>
                    val pnl = if (order.status == OrderStatus.Profit) order.potential else -order.atRisk
                    val newRunningPnL = runningPnL + pnl
                    val newPeakPnL = Math.max(peakPnL, newRunningPnL)
                    val drawdown = newPeakPnL - newRunningPnL
                    val newMaxDD = Math.max(currentMaxDD, drawdown)
                    (newRunningPnL, newPeakPnL, newMaxDD)
            }
            maxDD
        } else 0.0
        
        // Calculate total P&L
        val totalPnL = (winning * averageWinDollars) - (losing * averageLossDollars)
        
        // Calculate win rates by order type
        val winRates = completedOrders.groupBy(_.entryType).map { case (entryType, orders) =>
            val wins = orders.count(_.status == OrderStatus.Profit)
            val losses = orders.count(_.status == OrderStatus.Loss)
            WinRateByOrderType(entryType, wins, losses)
        }.toSeq
        
        OrderReport(serializableOrders, winning, losing, averageWinDollars, averageLossDollars, maxDrawdownDollars, totalFees, totalPnL, winRates)
    }
    
    /**
     * Determine profitability status for a list of orders
     * @param orders List of serializable orders
     * @return Some(true) if profitable (P&L > 0), 
     *         Some(false) if unprofitable (P&L < 0),
     *         None if neutral (no trades or P&L = 0)
     */
    def isProfitable(orders: List[SerializableOrder]): Option[Boolean] = {
        if (orders.isEmpty) return None
        
        val report = orderReport(orders)
        
        if (report.totalPnL > 0) Some(true)
        else if (report.totalPnL < 0) Some(false)
        else None
    }
}

class LeadAccountBroker(val brokers: List[AccountBroker], 
                        riskDollars: Double = 550.0, 
                        val feePerESContract: Double = 2.20,
                        val feePerMESContract: Double = 2.20) extends AccountBroker {

    final val TenMinutes = Duration.ofMinutes(10).toMillis()
    final val FiveMinutes = Duration.ofMinutes(5).toMillis()

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

    override lazy val accountBalance: Option[Double] = {
        val broker = brokers
            .find(_.accountId.startsWith("tradovate-live"))
            .orElse(brokers.find(_.accountId.startsWith("tradovate-demo")))
        
        broker.flatMap(_.accountBalance)
    }

    def updateOrderStatus(order: Order, candle: Candle): Order = {
        val operations = Seq(
            takeProfitOrLossFromTallCandle(_,_),
            takeProfitFromShortCandle(_,_),
            takeLossFromShortCandle(_,_),
            checkIfOrderWasFilled(_,_),
            fillNewMarketOrders(_,_),
            exitOrderAtEndOfDay(_,_),
            cancelPlannedOrderCandleOutside(_,_),
            cancelPlacedOrderCandleOutside(_,_), 
            cancelOldPlannedOrderWickOutside(_,_),
            cancelOldPlacedOrderWickOutside(_,_),
            cancelUnfilledOrderAfterTenMinutes(_,_),
        )

        operations.foldLeft(order) { (updatedOrder, operation) =>
            operation(updatedOrder, candle)
        }
    }

    def buildSerializableOrder(order: Order): SerializableOrder = {
        SerializableOrder.fromOrder(order, riskPerTrade)
    }

    private def fillNewMarketOrders(order: Order, candle: Candle): Order = {
        if (order.status == PlaceNow) {
            val placedOrder = placeOrder(order, candle)
            fillOrder(order, candle)
        } else order
    }

    //Rule: Fill the order if candle crosses entry point.
    private def checkIfOrderWasFilled(order: Order, candle: Candle): Order = {
        if (order.status == Placed) {
            order.orderType match {
                case OrderType.Long =>
                    if (candle.low <= order.entryPoint) fillOrder(order, candle)
                    else order
                case OrderType.Short => 
                    if (candle.high >= order.entryPoint) fillOrder(order, candle)
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
                    if (candle.low <= order.stopLoss && candle.high >= order.takeProfit) {
                        if (candle.isBearish) takeProfit(order, candle)
                        else takeLoss(order, candle)
                    } else order
                case OrderType.Short => 
                    if (candle.high >= order.stopLoss && candle.low <= order.takeProfit) {
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
                case OrderType.Long if candle.high >= order.takeProfit => takeProfit(order, candle)
                case OrderType.Short if candle.low <= order.takeProfit => takeProfit(order, candle)
                case _ => order
            }
        } else order
    }

    //Rule: Loss if candle crosses loss line.
    private def takeLossFromShortCandle(order: Order, candle: Candle): Order = {
        if (order.status == Filled) {
            order.orderType match {
                case OrderType.Long if candle.low <= order.stopLoss => takeLoss(order, candle)
                case OrderType.Short if candle.high >= order.stopLoss => takeLoss(order, candle)
                case _ => order
            }
        } else order
    }

    //Rule: During end of day, exit order
    private def exitOrderAtEndOfDay(order: Order, candle: Candle): Order = {
        if (TimestampUtils.isNearTradingClose(candle.timestamp)) order.status match {
            case Planned | Placed => cancelOrder(order, candle, CancelReason.EndOfDay)
            case Filled => exitOrder(order, candle)
            case _ => order
        } else order
    }

    //Rule: If the candle is fully above or below the order, cancel it.
    private def cancelPlannedOrderCandleOutside(order: Order, candle: Candle): Order = {
        if (order.status == Planned || order.status == PlaceNow) {
            val reason = CancelReason.FullCandleOutside
            order.orderType match {
                case OrderType.Long => 
                    if (candle.low >= order.takeProfit) {
                        if (order.entryType == EntryType.ConsolidationFadeOrderBlock) order
                        else cancelOrder(order, candle, reason)
                    } else if (candle.high < order.stopLoss) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    if (candle.high <= order.takeProfit)  {
                        if (order.entryType == EntryType.ConsolidationFadeOrderBlock) order
                        else cancelOrder(order, candle, reason)
                    } else if (candle.low > order.stopLoss) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }

    private def cancelPlacedOrderCandleOutside(order: Order, candle: Candle): Order = {
        if (order.status == Placed && order.entryType != EntryType.ConsolidationFadeOrderBlock) {
            val reason = CancelReason.FullCandleOutside
            order.orderType match {
                case OrderType.Long => 
                    // Only cancel if gaps above take profit (profitable direction)
                    if (candle.low >= order.takeProfit) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    // Only cancel if gaps below take profit (profitable direction)
                    if (candle.high <= order.takeProfit) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }

    //Rule: If the order is planned for 10 minutes and wicks above or below, cancel it.
    private def cancelOldPlannedOrderWickOutside(order: Order, candle: Candle): Order = {
        if ((order.status == Planned || order.status == PlaceNow) && order.timestamp + FiveMinutes <= candle.timestamp) {
            val reason = CancelReason.TenMinuteWickOutside
            order.orderType match {
                case OrderType.Long => 
                    if (candle.high >= order.takeProfit) cancelOrder(order, candle, reason)
                    else if (candle.low < order.stopLoss) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    if (candle.low <= order.takeProfit) cancelOrder(order, candle, reason)
                    else if (candle.high > order.stopLoss) cancelOrder(order, candle, reason)
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
                    if (candle.high >= order.takeProfit) cancelOrder(order, candle, reason)
                    else order

                case OrderType.Short =>
                    // Only cancel if wick is in profitable direction (below take profit)
                    if (candle.low <= order.takeProfit) cancelOrder(order, candle, reason)
                    else order
            }
        } else order
    }

    private def cancelUnfilledOrderAfterTenMinutes(order: Order, candle: Candle): Order = {
        if ((order.status == Placed || order.status == Planned) && 
                order.timestamp + TenMinutes <= candle.timestamp) {
            val reason = CancelReason.TenMinutesUnfilled
            cancelOrder(order, candle, reason)
        } else order
    }
}
