package bmps.core.strategies.entrance

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.EntryStrategy
import bmps.core.strategies.exit.SimpleExitStrategy
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.services.analysis.VolatilityAnalysis

/**
 * MomentumCrossoverEntryStrategy detects trend reversals via golden/death crosses
 * with significant momentum. It identifies early reversals by measuring the velocity
 * and acceleration of MA spread convergence, filtering out low-conviction crossovers
 * during quiet/consolidating markets.
 * 
 * Key features:
 * - Detects MA spread collapse velocity (rapid convergence toward crossover)
 * - Measures acceleration to confirm momentum is building
 * - Filters out quiet periods using ATR and Bollinger Band squeeze
 * - Can trigger before the actual crossover when momentum is strong
 */
trait MomentumCrossoverEntryStrategy {
    private final val entryStrategy = EntryStrategy("MomentumCrossoverEntryStrategy")
    private final val exitStrategy = new SimpleExitStrategy()

    // Configurable parameters - TUNED FOR ~300 ENTRIES/3 MONTHS (Golden only)
    
    /** Minimum velocity of spread change */
    private val spreadVelocityThreshold = 0.15
    
    /** Minimum acceleration - low threshold to capture momentum */
    private val accelerationThreshold = 0.02
    
    /** Number of periods to analyze for velocity/acceleration calculation */
    private val lookbackPeriods = 5
    
    /** Minimum ATR ratio vs recent average to filter quiet periods */
    private val minATRMultiple = 0.8
    
    /** Minimum ADX for trend strength filter */
    private val minADX = 20.0
    
    /** Enable/disable death cross signals (disabled - underperforms Golden) */
    private val enableDeathCross = false
    
    /** ATR multiplier for stop loss */
    private val stopLossATRMultiple = 2.5
    
    /** ATR multiplier for take profit */
    private val takeProfitATRMultiple = 3.0

    /**
     * Main trigger method - checks if momentum crossover conditions are met.
     * Returns Some((OrderType, EntryStrategy, setupFn)) if triggered, None otherwise.
     */
    def isMomentumCrossoverTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        // Require sufficient history for analysis
        if (state.recentTrendAnalysis.size < lookbackPeriods + 2) return None
        if (state.recentVolatilityAnalysis.size < lookbackPeriods + 2) return None

        // Filter out quiet/consolidating markets
        if (isQuietPeriod(state)) return None

        val currentTrend = state.recentTrendAnalysis.last
        val recentTrends = state.recentTrendAnalysis.takeRight(lookbackPeriods + 1)
        
        // Calculate MA spreads (positive = golden cross territory, negative = death cross)
        val spreads = recentTrends.map(t => t.shortTermMA - t.longTermMA)
        val normalizedSpreads = spreads.map(_ / currentTrend.longTermMA) // Normalize by long-term MA
        
        // Calculate velocity and acceleration of spread change
        val velocities = calculateVelocities(normalizedSpreads)
        val velocity = velocities.lastOption.getOrElse(0.0)
        val acceleration = calculateAcceleration(velocities)
        
        // Check for bullish momentum crossover (golden cross forming/formed)
        val bullishSignal = detectBullishMomentumCrossover(
            normalizedSpreads, velocity, acceleration, currentTrend
        )
        
        // Check for bearish momentum crossover (death cross) - disabled by default
        val bearishSignal = if (enableDeathCross) {
            detectBearishMomentumCrossover(normalizedSpreads, velocity, acceleration, currentTrend)
        } else false
        
