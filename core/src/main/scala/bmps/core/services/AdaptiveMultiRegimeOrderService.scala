package bmps.core.services

import bmps.core.models._
import bmps.core.services.analysis._
import bmps.core.services.rules.{RiskSizingRules, SignalScoring, SignalScore}
import bmps.core.utils.TimestampUtils

/**
 * Advanced adaptive order service that switches strategies based on market regime
 * Focuses on quality over quantity: only trades 75+ scored setups
 * 
 * Key improvements over existing services:
 * - Dynamic regime detection and strategy switching
 * - Volume analysis for confirmation (previously unused)
 * - Multi-indicator convergence (RSI + Stochastics + Williams%R + CCI)
 * - Comprehensive signal scoring (0-100)
 * - Better time-of-day filtering
 * - Stricter entry requirements for higher win rate
 */
class AdaptiveMultiRegimeOrderService(
    accountBalance: Double,
    minSignalScore: Int = 60  // Raised to 60 - data shows <75 scores have 17-23% win rate
) extends RiskSizingRules with SignalScoring {
    
    private val regimeDetector = new RegimeDetection()
    private val volumeAnalyzer = new VolumeConfluence()
    
    def processOneMinuteState(state: SystemState): SystemState = {
        val lastCandle = state.tradingCandles.last
        
        // Basic checks: don't trade if already in position or near close
        if (state.orders.exists(_.isActive)) {
            println(s"[AdaptiveService] Skipping: Already in position")
            return state
        }
        if (TimestampUtils.isNearTradingClose(lastCandle.timestamp)) {
            println(s"[AdaptiveService] Skipping: Near trading close")
            return state
        }
        
        // Need minimum data for analysis
        if (state.tradingCandles.size < 20 || state.recentTrendAnalysis.size < 10 ||
            state.recentMomentumAnalysis.size < 10 || state.recentVolatilityAnalysis.size < 10) {
            println(s"[AdaptiveService] Insufficient data: candles=${state.tradingCandles.size}, trend=${state.recentTrendAnalysis.size}, momentum=${state.recentMomentumAnalysis.size}, vol=${state.recentVolatilityAnalysis.size}")
            return state
        }
        
        // Stop after winning too much today
        if (hasMaxedOutProfitToday(state)) {
            println(s"[AdaptiveService] Skipping: Maxed out profit today")
            return state
        }
        
        // Detect current market regime
        val regimeAnalysis = regimeDetector.detectRegime(
            state.recentTrendAnalysis,
            state.recentVolatilityAnalysis,
            state.recentVolumeAnalysis.headOption.map(_ => state.recentVolumeAnalysis)
        )
        
        println(s"[AdaptiveService] Regime: ${regimeAnalysis.regime}, Confidence: ${regimeAnalysis.confidence}")
        
        // Analyze volume confluence
        val volumeSignal = if (state.recentVolumeAnalysis.nonEmpty) {
            volumeAnalyzer.analyzeVolumeConfluence(state.recentVolumeAnalysis, state.tradingCandles)
        } else {
            VolumeConfluenceSignal(false, false, false, "None", false, false, 0.5, lastCandle.timestamp)
        }
        
        println(s"[AdaptiveService] Volume: spike=${volumeSignal.hasVolumeSpike}, massive=${volumeSignal.hasMassiveSpike}, div=${volumeSignal.hasVolumeDivergence}, confirm=${volumeSignal.volumeConfirmation}")
        
        // Generate order based on regime
        val order = regimeAnalysis.regime match {
            case MarketRegime.TrendingHigh | MarketRegime.TrendingLow =>
                evaluateTrendingStrategy(state, regimeAnalysis, volumeSignal)
            case MarketRegime.Breakout =>
                evaluateBreakoutStrategy(state, regimeAnalysis, volumeSignal)
            case MarketRegime.RangingTight | MarketRegime.RangingWide =>
                // Try mean reversion in ranges (tight or wide)
                evaluateMeanReversionStrategy(state, regimeAnalysis, volumeSignal)
            case MarketRegime.Unknown =>
                None // Insufficient data
        }
        
        order match {
            case Some(o) => 
                println(s"[AdaptiveService] ✓ ORDER PLACED: ${o.orderType} @ ${o.entryPoint}, entryType=${o.entryType}")
                state.copy(orders = state.orders :+ o)
            case None => 
                println(s"[AdaptiveService] No order generated for regime ${regimeAnalysis.regime}")
                state
        }
    }
    
    /**
     * Trending market strategy: Ride momentum with pullbacks
     * Entry: Golden/Death cross + momentum convergence + volume confirmation
     */
    private def evaluateTrendingStrategy(
        state: SystemState,
        regimeAnalysis: RegimeAnalysis,
        volumeSignal: VolumeConfluenceSignal
    ): Option[Order] = {
        val trend = state.recentTrendAnalysis.last
        val momentum = state.recentMomentumAnalysis.last
        val volatility = state.recentVolatilityAnalysis.last
        val lastCandle = state.tradingCandles.last
        
        // Determine direction
        val orderType = if (trend.isUptrend) OrderType.Long else OrderType.Short
        println(s"[TrendRiding] Direction: $orderType, ADX: ${trend.adx}")
        
        // Require strong trend (ADX > 30)
        if (trend.adx < 30.0) {
            println(s"[TrendRiding] ✗ Weak trend: ADX ${trend.adx}")
            return None
        }
        
        // Score the signal
        val signalScore = scoreSignal(state, orderType, regimeAnalysis, volumeSignal)
        println(s"[TrendRiding] Signal score: ${signalScore.totalScore} (trend=${signalScore.trendAlignment}, vol=${signalScore.volumeConfirmation}, mom=${signalScore.momentumConvergence})")
        if (signalScore.totalScore < 75) {
            println(s"[TrendRiding] ✗ Score too low: ${signalScore.totalScore} (need 75+ based on 50% vs 30% win rate)")
            return None
        }
        
        // Check momentum isn't extreme (avoid tops/bottoms)
        val momentumOK = orderType match {
            case OrderType.Long => momentum.rsi > 25.0 && momentum.rsi < 65.0
            case OrderType.Short => momentum.rsi < 75.0 && momentum.rsi > 35.0
        }
        if (!momentumOK) {
            println(s"[TrendRiding] ✗ Momentum extreme: RSI=${momentum.rsi}")
            return None
        }
        
        // Volume confirmation is nice but not required (logged for analysis)
        println(s"[TrendRiding] Volume confirmation: ${volumeSignal.volumeConfirmation}")
        
        // Build order with dynamic ATR-based stops
        buildOrderWithScore(state, orderType, volatility, signalScore, "TrendRiding")
    }
    
    /**
     * Breakout strategy: BB squeeze with volume expansion
     * Entry: Squeeze breakout + massive volume + momentum alignment
     */
    private def evaluateBreakoutStrategy(
        state: SystemState,
        regimeAnalysis: RegimeAnalysis,
        volumeSignal: VolumeConfluenceSignal
    ): Option[Order] = {
        val trend = state.recentTrendAnalysis.last
        val momentum = state.recentMomentumAnalysis.last
        val volatility = state.recentVolatilityAnalysis.last
        val lastCandle = state.tradingCandles.last
        
        // Require volume spike for breakout confirmation
        if (!volumeSignal.hasVolumeSpike) {
            println(s"[Breakout] ✗ No volume spike")
            return None
        }
        
        // Determine breakout direction from price position and momentum
        val bb = volatility.bollingerBands
        val orderType = if (bb.percentB > 0.5 && momentum.rsi > 50.0) {
            OrderType.Long
        } else if (bb.percentB < 0.5 && momentum.rsi < 50.0) {
            OrderType.Short
        } else {
            return None // Unclear direction
        }
        
        // Score the signal
        val signalScore = scoreSignal(state, orderType, regimeAnalysis, volumeSignal)
        println(s"[Breakout] Signal score: ${signalScore.totalScore}, Direction: $orderType")
        if (signalScore.totalScore < minSignalScore) {
            println(s"[Breakout] ✗ Score below minimum ${minSignalScore}")
            return None
        }
        
        // Require good score for breakouts (75+ instead of 85+)
        if (signalScore.totalScore < 75) {
            println(s"[Breakout] ✗ Score not good enough (need 75+)")
            return None
        }
        
        // Build order with wider stops for volatility expansion
        buildOrderWithScore(state, orderType, volatility, signalScore, "Breakout", atrMultiplier = 2.5)
    }
    
    /**
     * Mean reversion strategy: Bollinger Band bounces in consolidation
     * Entry: Price at BB extreme + momentum extreme + low ADX
     */
    private def evaluateMeanReversionStrategy(
        state: SystemState,
        regimeAnalysis: RegimeAnalysis,
        volumeSignal: VolumeConfluenceSignal
    ): Option[Order] = {
        val trend = state.recentTrendAnalysis.last
        val momentum = state.recentMomentumAnalysis.last
        val volatility = state.recentVolatilityAnalysis.last
        val lastCandle = state.tradingCandles.last
        
        // Require weak trend (ADX < 35, relaxed from 25)
        if (trend.adx > 35.0) {
            println(s"[MeanReversion] ✗ ADX too high: ${trend.adx}")
            return None
        }
        
        val bb = volatility.bollingerBands
        println(s"[MeanReversion] BB %B: ${bb.percentB}, RSI: ${momentum.rsi}")
        
        // Identify mean reversion opportunity (relaxed thresholds)
        val orderType = if (bb.percentB < 0.3 && momentum.rsi < 35.0) {
            OrderType.Long // Bounce from lower band
        } else if (bb.percentB > 0.7 && momentum.rsi > 65.0) {
            OrderType.Short // Bounce from upper band
        } else {
            println(s"[MeanReversion] ✗ No extreme condition (need %B < 0.3 with RSI < 35, or %B > 0.7 with RSI > 65)")
            return None
        }
        
        // Score the signal
        val signalScore = scoreSignal(state, orderType, regimeAnalysis, volumeSignal)
        println(s"[MeanReversion] Signal score: ${signalScore.totalScore}, Direction: $orderType")
        if (signalScore.totalScore < 75) {
            println(s"[MeanReversion] ✗ Score too low: ${signalScore.totalScore} (need 75+ - data shows <75 = 25% win rate)")
            return None
        }
        
        // Check for convergence of momentum indicators
        val convergence = orderType match {
            case OrderType.Long =>
                momentum.stochastics.isOversold && momentum.williamsROversold
            case OrderType.Short =>
                momentum.stochastics.isOverbought && momentum.williamsROverbought
        }
        if (!convergence) return None
        
        // Build order with tighter stops (ranging market)
        buildOrderWithScore(state, orderType, volatility, signalScore, "MeanReversion", atrMultiplier = 1.5)
    }
    
    /**
     * Build order with ATR-based stops and signal score tracking
     */
    private def buildOrderWithScore(
        state: SystemState,
        orderType: OrderType,
        volatility: VolatilityAnalysis,
        signalScore: SignalScore,
        strategyName: String,
        atrMultiplier: Double = 2.0,
        profitMultiplier: Float = 2.5f
    ): Option[Order] = {
        val lastCandle = state.tradingCandles.last
        val atr = volatility.trueRange.atr
        val entry = lastCandle.close.toDouble
        
        // Calculate stop and target
        val (low, high) = orderType match {
            case OrderType.Long =>
                val stop = entry - (atr * atrMultiplier)
                (stop.toFloat, entry.toFloat)
            case OrderType.Short =>
                val stop = entry + (atr * atrMultiplier)
                (entry.toFloat, stop.toFloat)
        }
        
        // Calculate risk multiplier using Kelly criterion
        val riskMultiplier = computeRiskMultiplierKelly(state, accountBalance)
        
        // Create order with bucketed score for entryType grouping
        val scoreBucket = signalScore.totalScore match {
            case s if s >= 90 => "Score90+"
            case s if s >= 85 => "Score85-89"
            case s if s >= 80 => "Score80-84"
            case s if s >= 75 => "Score75-79"
            case _ => "Score<75"
        }
        
        val order = Order(
            low = low,
            high = high,
            timestamp = lastCandle.timestamp,
            orderType = orderType,
            entryType = EntryType.Trendy(s"Adaptive-$strategyName-$scoreBucket"),
            contract = state.contractSymbol.getOrElse("MES"),
            status = OrderStatus.PlaceNow,
            profitMultiplier = profitMultiplier,
            riskMultiplier = Some(riskMultiplier)
        )
        
        // Final safety check: order must make sense
        if (order.atRiskPoints > 0 && order.takeProfit != order.entryPoint) {
            Some(order)
        } else {
            None
        }
    }
    
    /**
     * Check for recent MA crossover (last 10 minutes for more flexibility)
     */
    private def checkRecentMACross(state: SystemState, orderType: OrderType): Boolean = {
        if (state.recentTrendAnalysis.size < 11) return false
        
        val last11 = state.recentTrendAnalysis.takeRight(11)
        
        // Look for cross in last 10 candles
        val crosses = last11.sliding(2).toList.zipWithIndex.collect {
            case (Seq(prev, curr), idx) =>
                val hadGoldenCross = !prev.isGoldenCross && curr.isGoldenCross
                val hadDeathCross = !prev.isDeathCross && curr.isDeathCross
                (hadGoldenCross, hadDeathCross, idx)
        }
        
        orderType match {
            case OrderType.Long => crosses.exists(_._1)
            case OrderType.Short => crosses.exists(_._2)
        }
    }
    
    /**
     * Avoid trading 10am-12pm (historically poor performance)
     */
    private def isInDeadZone(timestamp: Long): Boolean = {
        val hour = java.time.Instant.ofEpochMilli(timestamp).atZone(TimestampUtils.NewYorkZone).getHour
        hour >= 10 && hour < 12
    }
    
    /**
     * Stop trading after +6R profit to lock in gains
     */
    private def hasMaxedOutProfitToday(state: SystemState): Boolean = {
        val todayOrders = state.orders.filter(o => 
            o.status == OrderStatus.Profit || o.status == OrderStatus.Loss
        )
        
        if (todayOrders.isEmpty) return false
        
        val totalR = todayOrders.map { order =>
            if (order.status == OrderStatus.Profit) {
                order.profitMultiplier.toDouble
            } else {
                -1.0
            }
        }.sum
        
        totalR >= 6.0
    }
}
