package bmps.core.services.rules

import bmps.core.models.{SystemState, OrderType}
import bmps.core.services.analysis._

/** Signal quality score for filtering high-probability setups */
case class SignalScore(
    totalScore: Int,              // 0-100 total score
    trendAlignment: Int,          // 0-20 points
    volumeConfirmation: Int,      // 0-20 points
    momentumConvergence: Int,     // 0-20 points
    volatilityContext: Int,       // 0-20 points
    regimeAppropriate: Int,       // 0-20 points
    isPassing: Boolean            // true if score >= 75
) {
    def isExcellent: Boolean = totalScore >= 85
    def isGood: Boolean = totalScore >= 75
    def isMarginal: Boolean = totalScore >= 65 && totalScore < 75
}

trait SignalScoring {
    
    /**
     * Score a potential trade signal (0-100)
     * Only trade signals scoring 75+ for quality over quantity
     */
    def scoreSignal(
        state: SystemState,
        orderType: OrderType,
        regimeAnalysis: RegimeAnalysis,
        volumeSignal: VolumeConfluenceSignal
    ): SignalScore = {
        
        val trendScore = scoreTrendAlignment(state, orderType)
        val volumeScore = scoreVolumeConfirmation(state, volumeSignal, orderType)
        val momentumScore = scoreMomentumConvergence(state, orderType)
        val volatilityScore = scoreVolatilityContext(state, regimeAnalysis)
        val regimeScore = scoreRegimeAppropriate(orderType, regimeAnalysis)
        
        val total = trendScore + volumeScore + momentumScore + volatilityScore + regimeScore
        
        SignalScore(
            total, trendScore, volumeScore, momentumScore, 
            volatilityScore, regimeScore, total >= 75
        )
    }
    
    /**
     * Trend alignment score (0-20 points)
     * More generous scoring - trend strength matters most
     */
    private def scoreTrendAlignment(state: SystemState, orderType: OrderType): Int = {
        if (state.recentTrendAnalysis.isEmpty) return 0
        
        val trend = state.recentTrendAnalysis.last
        var score = 0
        
        // Strong ADX = 10 points (increased from 8)
        if (trend.adx > 40.0) score += 10
        else if (trend.adx > 35.0) score += 9  // NEW
        else if (trend.adx > 30.0) score += 7
        else if (trend.adx > 25.0) score += 5
        else score += 0 // Weak trend
        
        // Direction alignment = 6 points (reduced from 8, trend strength more important)
        val directionAligns = orderType match {
            case OrderType.Long => trend.isUptrend
            case OrderType.Short => trend.isDowntrend
        }
        if (directionAligns) score += 6
        
        // MA cross alignment = 4 points (unchanged)
        val crossAligns = orderType match {
            case OrderType.Long => trend.isGoldenCross
            case OrderType.Short => trend.isDeathCross
        }
        if (crossAligns) score += 4
        
        math.min(score, 20)
    }
    
    /**
     * Volume confirmation score (0-20 points)
     * Maximum points when volume spike + no divergence + accumulation/distribution
     */
    private def scoreVolumeConfirmation(state: SystemState, volumeSignal: VolumeConfluenceSignal, orderType: OrderType): Int = {
        var score = 0
        
        // High volume confirmation = 10 points
        if (volumeSignal.volumeConfirmation > 0.8) score += 10
        else if (volumeSignal.volumeConfirmation > 0.6) score += 7
        else if (volumeSignal.volumeConfirmation > 0.4) score += 4
        else score += 0
        
        // Volume spike = 5 points
        if (volumeSignal.hasMassiveSpike) score += 5
        else if (volumeSignal.hasVolumeSpike) score += 3
        
        // Accumulation/Distribution alignment = 5 points
        val accDistAligns = orderType match {
            case OrderType.Long => volumeSignal.isAccumulation && !volumeSignal.isDistribution
            case OrderType.Short => volumeSignal.isDistribution && !volumeSignal.isAccumulation
        }
        if (accDistAligns) score += 5
        
        // Penalize bearish divergence on long, bullish on short
        if (volumeSignal.hasVolumeDivergence) {
            val badDivergence = orderType match {
                case OrderType.Long => volumeSignal.divergenceType == "Bearish"
                case OrderType.Short => volumeSignal.divergenceType == "Bullish"
            }
            if (badDivergence) score -= 5
        }
        
        math.max(0, math.min(score, 20))
    }
    
