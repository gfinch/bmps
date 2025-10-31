package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.SwingPoint
import bmps.core.models.Direction
import bmps.core.models.Direction._
import bmps.core.models.Candle

class SwingService(minConfirmations: Int = 1, minSwingSize: Float = 0.0f) {

    def computeSwings(candles: List[Candle]): (List[SwingPoint], Option[Direction]) = {
        if (candles.length < 2 * minConfirmations + 1) (List.empty, None)
        else {
            val rawSwings = findRawSwingPoints(candles)
            val filteredSwings = filterAndAlternateSwings(rawSwings)
            val newDirection = if (filteredSwings.nonEmpty) Some(filteredSwings.last.direction) else None
            (filteredSwings, newDirection)
        }
    }

    private def findRawSwingPoints(candles: List[Candle]): List[SwingPoint] = {
        val swingPointsBuilder = List.newBuilder[SwingPoint]
        
        for (i <- minConfirmations until candles.length - minConfirmations) {
            val curr = candles(i)
            val windowHighs = (i - minConfirmations to i + minConfirmations).filter(_ != i).map(j => candles(j).high)
            val windowLows = (i - minConfirmations to i + minConfirmations).filter(_ != i).map(j => candles(j).low)
            val maxWindowHigh = windowHighs.max
            val minWindowLow = windowLows.min
            
            // Use >= and <= to catch equal highs/lows as potential swing points
            if (curr.high >= maxWindowHigh) {
                swingPointsBuilder += SwingPoint(curr.high, Direction.Down, curr.timestamp)
            } else if (curr.low <= minWindowLow) {
                swingPointsBuilder += SwingPoint(curr.low, Direction.Up, curr.timestamp)
            }
        }
        
        swingPointsBuilder.result()
    }

    private def filterAndAlternateSwings(rawSwings: List[SwingPoint]): List[SwingPoint] = {
        if (rawSwings.isEmpty) return List.empty
        
        val filteredSwings = List.newBuilder[SwingPoint]
        var lastSwing: Option[SwingPoint] = None
        
        for (swing <- rawSwings) {
            lastSwing match {
                case None => 
                    // First swing point
                    filteredSwings += swing
                    lastSwing = Some(swing)
                    
                case Some(last) if swing.direction == last.direction =>
                    // Same direction - keep the more extreme point
                    val shouldReplace = swing.direction match {
                        case Direction.Up => swing.level < last.level // Lower low for upswing
                        case Direction.Down => swing.level > last.level // Higher high for downswing  
                        case _ => false
                    }
                    
                    if (shouldReplace && meetsMinimumSize(swing, getSecondLastSwing(filteredSwings.result()))) {
                        // Remove the last swing and add this more extreme one
                        val temp = filteredSwings.result()
                        filteredSwings.clear()
                        filteredSwings ++= temp.dropRight(1)
                        filteredSwings += swing
                        lastSwing = Some(swing)
                    }
                    
                case Some(last) =>
                    // Different direction - add if it meets minimum swing size
                    if (swing.direction != last.direction && meetsMinimumSize(swing, Some(last))) {
                        filteredSwings += swing
                        lastSwing = Some(swing)
                    }
            }
        }
        
        filteredSwings.result()
    }
    
    private def getSecondLastSwing(swings: List[SwingPoint]): Option[SwingPoint] = {
        if (swings.length >= 2) Some(swings(swings.length - 2))
        else None
    }
    
    private def meetsMinimumSize(newSwing: SwingPoint, lastSwing: Option[SwingPoint]): Boolean = {
        if (minSwingSize <= 0.0f) return true
        
        lastSwing match {
            case None => true
            case Some(last) => math.abs(newSwing.level - last.level) >= minSwingSize
        }
    }
}
