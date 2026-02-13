// package bmps.core.services

// import bmps.core.services.rules.RiskSizingRules
// import bmps.core.models.SystemState
// import bmps.core.services.analysis.{TrendAnalysis, VolatilityAnalysis}
// import bmps.core.models.OrderType
// import bmps.core.models.Order
// import bmps.core.models.OrderStatus
// import bmps.core.models.EntryType


// class TrendRiderEntryBlock(accountBalance: Double, baseRisk: Double, readOnly: Boolean) extends RiskSizingRules {

//     private val trendStrengthGap = 30.0
//     private val cooldownCandles = 10  // Minimum candles between opposite crosses
//     private val rsiOversoldThreshold = 35.0  // Must have been below this for bullish signal
//     private val rsiOverboughtThreshold = 65.0  // Must have been above this for bearish signal

//     def processState(state: SystemState): SystemState = {
//         // RSI-based entries (new approach)
//         if (isStrongRSICrossBullish(state)) {
//             buildOrder(state, OrderType.Long).map { newOrder => 
//                 state.copy(orders = state.orders :+ newOrder)
//             }.getOrElse(state)
//         } else if (isStrongRSICrossBearish(state)) {
//             buildOrder(state, OrderType.Short).map { newOrder => 
//                 state.copy(orders = state.orders :+ newOrder)
//             }.getOrElse(state)
//         } else {
//             state
//         }
        
//         // MA cross-based entries (disabled for now)
//         // if (isSignificantDeathCross(state) && !hasRecentGoldenCross(state)) {
//         //     buildOrder(state, OrderType.Short).map { newOrder => 
//         //         state.copy(orders = state.orders :+ newOrder)
//         //     }.getOrElse(state)
//         // } else if (isSignificantGoldenCross(state) && !hasRecentDeathCross(state)) {
//         //     buildOrder(state, OrderType.Long).map { newOrder => 
//         //         state.copy(orders = state.orders :+ newOrder)
//         //     }.getOrElse(state)
//         // } else {
//         //     state
//         // }
//     }

//     /**
//      * Check if RSI just crossed from below 50 to above 50,
//      * AND was below 30 at some point during the period below 50.
//      */
//     private def isStrongRSICrossBullish(state: SystemState): Boolean = {
//         if (state.recentMomentumAnalysis.size < 2) return false
        
//         val current = state.recentMomentumAnalysis.last.rsi
//         val previous = state.recentMomentumAnalysis.init.last.rsi
        
//         // Check if RSI just crossed above 50
//         val justCrossedAbove50 = current >= 50 && previous < 50
        
//         if (!justCrossedAbove50) return false
        
//         // Look back through the period when RSI was below 50
//         val belowFiftyPeriod = state.recentMomentumAnalysis.init.reverse.takeWhile(_.rsi < 50)
        
//         // Check if RSI was ever below 30 during that period
//         belowFiftyPeriod.exists(_.rsi < rsiOversoldThreshold)
//     }

//     /**
//      * Check if RSI just crossed from above 50 to below 50,
//      * AND was above 70 at some point during the period above 50.
//      */
//     private def isStrongRSICrossBearish(state: SystemState): Boolean = {
//         if (state.recentMomentumAnalysis.size < 2) return false
        
//         val current = state.recentMomentumAnalysis.last.rsi
//         val previous = state.recentMomentumAnalysis.init.last.rsi
        
//         // Check if RSI just crossed below 50
//         val justCrossedBelow50 = current <= 50 && previous > 50
        
//         if (!justCrossedBelow50) return false
        
//         // Look back through the period when RSI was above 50
//         val aboveFiftyPeriod = state.recentMomentumAnalysis.init.reverse.takeWhile(_.rsi > 50)
        
//         // Check if RSI was ever above 70 during that period
//         aboveFiftyPeriod.exists(_.rsi > rsiOverboughtThreshold)
//     }

