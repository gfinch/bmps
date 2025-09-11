package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.SwingPoint
import bmps.core.models.Direction

class SwingService(minConfirmations: Int = 1) {

    def computeSwings(state: SystemState): SystemState = {
        val candles = state.candles
        if (candles.length < 2 * minConfirmations + 1) return state

        val swingPointsBuilder = List.newBuilder[SwingPoint]
        for (i <- minConfirmations until candles.length - minConfirmations) {
            val curr = candles(i)
            val windowHighs = (i - minConfirmations to i + minConfirmations).filter(_ != i).map(j => candles(j).high.value)
            val windowLows = (i - minConfirmations to i + minConfirmations).filter(_ != i).map(j => candles(j).low.value)
            val maxWindowHigh = windowHighs.max
            val minWindowLow = windowLows.min
            if (curr.high.value > maxWindowHigh) {
                swingPointsBuilder += SwingPoint(curr.high, Direction.Down, curr.timestamp)
            } else if (curr.low.value < minWindowLow) {
                swingPointsBuilder += SwingPoint(curr.low, Direction.Up, curr.timestamp)
            }
        }
        val swingPoints = swingPointsBuilder.result()
        val newDirection = if (swingPoints.nonEmpty) swingPoints.last.direction else state.direction
        state.copy(swingPoints = swingPoints, direction = newDirection)
    }
  
}