        (bullishSignal, bearishSignal) match {
            case (true, false) => 
                val description = buildEntryDescription("Golden", velocity, acceleration)
                Some((OrderType.Long, entryStrategy.copy(description = description), momentumCrossoverSetup))
            case (false, true) => 
                val description = buildEntryDescription("Death", velocity, acceleration)
                Some((OrderType.Short, entryStrategy.copy(description = description), momentumCrossoverSetup))
            case _ => None
        }
    }

    /**
     * Detects bullish momentum crossover (golden cross with momentum).
     * Triggers when spread is moving strongly upward with building momentum.
     * Does NOT require an actual crossover - captures momentum in golden cross territory.
     */
    private def detectBullishMomentumCrossover(
        normalizedSpreads: List[Double],
        velocity: Double,
        acceleration: Double,
        currentTrend: TrendAnalysis
    ): Boolean = {
        val currentSpread = normalizedSpreads.last
        
        // Check ADX for minimum trend strength
        if (currentTrend.adx < minADX) return false
        
        // Velocity must exceed threshold (spread moving upward)
        if (velocity < spreadVelocityThreshold) return false
        
        // Acceleration must exceed threshold (momentum building)
        if (acceleration < accelerationThreshold) return false
        
        // Must be in or entering golden cross territory (short MA above or approaching long MA)
        // Either already crossed (positive spread) OR rapidly approaching (small negative)
        currentSpread >= -0.005  // Within 0.5% of crossover or already crossed
    }

    /**
     * Detects bearish momentum crossover (death cross with momentum).
     * Triggers when spread is moving strongly downward with building momentum.
     * NOTE: Currently disabled by enableDeathCross flag based on backtest results.
     */
    private def detectBearishMomentumCrossover(
        normalizedSpreads: List[Double],
        velocity: Double,
        acceleration: Double,
        currentTrend: TrendAnalysis
    ): Boolean = {
        val currentSpread = normalizedSpreads.last
        
        // Check ADX for minimum trend strength
        if (currentTrend.adx < minADX) return false
        
        // Velocity must be strongly negative
        if (velocity > -spreadVelocityThreshold) return false
        
        // Acceleration must be strongly negative (momentum building)
        if (acceleration > -accelerationThreshold) return false
        
        // Must be in or entering death cross territory
        currentSpread <= 0.005  // Within 0.5% of crossover or already crossed
    }

    /**
     * Filters out quiet periods where crossovers are less meaningful.
     * Returns true if market is in a quiet/consolidating state.
     * LOOSENED: Only filter when BOTH squeeze AND low ATR.
     */
    private def isQuietPeriod(state: SystemState): Boolean = {
        val currentVol = state.recentVolatilityAnalysis.last
        val recentVols = state.recentVolatilityAnalysis.takeRight(lookbackPeriods + 1)
        
        // Check Bollinger Band squeeze (low volatility consolidation)
        val isSqueeze = currentVol.bollingerBands.isSqueezing
        
        // Check for low ATR relative to recent average
        val avgATR = recentVols.map(_.trueRange.atr).sum / recentVols.size
        val currentATR = currentVol.trueRange.atr
        val isLowATR = currentATR < avgATR * minATRMultiple
        
        // Only filter when BOTH conditions are met (more permissive)
        isSqueeze && isLowATR
    }

    /**
     * Calculates velocity of spread changes (rate of change between consecutive spreads).
     * Positive velocity = spread increasing, negative = spread decreasing.
     */
    private def calculateVelocities(spreads: List[Double]): List[Double] = {
        if (spreads.size < 2) return List.empty
        
        spreads.sliding(2).map { 
            case List(prev, curr) =>
                if (math.abs(prev) < 0.0001) {
                    // Avoid division by near-zero, use absolute change
                    curr - prev
                } else {
                    (curr - prev) / math.abs(prev)
                }
            case _ => 0.0
        }.toList
    }

    /**
     * Calculates acceleration of spread change (change in velocity).
     * Positive acceleration = momentum building in positive direction.
     */
    private def calculateAcceleration(velocities: List[Double]): Double = {
        if (velocities.size < 2) return 0.0
        
        val recentVelocities = velocities.takeRight(3)
        if (recentVelocities.size < 2) return 0.0
        
        // Average acceleration over recent periods
        val accelerations = recentVelocities.sliding(2).map {
            case List(prev, curr) => curr - prev
            case _ => 0.0
        }.toList
        
        if (accelerations.isEmpty) 0.0 else accelerations.sum / accelerations.size
    }

    /**
     * Builds a descriptive entry strategy label for tracking.
     * Buckets velocity and acceleration into categories for ~10 unique groups.
     */
    private def buildEntryDescription(crossType: String, velocity: Double, acceleration: Double): String = {
        val absVel = math.abs(velocity)
        val absAcc = math.abs(acceleration)
        
        // Velocity buckets: VeryHigh (>60%), High (40-60%), Moderate (<40%)
        val velBucket = if (absVel > 0.60) "VeryHigh"
                        else if (absVel > 0.40) "High"
                        else "Moderate"
        
        // Acceleration buckets: Strong (>15%), Steady (<=15%)
        val accBucket = if (absAcc > 0.15) "Strong" else "Steady"
        
        s"MomentumCrossover:$crossType:$velBucket:$accBucket"
    }

    /**
     * Sets up the order with ATR-based stops and targets.
     */
    def momentumCrossoverSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy): Order = {
        val lastCandle = state.tradingCandles.last
        val atr = state.recentVolatilityAnalysis.last.trueRange.atr
        val entry = lastCandle.close

        val (stop, profit) = orderType match {
            case OrderType.Long =>
                val longStop = entry - (atr * stopLossATRMultiple)
                val longTP = entry + (atr * takeProfitATRMultiple)
                (longStop, longTP)
            case OrderType.Short =>
                val shortStop = entry + (atr * stopLossATRMultiple)
                val shortTP = entry - (atr * takeProfitATRMultiple)
                (shortStop, shortTP)
        }

        // Trailing stop at 1.5x ATR to lock in profits
        val trailStop = atr * 1.5

        Order(
            timestamp = lastCandle.endTime,
            orderType = orderType,
            status = OrderStatus.PlaceNow,
            contractType = ContractType.ES,
            contract = state.contractSymbol.get,
            contracts = 1,
            entryStrategy = entryStrategy,
            exitStrategy = exitStrategy,
            entryPrice = entry,
            stopLoss = stop,
            trailStop = Some(trailStop),
            takeProfit = profit
        )
    }
}
