package bmps.core.services.rules

import bmps.core.models.SystemState

trait CalcAtrs {
    def calcAtrs(state: SystemState, quantity: Double): Double = {
        state.recentVolatilityAnalysis.last.trueRange.atr * quantity
    }
}
