package bmps.core.services.rules

import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.SerializableOrder
import bmps.core.models.OrderStatus.Loss
import bmps.core.models.OrderStatus.Profit
import bmps.core.models.OrderStatus

trait RiskSizingRules {
    def computeRiskMultiplier(state: SystemState, accountBalance: Double): Float = {
        val orders = state.orders.filter(_.isProfitOrLoss)
        val approximateAccountValue = orders.foldLeft(accountBalance) { (r, c) =>
            val risk = riskPerTrade(r)
            r + valueOfOrder(c, risk)
        }
        val finalRisk = riskPerTrade(approximateAccountValue)
        val valueBasedMultiplier = finalRisk.toFloat / 1000.0f //40k burn //223k gain
        val lastWinIndex = orders.reverse.indexWhere(_.status == OrderStatus.Profit)
        val finalIndex = if (lastWinIndex == -1) orders.size else lastWinIndex
        finalIndex match {  
            case -1 => valueBasedMultiplier
            case 0 => valueBasedMultiplier
            case 1 => valueBasedMultiplier * 2.0f
            case 2 => valueBasedMultiplier * 4.0f
            case n => (valueBasedMultiplier * math.pow(0.5, n - 2)).toFloat
        }
    }

    /** Modified version of original martingale with hard drawdown caps.
      * Target: Limit drawdown to $20k while maintaining high profit.
      */
    def computeRiskMultiplierKelly(state: SystemState, accountBalance: Double): Float = {
        val orders = state.recentOrders ++ state.orders.filter(_.isProfitOrLoss)
        
        // Calculate approximate current account value
        val approximateAccountValue = orders.foldLeft(accountBalance) { (r, c) =>
            val risk = riskPerTrade(r)
            r + valueOfOrder(c, risk)
        }
        
        // Base risk for current account value
        val finalRisk = riskPerTrade(approximateAccountValue)
        val valueBasedMultiplier = finalRisk.toFloat / 1000.0f
        
        // If we have no completed trades yet, use base risk
        if (orders.isEmpty) return valueBasedMultiplier
        
        // Calculate drawdown from peak
        val peakValue = orders.foldLeft((accountBalance, accountBalance)) { case ((peak, running), order) =>
            val risk = riskPerTrade(running)
            val newRunning = running + valueOfOrder(order, risk)
            (math.max(peak, newRunning), newRunning)
        }._1
        
        val currentDrawdownDollars = peakValue - approximateAccountValue
        
        // HARD STOP: If drawdown exceeds $18k, cut risk dramatically until it recovers below $15k
        if (currentDrawdownDollars > 18000) {
            return valueBasedMultiplier * 0.25f  // 25% risk only
        } else if (currentDrawdownDollars > 15000) {
            return valueBasedMultiplier * 0.5f   // 50% risk
        }
        
        // Use original martingale logic (works well for profit)
        val lastWinIndex = orders.reverse.indexWhere(_.status == OrderStatus.Profit)
        val finalIndex = if (lastWinIndex == -1) orders.size else lastWinIndex
        
        val martingaleMultiplier = finalIndex match {  
            case -1 => 1.0f
            case 0 => 1.0f
            case 1 => 2.0f
            case 2 => 3.0f   // Slightly less aggressive than 4x
            case n => (math.pow(0.5, n - 2)).toFloat
        }
        
        // Additional safety: if drawdown is between $10k-15k, cap the martingale multiplier
        val cappedMultiplier = if (currentDrawdownDollars > 10000) {
            math.min(martingaleMultiplier, 2.0f)  // Cap at 2x during moderate drawdown
        } else {
            martingaleMultiplier
        }
        
        valueBasedMultiplier * cappedMultiplier
    }

    private def riskPerTrade(runningTotal: Double): Double = {
        // if (runningTotal < 30000.0) 300.0
        if (runningTotal < 50000.0) 500.0
        else if (runningTotal < 100000.0) 1000.0
        else math.floor(runningTotal / 100000.0) * 1000.0
    }

    private def valueOfOrder(order: Order, riskPerTrade: Double): Double = {
        require(order.isProfitOrLoss, "This order is not closed.")
        val serializedOrder = SerializableOrder.fromOrder(order, riskPerTrade)
        order.status match {
            case Loss => serializedOrder.atRisk * -1
            case Profit => serializedOrder.potential
            case _ => throw new IllegalStateException(s"Unexpected order statue: ${order.status}")
        }
    }
}