//     def buildOrder(state: SystemState, orderType: OrderType): Option[Order] = {
//         val lastCandle = state.tradingCandles.last
//         orderType match {
//             case OrderType.Short => 
//                 peakOfRecentGoldenRun(state).map { peak =>
//                     val entry = lastCandle.close
//                     val stopLoss = entry + 3.0f
//                     Order(
//                         low = entry,
//                         high = stopLoss,
//                         timestamp = lastCandle.timestamp,
//                         orderType = orderType,
//                         entryType = EntryType.Trendy("TrendRider"),
//                         contract = state.contractSymbol.get,
//                         status = OrderStatus.PlaceNow,
//                         trailingStop = Some(3.0f)
//                     )
//                 }
//             case OrderType.Long => 
//                 bottomOfRecentDeathRun(state).map { bottom => 
//                     val entry = lastCandle.close
//                     val stopLoss = entry - 3.0f
//                     Order(
//                         low = stopLoss,
//                         high = entry,
//                         timestamp = lastCandle.timestamp,
//                         orderType = orderType,
//                         entryType = EntryType.Trendy("TrendRider"),
//                         contract = state.contractSymbol.get,
//                         status = OrderStatus.PlaceNow,
//                         trailingStop = Some(3.0f)
//                     )
//                 }
//         }
//     }

//     /**
//      * Calculate trend strength using MA spread relative to Keltner channel width.
//      * This matches the web app's calculation in ADXRenderer.jsx.
//      * 
//      * Formula: trendStrength = |shortTermMA - longTermMA| / |keltnerUpper - keltnerLower|
//      * Result is clamped to 0-100 scale.
//      * 
//      * @param trend The trend analysis containing short and long term moving averages
//      * @param volatility The volatility analysis containing Keltner channel data
//      * @return Trend strength as a percentage (0-100)
//      */
//     private def calculateTrendStrength(trend: TrendAnalysis, 
//                                volatility: VolatilityAnalysis): Double = {
//         val maSpread = math.abs(trend.shortTermMA - trend.longTermMA)
//         val channelWidth = math.abs(volatility.keltnerChannels.upperBand - volatility.keltnerChannels.lowerBand)
        
//         if (channelWidth == 0) return 0.0
        
//         val rawStrength = maSpread / channelWidth
//         val clampedStrength = math.max(0.0, math.min(1.0, rawStrength))
//         clampedStrength * 100.0 // Scale to 0-100
//     }

//     /**
//      * Get the trend strength from N minutes ago.
//      * 
//      * @param state The current system state
//      * @param n Number of minutes ago to check
//      * @return Trend strength as a percentage (0-100), or 0.0 if insufficient data
//      */
//     private def trendStrengthNMinutesAgo(state: SystemState, n: Int): Double = {
//         if (state.recentTrendAnalysis.size < n + 1 || state.recentVolatilityAnalysis.size < n + 1) {
//             return 0.0
//         }
//         val trendAnalysis = state.recentTrendAnalysis.takeRight(n + 1).head
//         val volatilityAnalysis = state.recentVolatilityAnalysis.takeRight(n + 1).head
//         calculateTrendStrength(trendAnalysis, volatilityAnalysis)
//     }

//     /**
//      * Check if we just crossed from golden to death cross, and if the prior
//      * golden cross period had a trend strength of 10 or greater.
//      */
//     private def isSignificantDeathCross(state: SystemState): Boolean = {
//         if (state.recentTrendAnalysis.size < 2 || state.recentVolatilityAnalysis.size < 2) return false
        
//         val current = state.recentTrendAnalysis.last
//         val previous = state.recentTrendAnalysis.init.last
        
//         // Check if we just crossed from golden to death
//         val justCrossedToDeath = current.isDeathCross && previous.isGoldenCross
        
//         if (!justCrossedToDeath) return false
        
//         // Look back through the golden cross period and check trend strength
//         val trendVolatilityPairs = state.recentTrendAnalysis.init.zip(state.recentVolatilityAnalysis.init)
//         val goldiesWithVolatility = trendVolatilityPairs.reverse.takeWhile(_._1.isGoldenCross)
        
//         // Check if any golden cross period had trend strength >= 10
//         goldiesWithVolatility.exists { case (trend, volatility) =>
//             calculateTrendStrength(trend, volatility) >= trendStrengthGap
//         }
//     }

//     /**
//      * Check if we just crossed from death to golden cross, and if the prior
//      * death cross period had a trend strength of 10 or greater.
//      */
//     private def isSignificantGoldenCross(state: SystemState): Boolean = {
//         if (state.recentTrendAnalysis.size < 2 || state.recentVolatilityAnalysis.size < 2) return false
        
