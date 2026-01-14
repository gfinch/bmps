package bmps.core.services.rules

import bmps.core.models.SystemState

trait CrossingRules {
    def isDeathCrossMinutesAgo(state: SystemState, n: Int): Boolean = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 2)
        trendAnalysis.tail.forall(_.isDeathCross == true) && !trendAnalysis.head.isDeathCross
    }

    def isGoldenCrossMinutesAgo(state: SystemState, n: Int): Boolean = {
        val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 2)
        trendAnalysis.tail.forall(_.isGoldenCross == true) && !trendAnalysis.head.isGoldenCross
    }
}
