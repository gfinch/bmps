package bmps.core.strategies.exit

import bmps.core.models.ExitStrategy
import bmps.core.brokers.AccountBroker
import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.OrderType
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.Prices

/**
 * IceInVeinsExitStrategy manages stops for riding trends with confidence.
 * 
 * Stop adjustment rules:
 * 1. If profitable by (2 * ATR) + fees: reset stop to breakeven (entry + fees for long, entry - fees for short)
 * 2. If profitable by $1000 + (2 * ATR): reset stop to the $1000 profit line
 * 
 * Early exit rules (only in first 8 minutes and when unprofitable):
 * - Long: exit if RSI dips below 45 and position is unprofitable
 * - Short: exit if iRSI dips below 45 (RSI > 55) and position is unprofitable
 * 
 * Otherwise relies on stop loss, take profit, or end-of-day lifecycle.
 */
class IceInVeinsExitStrategy extends ExitStrategy {
    
    override def adjustOrder(leadAccountBroker: AccountBroker, state: SystemState, order: Order): Seq[Order] = {
        // Only adjust filled orders
        if (order.status != OrderStatus.Filled) return Seq(order)
        if (state.recentVolatilityAnalysis.isEmpty) return Seq(order)
        if (state.tradingCandles.isEmpty) return Seq(order)
        if (state.recentMomentumAnalysis.isEmpty) return Seq(order)

        val lastCandle = state.tradingCandles.last
        val volatility = state.recentVolatilityAnalysis.last
        val momentum = state.recentMomentumAnalysis.last
        val atr = volatility.trueRange.atr
        val currentPrice = lastCandle.close
        
        // Only enforce RSI check in the first 8 minutes of the order
        val eightMinutesMs = 8 * 60 * 1000L
        val withinEarlyWindow = order.filledTimestamp.exists { fillTime =>
            lastCandle.endTime - fillTime <= eightMinutesMs
        }
        
        // Check if order is currently unprofitable (considering fees)
        val isUnprofitable = order.openProfit(lastCandle).exists(_ <= 0)
        
        // Check for RSI-based early exit (only if unprofitable and within first 8 minutes)
        val rsiSignal = order.orderType match {
            case OrderType.Long => momentum.rsi < 45.0
            case OrderType.Short => momentum.iRsi < 45.0
        }
        
        if (withinEarlyWindow && isUnprofitable && rsiSignal) {
            val exitPrice = lastCandle.close
            val exitedOrder = leadAccountBroker.exitOrder(order, lastCandle, exitPrice)
            return Seq(exitedOrder)
        }
        
        // Calculate price per point based on contract type
        val pricePerPoint = order.contractType match {
            case ContractType.ES => Prices.MiniDollarsPerPoint
            case ContractType.MES => Prices.MicroDollarsPerPoint
        }
        
        // Convert fees to price movement (points)
        val feesInPoints = order.fees / pricePerPoint / order.contracts
        
        // Calculate current profit in price movement (points)
        val profitInPoints = order.orderType match {
            case OrderType.Long => currentPrice - order.entryPrice
            case OrderType.Short => order.entryPrice - currentPrice
        }
        
        // Thresholds in points
        val breakEvenThreshold = (2 * atr) + feesInPoints
        val thousandDollarsInPoints = 1000.0 / pricePerPoint / order.contracts
        val lockProfitThreshold = thousandDollarsInPoints + (2 * atr)
        
        // Determine the new stop loss
        val newStop: Option[Double] = {
            if (profitInPoints >= lockProfitThreshold) {
                // Lock in $1000 profit
                val stopAtThousand = order.orderType match {
                    case OrderType.Long => order.entryPrice + thousandDollarsInPoints + feesInPoints
                    case OrderType.Short => order.entryPrice - thousandDollarsInPoints - feesInPoints
                }
                // Only move stop if it's better than current
                val isBetterStop = order.orderType match {
                    case OrderType.Long => stopAtThousand > order.stopLoss
                    case OrderType.Short => stopAtThousand < order.stopLoss
                }
                if (isBetterStop) Some(stopAtThousand) else None
            } else if (profitInPoints >= breakEvenThreshold) {
                // Move stop to breakeven (entry + fees)
                val breakEvenStop = order.orderType match {
                    case OrderType.Long => order.entryPrice + feesInPoints
                    case OrderType.Short => order.entryPrice - feesInPoints
                }
                // Only move stop if it's better than current
                val isBetterStop = order.orderType match {
                    case OrderType.Long => breakEvenStop > order.stopLoss
                    case OrderType.Short => breakEvenStop < order.stopLoss
                }
                if (isBetterStop) Some(breakEvenStop) else None
            } else {
                None
            }
        }
        
        newStop match {
            case Some(stop) =>
                val updatedOrder = leadAccountBroker.resetStop(order, stop, lastCandle)
                Seq(updatedOrder)
            case None =>
                Seq(order)
        }
    }
}