//         val current = state.recentTrendAnalysis.last
//         val previous = state.recentTrendAnalysis.init.last
        
//         // Check if we just crossed from death to golden
//         val justCrossedToGolden = current.isGoldenCross && previous.isDeathCross
        
//         if (!justCrossedToGolden) return false
        
//         // Look back through the death cross period and check trend strength
//         val trendVolatilityPairs = state.recentTrendAnalysis.init.zip(state.recentVolatilityAnalysis.init)
//         val deathsWithVolatility = trendVolatilityPairs.reverse.takeWhile(_._1.isDeathCross)
        
//         // Check if any death cross period had trend strength >= 10
//         deathsWithVolatility.exists { case (trend, volatility) =>
//             calculateTrendStrength(trend, volatility) >= trendStrengthGap
//         }
//     }

//     /**
//      * Find the peak (highest high) during the previous golden cross run.
//      * Called after identifying a death cross, so the last trend is death cross.
//      * Uses init to skip the current death cross and look at the prior golden period.
//      * 
//      * @param state The current system state
//      * @return Option containing the peak price, or None if no golden cross period found
//      */
//     private def peakOfRecentGoldenRun(state: SystemState): Option[Float] = {
//         if (state.recentTrendAnalysis.size < 2 || state.tradingCandles.size < 2) return None
        
//         // Skip the current death cross(es) and count consecutive golden crosses before that
//         val goldenCount = state.recentTrendAnalysis.init.reverse.takeWhile(_.isGoldenCross).size
        
//         if (goldenCount == 0) return None
        
//         // Get the corresponding candles for that period (also using init to skip current candle)
//         val candleCount = math.min(goldenCount, state.tradingCandles.size - 1)
//         val goldenCandles = state.tradingCandles.init.takeRight(candleCount)
        
//         if (goldenCandles.isEmpty) None else Some(goldenCandles.map(_.high).max)
//     }

//     /**
//      * Find the bottom (lowest low) during the previous death cross run.
//      * Called after identifying a golden cross, so the last trend is golden cross.
//      * Uses init to skip the current golden cross and look at the prior death period.
//      * 
//      * @param state The current system state
//      * @return Option containing the bottom price, or None if no death cross period found
//      */
//     private def bottomOfRecentDeathRun(state: SystemState): Option[Float] = {
//         if (state.recentTrendAnalysis.size < 2 || state.tradingCandles.size < 2) return None
        
//         // Skip the current golden cross(es) and count consecutive death crosses before that
//         val deathCount = state.recentTrendAnalysis.init.reverse.takeWhile(_.isDeathCross).size
        
//         if (deathCount == 0) return None
        
//         // Get the corresponding candles for that period (also using init to skip current candle)
//         val candleCount = math.min(deathCount, state.tradingCandles.size - 1)
//         val deathCandles = state.tradingCandles.init.takeRight(candleCount)
        
//         if (deathCandles.isEmpty) None else Some(deathCandles.map(_.low).min)
//     }

//     /**
//      * Check if there was a golden cross in the last N candles.
//      * Used as a cooldown filter for death cross signals to avoid choppy markets.
//      */
//     private def hasRecentGoldenCross(state: SystemState): Boolean = {
//         if (state.recentTrendAnalysis.size < cooldownCandles + 2) return false
        
//         // Look at the last N candles (excluding current cross) for any golden cross
//         val recentTrends = state.recentTrendAnalysis.init.takeRight(cooldownCandles + 1)
        
//         recentTrends.sliding(2).exists {
//             case List(prev, curr) => curr.isGoldenCross && prev.isDeathCross
//             case _ => false
//         }
//     }

//     /**
//      * Check if there was a death cross in the last N candles.
//      * Used as a cooldown filter for golden cross signals to avoid choppy markets.
//      */
//     private def hasRecentDeathCross(state: SystemState): Boolean = {
//         if (state.recentTrendAnalysis.size < cooldownCandles + 2) return false
        
//         // Look at the last N candles (excluding current cross) for any death cross
//         val recentTrends = state.recentTrendAnalysis.init.takeRight(cooldownCandles + 1)
        
//         recentTrends.sliding(2).exists {
//             case List(prev, curr) => curr.isDeathCross && prev.isGoldenCross
//             case _ => false
//         }
//     }

// }