package bmps.core.strategies.rules

import bmps.core.utils.TimestampUtils
import bmps.core.models.Candle
import bmps.core.models.SystemState
import bmps.core.models.Order
import bmps.core.models.OrderStatus._

trait TimeEntryRules {
    def notLateInTheDay(state: SystemState, candle: Candle): Boolean = {
        !TimestampUtils.isNearTradingClose(candle.endTime)    
    }
}

trait TimeExitRules {
    def isLateInTheDay(candle: Candle, order: Order): ExitAction = {
        if (TimestampUtils.isNearTradingClose(candle.endTime)) order.status match {
            case Filled => ExitAction.ExitNow
            case Placed => ExitAction.Cancel
            case _ => ExitAction.NoAction
        } else ExitAction.NoAction
    }
}
