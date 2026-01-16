package bmps.core.services.analysis

import bmps.core.models.Candle

/** Volume confluence signals for enhanced trade confirmation */
case class VolumeConfluenceSignal(
    hasVolumeSpike: Boolean,
    hasMassiveSpike: Boolean,
    hasVolumeDivergence: Boolean,
    divergenceType: String,
    isAccumulation: Boolean,
    isDistribution: Boolean,
    volumeConfirmation: Double,
    timestamp: Long
)

class VolumeConfluence() {
    
    def analyzeVolumeConfluence(
        recentVolume: List[VolumeAnalysis],
        recentCandles: List[Candle]
    ): VolumeConfluenceSignal = {
        require(recentVolume.nonEmpty, "Volume analysis list cannot be empty")
        require(recentCandles.nonEmpty, "Candles list cannot be empty")
        
        if (recentVolume.size < 5 || recentCandles.size < 5) {
            return VolumeConfluenceSignal(
                false, false, false, "None", false, false, 0.5, 
                recentCandles.last.timestamp
            )
        }
        
        val currentVol = recentVolume.last
        val currentCandle = recentCandles.last
        
        val (hasVolumeSpike, hasMassiveSpike) = detectVolumeSpikes(currentVol)
        val (hasDivergence, divergenceType) = detectVolumeDivergence(recentVolume, recentCandles)
        val (isAccumulation, isDistribution) = detectAccumulationDistribution(recentVolume, recentCandles)
        val confirmation = calculateVolumeConfirmation(
            currentVol, hasVolumeSpike, hasDivergence, isAccumulation, isDistribution
        )
        
        VolumeConfluenceSignal(
            hasVolumeSpike, hasMassiveSpike, hasDivergence, divergenceType,
            isAccumulation, isDistribution, confirmation, currentCandle.timestamp
        )
    }
    
    private def detectVolumeSpikes(current: VolumeAnalysis): (Boolean, Boolean) = {
        val relVol = current.relativeVolume
        val hasSpike = relVol > 1.5
        val hasMassiveSpike = relVol > 2.0
        (hasSpike, hasMassiveSpike)
    }
    
    private def detectVolumeDivergence(recentVolume: List[VolumeAnalysis], recentCandles: List[Candle]): (Boolean, String) = {
        if (recentVolume.size < 10 || recentCandles.size < 10) return (false, "None")
        
        val last5Vol = recentVolume.takeRight(5)
        val prev5Vol = recentVolume.slice(recentVolume.size - 10, recentVolume.size - 5)
        val last5Candles = recentCandles.takeRight(5)
        val prev5Candles = recentCandles.slice(recentCandles.size - 10, recentCandles.size - 5)
        
        val last5AvgVol = last5Vol.map(_.relativeVolume).sum / 5.0
        val prev5AvgVol = prev5Vol.map(_.relativeVolume).sum / 5.0
        val last5PriceChange = last5Candles.last.close - last5Candles.head.close
        
        val volumeIncreasing = last5AvgVol > prev5AvgVol * 1.1
        val volumeDecreasing = last5AvgVol < prev5AvgVol * 0.9
        val priceGoingUp = last5PriceChange > 0
        val priceGoingDown = last5PriceChange < 0
        
        if (priceGoingDown && volumeIncreasing) {
            (true, "Bullish")
        } else if (priceGoingUp && volumeDecreasing) {
            (true, "Bearish")
        } else {
            (false, "None")
        }
    }
    
    private def detectAccumulationDistribution(
        recentVolume: List[VolumeAnalysis],
        recentCandles: List[Candle]
    ): (Boolean, Boolean) = {
        if (recentVolume.size < 10) return (false, false)
        
        val last10Vol = recentVolume.takeRight(10)
        val last10Candles = recentCandles.takeRight(10)
        val currentVol = last10Vol.last
        val currentCandle = last10Candles.last
        
        val recentLow = last10Candles.map(_.low).min
        val recentHigh = last10Candles.map(_.high).max
        val range = recentHigh - recentLow
        val pricePosition = if (range > 0) (currentCandle.close - recentLow) / range else 0.5
        
        val obvTrending = if (last10Vol.size >= 2) {
            val firstOBV = last10Vol.head.onBalanceVolume
            val lastOBV = last10Vol.last.onBalanceVolume
            lastOBV > firstOBV
        } else false
        
        val isAccumulation = pricePosition < 0.4 && currentVol.isVolumeIncreasing && obvTrending
        val isDistribution = pricePosition > 0.6 && currentVol.isVolumeIncreasing && !obvTrending
        
        (isAccumulation, isDistribution)
    }
    
    private def calculateVolumeConfirmation(
        current: VolumeAnalysis,
        hasSpike: Boolean,
        hasDivergence: Boolean,
        isAccumulation: Boolean,
        isDistribution: Boolean
    ): Double = {
        var score = 0.5
        
        if (current.relativeVolume > 1.5) score += 0.2
        else if (current.relativeVolume > 1.2) score += 0.1
        else if (current.relativeVolume < 0.8) score -= 0.15
        
        if (current.isVolumeIncreasing) score += 0.15
        
        current.volumeQuality match {
            case "Excellent" => score += 0.15
            case "Good" => score += 0.10
            case "Poor" => score -= 0.15
            case _ =>
        }
        
        if (hasDivergence) score -= 0.1
        if (isAccumulation) score += 0.1
        if (isDistribution) score -= 0.1
        
        math.max(0.0, math.min(1.0, score))
    }
    
    def volumeConfirmsDirection(recentVolume: List[VolumeAnalysis], recentCandles: List[Candle], isLong: Boolean): Boolean = {
        if (recentVolume.size < 3 || recentCandles.size < 3) return false
        
        val last3Vol = recentVolume.takeRight(3)
        val last3Candles = recentCandles.takeRight(3)
        
        val volIncreasing = last3Vol.last.relativeVolume > last3Vol.head.relativeVolume
        val priceMovement = last3Candles.last.close - last3Candles.head.close
        val priceConfirms = if (isLong) priceMovement > 0 else priceMovement < 0
        
        volIncreasing && priceConfirms && last3Vol.last.relativeVolume > 1.1
    }
}