    /**
     * Momentum convergence score (0-20 points)
     * More generous - reward momentum in right direction, not just extremes
     */
    private def scoreMomentumConvergence(state: SystemState, orderType: OrderType): Int = {
        if (state.recentMomentumAnalysis.isEmpty) return 0
        
        val momentum = state.recentMomentumAnalysis.last
        var score = 0
        
        orderType match {
            case OrderType.Long =>
                // RSI < 50 (not overbought) = 5 points
                if (momentum.rsi < 35.0) score += 5
                else if (momentum.rsi < 45.0) score += 4  // Increased from 3
                else if (momentum.rsi < 50.0) score += 2  // NEW - reward not being overbought
                
                // Stochastics = 5 points
                if (momentum.stochastics.isOversold) score += 5
                else if (momentum.stochastics.percentK < 40.0) score += 4  // Increased from 3
                else if (momentum.stochastics.percentK < 50.0) score += 2  // NEW
                
                // Williams%R = 5 points  
                if (momentum.williamsROversold) score += 5
                else if (momentum.williamsR < -60.0) score += 4  // Increased from 3
                else if (momentum.williamsR < -50.0) score += 2  // NEW
                
                // CCI = 5 points
                if (momentum.cciOversold) score += 5
                else if (momentum.cci < 0.0) score += 4  // Increased threshold and points
                
            case OrderType.Short =>
                // RSI > 50 (not oversold) = 5 points
                if (momentum.rsi > 65.0) score += 5
                else if (momentum.rsi > 55.0) score += 4  // Increased from 3
                else if (momentum.rsi > 50.0) score += 2  // NEW - reward not being oversold
                
                // Stochastics = 5 points
                if (momentum.stochastics.isOverbought) score += 5
                else if (momentum.stochastics.percentK > 60.0) score += 4  // Increased from 3
                else if (momentum.stochastics.percentK > 50.0) score += 2  // NEW
                
                // Williams%R = 5 points
                if (momentum.williamsROverbought) score += 5
                else if (momentum.williamsR > -40.0) score += 4  // Increased from 3
                else if (momentum.williamsR > -50.0) score += 2  // NEW
                
                // CCI = 5 points
                if (momentum.cciOverbought) score += 5
                else if (momentum.cci > 0.0) score += 4  // Increased threshold and points
        }
        
        math.min(score, 20)
    }
    
    /**
     * Volatility context score (0-20 points)
     * Maximum points when volatility is appropriate for the regime
     */
    private def scoreVolatilityContext(state: SystemState, regimeAnalysis: RegimeAnalysis): Int = {
        if (state.recentVolatilityAnalysis.isEmpty) return 0
        
        val volatility = state.recentVolatilityAnalysis.last
        var score = 0
        
        // ATR in reasonable range (not too high, not too low) = 10 points
        val volLevel = volatility.trueRange.volatilityLevel
        volLevel match {
            case "Normal" => score += 10
            case "High" => score += 7
            case "Low" => score += 5
            case "Extreme" => score += 3
            case _ => score += 5
        }
        
        // Bollinger Band position = 5 points
        val bb = volatility.bollingerBands
        if (!bb.isSqueezing) {
            if (bb.percentB > 0.2 && bb.percentB < 0.8) score += 5 // In middle of bands
            else if (bb.percentB > 0.1 && bb.percentB < 0.9) score += 3
        } else {
            score += 3 // Squeeze can be good for breakout
        }
        
        // Keltner channel confirmation = 5 points
        val kc = volatility.keltnerChannels
        if (kc.pricePosition == "Inside") score += 5
        else if (kc.pricePosition != "Unknown") score += 2
        
        math.min(score, 20)
    }
    
    /**
     * Regime appropriateness score (0-20 points)
     * Maximum points when strategy matches the regime
     */
    private def scoreRegimeAppropriate(orderType: OrderType, regimeAnalysis: RegimeAnalysis): Int = {
        var score = 0
        
        // High confidence in regime = 10 points
        if (regimeAnalysis.confidence > 0.7) score += 10
        else if (regimeAnalysis.confidence > 0.5) score += 7
        else if (regimeAnalysis.confidence > 0.3) score += 4
        else score += 0
        
        // Appropriate regime for directional trading = 10 points
        regimeAnalysis.regime match {
            case MarketRegime.TrendingHigh | MarketRegime.TrendingLow =>
                score += 10 // Best for directional trades
            case MarketRegime.Breakout =>
                score += 8 // Good for breakout trades
            case MarketRegime.RangingTight =>
                score += 3 // Poor for trends, better for mean reversion
            case MarketRegime.RangingWide =>
                score += 2 // Choppy, avoid
            case MarketRegime.Unknown =>
                score += 0
        }
        
        // Penalize transitioning regimes
        if (regimeAnalysis.isTransitioning) score -= 5
        
        math.max(0, math.min(score, 20))
    }
}
