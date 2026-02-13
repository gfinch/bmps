// package bmps.core.services

// import bmps.core.models.{SystemState, Order, OrderType, OrderStatus, Candle}
// import bmps.core.models.EntryType
// import java.time.Duration
// import bmps.core.services.rules.RiskSizingRules
// import bmps.core.utils.TimestampUtils

// class ConsolidationFadeService(accountBalance: Double, baseRisk: Double, readOnly: Boolean) extends RiskSizingRules {
    
//     // Configurable parameters
//     private val consolidationMinutes = 10 // How long price must be consolidating
//     private val rangeWindowMinutes = 30   // Window to find support/resistance
//     private val adxThreshold = 20.0       // ADX must be below this
//     private val entryOffsetPoints = 1.0f  // Entry distance from midpoint
//     private val stopBufferPoints = 1.0f  // Buffer beyond support/resistance
//     private val defaultContracts = 5      // Fixed contract size
//     private val riskRewardRatio = 4.0f    // 1:3 R:R (risk 3 to make 1)
    
//     def processState(state: SystemState): SystemState = {
//         require(state.tradingCandles.nonEmpty, "There must be at least one candle in state.")
//         require(state.contractSymbol.isDefined, "The contract symbol must be defined before creating orders.")
        
//         val hasActiveOrder = state.orders.exists(_.isActive)
//         val isEndOfDay = TimestampUtils.isNearTradingClose(state.tradingCandles.last.timestamp)
        
//         if (hasActiveOrder || isEndOfDay) {
//             return state
//         }
        
//         // Detect if we're in consolidation
//         if (!isInConsolidation(state)) {
//             return state
//         }
        
//         // Find support and resistance
//         val (support, resistance) = findSupportResistance(state)
//         val midpoint = (support + resistance) / 2.0f
//         val range = resistance - support
        
//         // Need at least 2 points of range to be meaningful
//         if (range < 2.0f) {
//             return state
//         }
        
//         val contract = state.contractSymbol.get
//         val lastCandle = state.tradingCandles.last

//         val riskMultiplier = riskBasedOnAccountValueOnly(state, accountBalance, baseRisk, readOnly)
        
//         // Create order based on current price position
//         // If above midpoint, plan LONG order (price needs to fall to trigger)
//         // If below midpoint, plan SHORT order (price needs to rise to trigger)
//         val order = if (lastCandle.close > midpoint) {
//             // Create LONG order: enter below midpoint, stop below support
//             val longEntry = midpoint - entryOffsetPoints
//             val longStop = support - stopBufferPoints
//             val longRisk = longEntry - longStop
//             val longTP = longEntry + (longRisk / riskRewardRatio) // 1 point target if risk is 3 points
            
//             Order(
//                 low = longStop,
//                 high = longEntry,
//                 timestamp = lastCandle.timestamp,
//                 orderType = OrderType.Long,
//                 entryType = EntryType.ConsolidationFadeOrderBlock,
//                 contract = contract,
//                 profitCap = Some(longTP),
//                 status = OrderStatus.Planned,
//                 riskMultiplier = Some(riskMultiplier)
//             )
//         } else {
//             // Create SHORT order: enter above midpoint, stop above resistance
//             val shortEntry = midpoint + entryOffsetPoints
//             val shortStop = resistance + stopBufferPoints
//             val shortRisk = shortStop - shortEntry
//             val shortTP = shortEntry - (shortRisk / riskRewardRatio) // 1 point target if risk is 3 points
            
//             Order(
//                 low = shortEntry,
//                 high = shortStop,
//                 timestamp = lastCandle.timestamp,
//                 orderType = OrderType.Short,
//                 entryType = EntryType.ConsolidationFadeOrderBlock,
//                 contract = contract,
//                 profitCap = Some(shortTP),
//                 status = OrderStatus.Planned,
//                 riskMultiplier = Some(riskMultiplier)
//             )
//         }
        
//         // Add the order
//         state.copy(orders = state.orders :+ order)
//     }
    
//     private def isInConsolidation(state: SystemState): Boolean = {
//         if (state.recentTrendAnalysis.isEmpty || state.recentVolatilityAnalysis.isEmpty) {
//             return false
//         }
        
//         val trend = state.recentTrendAnalysis.last
//         val volatility = state.recentVolatilityAnalysis.last
//         val currentPrice = state.tradingCandles.last.close
        
//         // Check ADX is weak (no strong trend)
//         val weakTrend = trend.adx < adxThreshold
        
//         // Check price is within Keltner channels (ranging)
//         val withinChannels = volatility.keltnerChannels.isInsideChannel(currentPrice)
        
//         // NEW: Check recent candles aren't growing (stable consolidation)
//         val stableConsolidation = {
//             val recentCandles = state.tradingCandles.takeRight(10)
//             if (recentCandles.length < 10) {
//                 true
//             } else {
//                 val firstFive = recentCandles.take(5)
//                 val lastFive = recentCandles.takeRight(5)
                
//                 val firstAvgRange = firstFive.map(c => c.high - c.low).sum / 5
//                 val lastAvgRange = lastFive.map(c => c.high - c.low).sum / 5
                
//                 // Last 5 candles shouldn't be 50% bigger than first 5
//                 lastAvgRange < (firstAvgRange * 1.5)
//             }
//         }
        
//         // Check we've been consolidating for minimum time
//         val hasConsolidatedLongEnough = {
//             val consolidationMs = Duration.ofMinutes(consolidationMinutes).toMillis()
//             val startTime = state.tradingCandles.last.timestamp - consolidationMs
            
//             // Check if all candles in window stayed within a tight range
//             val recentCandles = state.tradingCandles.filter(_.timestamp >= startTime)
//             if (recentCandles.length < consolidationMinutes) {
//                 false
//             } else {
//                 val high = recentCandles.map(_.high).max
//                 val low = recentCandles.map(_.low).min
//                 val rangePercent = ((high - low) / low) * 100
//                 rangePercent < 1.0 // Less than 1.0% range over period
//             }
//         }
        
//         weakTrend && withinChannels && stableConsolidation && hasConsolidatedLongEnough
//     }
    
//     private def findSupportResistance(state: SystemState): (Float, Float) = {
//         val windowMs = Duration.ofMinutes(rangeWindowMinutes).toMillis()
//         val startTime = state.tradingCandles.last.timestamp - windowMs
//         val recentCandles = state.tradingCandles.filter(_.timestamp >= startTime)
        
//         if (recentCandles.isEmpty) {
//             // Fallback to last candle
//             val last = state.tradingCandles.last
//             (last.low, last.high)
//         } else {
//             val support = recentCandles.map(_.low).min
//             val resistance = recentCandles.map(_.high).max
//             (support, resistance)
//         }
//     }
// }
