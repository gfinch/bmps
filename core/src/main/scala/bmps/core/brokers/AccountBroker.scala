package bmps.core.brokers

import bmps.core.models.OrderStatus
import bmps.core.models.OrderType
import bmps.core.models.Candle
import bmps.core.models.OrderStatus._
import java.time.Duration
import bmps.core.utils.TimestampUtils
import bmps.core.models.CandleDuration
import java.security.KeyStore.Entry
import bmps.core.models.Order
import bmps.core.models.EntryStrategy

sealed trait BrokerType
object BrokerType {
    case object LeadAccountBroker extends BrokerType
    case object SimulatedAccountBroker extends BrokerType
    case object TradovateAccountBroker extends BrokerType
}

case class WinRateByOrderType(orderType: EntryStrategy, winning: Int, losing: Int)

case class OrderReport(orders: List[Order], winning: Int, losing: Int,
                       averageWinDollars: Double, averageLossDollars: Double,
                       maxDrawdownDollars: Double, totalFees: Double, totalPnL: Double, winRates: Seq[WinRateByOrderType])

object OrderReport {
    def orderReport(orders: List[Order]): OrderReport = {
        val completedOrders = orders.filter(_.isProfitOrLoss)
        val winningOrders = completedOrders.filter(_.status == OrderStatus.Profit)
        val losingOrders = completedOrders.filter(_.status == OrderStatus.Loss)
        val winning = winningOrders.length
        val losing = losingOrders.length

        val totalFees = completedOrders.map(_.fees).sum
        
        val averageWinDollars = winningOrders.map(_.closedProfit.getOrElse(0.0)).sum / winning
        val averageLossDollars = (losingOrders.map(_.closedProfit.getOrElse(0.0)).sum / losing) * -1.0
        
        // Calculate max drawdown: largest drop from peak P&L
        val maxDrawdownDollars = if (completedOrders.nonEmpty) {
            // Sort by close timestamp to get chronological order
            val chronological = completedOrders.sortBy(_.closeTimestamp.getOrElse(0L))
            
            // Calculate running P&L and track max drawdown
            val (_, _, maxDD) = chronological.foldLeft((0.0, 0.0, 0.0)) { 
                case ((runningPnL, peakPnL, currentMaxDD), order) =>
                    val pnl = order.closedProfit.getOrElse(0.0)
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
        val winRates = completedOrders.groupBy(_.entryStrategy).map { case (strategy, orders) =>
            val wins = orders.count(_.status == OrderStatus.Profit)
            val losses = orders.count(_.status == OrderStatus.Loss)
            WinRateByOrderType(strategy, wins, losses)
        }.toSeq
        
        OrderReport(orders, winning, losing, averageWinDollars, averageLossDollars, maxDrawdownDollars, totalFees, totalPnL, winRates)
    }

    def isProfitable(orders: List[Order]): Option[Boolean] = {
        if (orders.isEmpty) return None
        
        val report = orderReport(orders)
        
        if (report.totalPnL > 0) Some(true)
        else if (report.totalPnL < 0) Some(false)
        else None
    }
}

trait AccountBroker {
    val accountId: String
    val brokerType: BrokerType

    val accountBalance: Option[Double]

    def placeOrder(order: Order, candle: Candle): Order
    def fillOrder(order: Order, candle: Candle): Order
    def resetStop(order: Order, stop: Double, candle: Candle): Order
    def exitOrder(order: Order, candle: Candle, exitPrice: Double): Order
    def cancelOrder(order: Order, candle: Candle): Order
    def reconcileOrder(order: Order): Order
}

class LeadAccountBroker(val brokers: List[AccountBroker]) extends AccountBroker {

    val accountId = "LeadAccountBroker"
    val brokerType = BrokerType.LeadAccountBroker

    def placeOrder(order: Order, candle: Candle): Order = brokers.map(_.placeOrder(order, candle)).head
    def fillOrder(order: Order, candle: Candle): Order = brokers.map(_.fillOrder(order, candle)).head
    def resetStop(order: Order, stop: Double, candle: Candle): Order = brokers.map(_.resetStop(order, stop, candle)).head
    def exitOrder(order: Order, candle: Candle, exitPrice: Double): Order = brokers.map(_.exitOrder(order, candle, exitPrice)).head
    def cancelOrder(order: Order, candle: Candle): Order = brokers.map(_.cancelOrder(order, candle)).head

    def reconcileOrder(order: Order): Order = brokers.map(_.reconcileOrder(order)).head

    lazy val brokerCount = brokers.size

    override lazy val accountBalance: Option[Double] = {
        val broker = brokers
            .find(_.accountId.startsWith("tradovate-live"))
            .orElse(brokers.find(_.accountId.startsWith("tradovate-demo")))
        
        broker.flatMap(_.accountBalance)
    }
}
