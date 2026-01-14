package bmps.core.services.rules

import bmps.core.models.SystemState
import bmps.core.utils.TimestampUtils

trait OrderPlacingRules {
    def noActiveOrderExists(systemState: SystemState): Boolean = {
        !systemState.orders.exists(_.isActive)
    }

    def notEndOfDay(state: SystemState): Boolean = {
        !TimestampUtils.isNearTradingClose(state.tradingCandles.last.timestamp)
    }
}
