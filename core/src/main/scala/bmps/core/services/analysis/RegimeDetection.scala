package bmps.core.services.analysis

import bmps.core.models.Candle

/** Market regime types for adaptive strategy selection */
sealed trait MarketRegime
object MarketRegime {
    case object TrendingHigh extends MarketRegime       // Strong trending + high volatility
    case object TrendingLow extends MarketRegime        // Strong trending + low volatility
    case object RangingTight extends MarketRegime       // Weak trend + low volatility (consolidation)
    case object RangingWide extends MarketRegime        // Weak trend + high volatility (choppy)
    case object Breakout extends MarketRegime           // BB squeeze + increasing volatility
    case object Unknown extends MarketRegime            // Insufficient data
}

/** Complete regime analysis with confidence metrics */
case class RegimeAnalysis(
    regime: MarketRegime,
    trendScore: Double,          // 0-100: how strong is the trend
    volatilityScore: Double,     // 0-100: normalized volatility level
    volumeScore: Double,         // 0-100: volume confirmation strength
    confidence: Double,          // 0-1: confidence in regime classification
    isTransitioning: Boolean,    // true if regime is likely changing
    timestamp: Long
) {
    def isTrending: Boolean = regime == MarketRegime.TrendingHigh || regime == MarketRegime.TrendingLow
    def isRanging: Boolean = regime == MarketRegime.RangingTight || regime == MarketRegime.RangingWide
    def isBreakout: Boolean = regime == MarketRegime.Breakout
    def isHighVolatility: Boolean = volatilityScore > 60.0
    def isLowVolatility: Boolean = volatilityScore < 40.0
}

class RegimeDetection() {
    
    /**
     * Detect current market regime from recent analyses
     */
    def detectRegime(
        recentTrend: List[TrendAnalysis],
        recentVolatility: List[VolatilityAnalysis],
        recentVolume: Option[List[VolumeAnalysis]] = None
    ): RegimeAnalysis = {
        require(recentTrend.nonEmpty, "Trend analysis list cannot be empty")
        require(recentVolatility.nonEmpty, "Volatility analysis list cannot be empty")
        
        if (recentTrend.size < 5 || recentVolatility.size < 5) {
            return RegimeAnalysis(
                MarketRegime.Unknown, 0.0, 0.0, 0.0, 0.0, false,
                recentTrend.last.timestamp
            )
        }
        
        val currentTrend = recentTrend.last
        val currentVol = recentVolatility.last
        val currentVolume = recentVolume.flatMap(_.lastOption)
        
        val trendScore = calculateTrendScore(currentTrend, recentTrend)
        val volatilityScore = calculateVolatilityScore(currentVol, recentVolatility)
        val volumeScore = currentVolume.map(v => calculateVolumeScore(v, recentVolume.get)).getOrElse(50.0)
        val regime = classifyRegime(trendScore, volatilityScore, currentVol)
        val confidence = calculateConfidence(trendScore, volatilityScore, volumeScore, recentTrend.size)
        val isTransitioning = detectTransition(recentTrend, recentVolatility)
        
        RegimeAnalysis(
            regime, trendScore, volatilityScore, volumeScore, 
            confidence, isTransitioning, currentTrend.timestamp
        )
    }
    
    private def calculateTrendScore(current: TrendAnalysis, recent: List[TrendAnalysis]): Double = {
        var score = 0.0
        
        // ADX is primary trend strength indicator
        score += math.min(current.adx, 100.0) * 0.5
        
        // Direction consistency over last 5 periods
        val lastFive = recent.takeRight(5)
        val directionConsistency = if (lastFive.size >= 5) {
            val upCount = lastFive.count(_.isUptrend)
            val downCount = lastFive.count(_.isDowntrend)
            math.max(upCount, downCount) / 5.0
        } else 0.5
        score += directionConsistency * 30.0
        
        // MA crossover strength
        score += current.maCrossoverStrength * 20.0
        
        math.min(score, 100.0)
    }
    
    private def calculateVolatilityScore(current: VolatilityAnalysis, recent: List[VolatilityAnalysis]): Double = {
        val atr = current.trueRange.atr
        
        val historicalATRs = recent.map(_.trueRange.atr)
        val maxATR = if (historicalATRs.nonEmpty) historicalATRs.max else atr
        
        val normalizedATR = if (maxATR > 0) (atr / maxATR) * 100.0 else 50.0
        
        val atrTrendBonus = current.trueRange.atrTrend match {
            case "Increasing" => 10.0
            case "Decreasing" => -10.0
            case _ => 0.0
        }
        
        val bbAdjustment = if (current.bollingerBands.isSqueezing) -20.0 else 0.0
        
        math.max(0.0, math.min(normalizedATR + atrTrendBonus + bbAdjustment, 100.0))
    }
    
    private def calculateVolumeScore(current: VolumeAnalysis, recent: List[VolumeAnalysis]): Double = {
        var score = 50.0
        
        if (current.relativeVolume > 1.5) score += 20.0
        else if (current.relativeVolume > 1.2) score += 10.0
        else if (current.relativeVolume < 0.7) score -= 15.0
        
        if (current.isVolumeIncreasing) score += 15.0
        
        if (recent.size >= 2) {
            val prevOBV = recent(recent.size - 2).onBalanceVolume
            if (current.onBalanceVolume > prevOBV) score += 15.0
            else if (current.onBalanceVolume < prevOBV) score -= 10.0
        }
        
        math.max(0.0, math.min(score, 100.0))
    }
    
    private def classifyRegime(trendScore: Double, volatilityScore: Double, currentVol: VolatilityAnalysis): MarketRegime = {
        if (currentVol.bollingerBands.isSqueezing && currentVol.trueRange.atrTrend == "Increasing") {
            return MarketRegime.Breakout
        }
        
        if (trendScore > 50.0) {
            if (volatilityScore > 60.0) MarketRegime.TrendingHigh
            else MarketRegime.TrendingLow
        } else {
            if (volatilityScore > 50.0) MarketRegime.RangingWide
            else MarketRegime.RangingTight
        }
    }
    
    private def calculateConfidence(trendScore: Double, volatilityScore: Double, volumeScore: Double, sampleSize: Int): Double = {
        val trendConfidence = math.abs(trendScore - 50.0) / 50.0
        val volConfidence = math.abs(volatilityScore - 50.0) / 50.0
        val volumeConfidence = math.abs(volumeScore - 50.0) / 50.0
        
        val avgConfidence = (trendConfidence + volConfidence + volumeConfidence) / 3.0
        val samplePenalty = if (sampleSize < 10) 0.7 else if (sampleSize < 20) 0.85 else 1.0
        
        avgConfidence * samplePenalty
    }
    
    private def detectTransition(recentTrend: List[TrendAnalysis], recentVolatility: List[VolatilityAnalysis]): Boolean = {
        if (recentTrend.size < 5 || recentVolatility.size < 5) return false
        
        val last5Trend = recentTrend.takeRight(5)
        val last5Vol = recentVolatility.takeRight(5)
        
        val adxChanging = {
            val firstADX = last5Trend.head.adx
            val lastADX = last5Trend.last.adx
            val adxChange = math.abs(lastADX - firstADX)
            adxChange > 5.0
        }
        
        val volChanging = {
            val firstVol = last5Vol.head.trueRange.volatilityLevel
            val lastVol = last5Vol.last.trueRange.volatilityLevel
            firstVol != lastVol
        }
        
        val directionFlip = {
            val firstDir = last5Trend.head.isUptrend
            val lastDir = last5Trend.last.isUptrend
            firstDir != lastDir
        }
        
        adxChanging || volChanging || directionFlip
    }
}
