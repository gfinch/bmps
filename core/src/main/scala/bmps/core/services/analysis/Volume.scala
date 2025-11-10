package bmps.core.services.analysis

import bmps.core.models.Candle
import scala.collection.mutable

// Case class to represent a price level in the volume profile
case class VolumeProfileLevel(price: Double, volume: Long, percentOfTotal: Double)

// Case class to hold volume profile results
case class VolumeProfile(
    levels: List[VolumeProfileLevel],
    pocPrice: Double,        // Point of Control (highest volume price)
    pocVolume: Long,         // Volume at POC
    valueAreaHigh: Double,   // Value Area High (70% of volume above this)
    valueAreaLow: Double,    // Value Area Low (70% of volume below this)
    totalVolume: Long
) {
    def isAboveValueArea(price: Double): Boolean = price > valueAreaHigh
    def isBelowValueArea(price: Double): Boolean = price < valueAreaLow
    def isInValueArea(price: Double): Boolean = price >= valueAreaLow && price <= valueAreaHigh
    
    // Get volume distribution statistics
    def volumeDistribution: String = {
        val aboveVA = levels.filter(_.price > valueAreaHigh).map(_.volume).sum
        val belowVA = levels.filter(_.price < valueAreaLow).map(_.volume).sum
        val inVA = totalVolume - aboveVA - belowVA
        
        s"Above VA: ${(aboveVA.toDouble / totalVolume * 100).round}%, " +
        s"In VA: ${(inVA.toDouble / totalVolume * 100).round}%, " +
        s"Below VA: ${(belowVA.toDouble / totalVolume * 100).round}%"
    }
}

// Case class to hold VWAP analysis results
case class VWAPAnalysis(
    vwap: Double,
    standardDeviationBands: List[Double], // Usually 1σ, 2σ, 3σ
    currentPriceVsVWAP: String, // "Above", "Below", "At"
    vwapSlope: Double, // Positive = upward trending, Negative = downward trending
    volumeWeightedPrice: Double // Current period's volume-weighted price
) {
    def isAboveVWAP(price: Double): Boolean = price > vwap
    def isBelowVWAP(price: Double): Boolean = price < vwap
    def getStandardDeviationLevel(price: Double): Int = {
        val distance = math.abs(price - vwap)
        standardDeviationBands.zipWithIndex.find { case (band, _) => distance <= band } match {
            case Some((_, index)) => index + 1
            case None => standardDeviationBands.length + 1
        }
    }
}

// Case class to hold complete volume analysis results
case class VolumeAnalysis(
    volumeProfile: VolumeProfile,
    vwap: VWAPAnalysis,
    relativeVolume: Double,          // Current vs average volume
    volumeTrend: String,             // "Increasing", "Decreasing", "Stable"
    onBalanceVolume: Double,         // OBV indicator
    volumePriceTrend: Double         // VPT indicator
) {
    def isHighVolume: Boolean = relativeVolume > 1.5
    def isLowVolume: Boolean = relativeVolume < 0.5
    def isVolumeIncreasing: Boolean = volumeTrend == "Increasing"
    
    // Overall volume assessment for trading decisions
    def volumeQuality: String = {
        if (isHighVolume && isVolumeIncreasing) "Excellent"
        else if (isHighVolume || isVolumeIncreasing) "Good"
        else if (isLowVolume) "Poor"
        else "Fair"
    }
}

class Volume() {
    
    // Build volume profile with multiple distribution estimation techniques
    def buildVolumeProfile(candles: List[Candle], priceLevels: Int = 50, tickSize: Double = 0.25): VolumeProfile = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        require(priceLevels > 0, "Price levels must be positive")
        
        if (candles.size < 2) {
            // Handle single candle case
            val candle = candles.head
            val price = (candle.high + candle.low + candle.close).toDouble / 3.0
            val level = VolumeProfileLevel(price, candle.volume, 100.0)
            return VolumeProfile(
                levels = List(level),
                pocPrice = price,
                pocVolume = candle.volume,
                valueAreaHigh = candle.high.toDouble,
                valueAreaLow = candle.low.toDouble,
                totalVolume = candle.volume
            )
        }
        
        // Determine price range and create levels
        val allPrices = candles.flatMap(c => List(c.high, c.low, c.open, c.close))
        val minPrice = allPrices.min.toDouble
        val maxPrice = allPrices.max.toDouble
        val priceStep = (maxPrice - minPrice) / priceLevels
        
        // Initialize volume distribution map
        val volumeDistribution = mutable.Map[Double, Long]()
        
        // Distribute volume for each candle
        candles.foreach { candle =>
            val distributedVolume = distributeVolumeInCandle(candle, minPrice, maxPrice, priceStep)
            distributedVolume.foreach { case (price, volume) =>
                volumeDistribution(price) = volumeDistribution.getOrElse(price, 0L) + volume
            }
        }
        
        // Convert to sorted list and calculate percentages
        val totalVolume = volumeDistribution.values.sum
        val levels = volumeDistribution.toList.sortBy(_._1).map { case (price, volume) =>
            VolumeProfileLevel(price, volume, volume.toDouble / totalVolume * 100.0)
        }
        
        // Find POC (Point of Control)
        val pocLevel = levels.maxBy(_.volume)
        
        // Calculate Value Area (70% of total volume around POC)
        val valueArea = calculateValueArea(levels, totalVolume)
        
        VolumeProfile(
            levels = levels,
            pocPrice = pocLevel.price,
            pocVolume = pocLevel.volume,
            valueAreaHigh = valueArea._1,
            valueAreaLow = valueArea._2,
            totalVolume = totalVolume
        )
    }
    
    // Distribute volume within a candle using multiple techniques
    private def distributeVolumeInCandle(candle: Candle, minPrice: Double, maxPrice: Double, priceStep: Double): Map[Double, Long] = {
        val distribution = mutable.Map[Double, Long]()
        
        // Technique 1: Weighted distribution based on candle structure
        val typicalPrice = (candle.high + candle.low + candle.close).toDouble / 3.0
        val candleRange = candle.high - candle.low
        
        // Create price levels within this candle's range
        val candleLow = candle.low.toDouble
        val candleHigh = candle.high.toDouble
        val numLevelsInCandle = math.max(1, ((candleHigh - candleLow) / priceStep).toInt)
        
        if (numLevelsInCandle == 1) {
            // Single price level - put all volume at typical price
            val level = math.round(typicalPrice / priceStep) * priceStep
            distribution(level) = candle.volume
        } else {
            // Multiple levels - use weighted distribution
            val levelStep = (candleHigh - candleLow) / numLevelsInCandle
            
            for (i <- 0 to numLevelsInCandle) {
                val levelPrice = candleLow + (i * levelStep)
                val roundedPrice = math.round(levelPrice / priceStep) * priceStep
                
                // Weight distribution: more volume near typical price and close
                val distanceFromTypical = math.abs(levelPrice - typicalPrice)
                val distanceFromClose = math.abs(levelPrice - candle.close)
                val weight = calculateVolumeWeight(distanceFromTypical, distanceFromClose, candleRange)
                
                val volumeAtLevel = (candle.volume * weight).toLong
                distribution(roundedPrice) = distribution.getOrElse(roundedPrice, 0L) + volumeAtLevel
            }
            
            // Ensure all volume is distributed (handle rounding)
            val distributedVolume = distribution.values.sum
            if (distributedVolume != candle.volume) {
                val adjustment = candle.volume - distributedVolume
                val pocInCandle = distribution.maxBy(_._2)._1
                distribution(pocInCandle) = distribution(pocInCandle) + adjustment
            }
        }
        
        distribution.toMap
    }
    
    // Calculate volume weight for price level distribution
    private def calculateVolumeWeight(distanceFromTypical: Double, distanceFromClose: Double, candleRange: Double): Double = {
        if (candleRange == 0) return 1.0
        
        // Higher weight for prices near typical price and close
        val typicalWeight = 1.0 - (distanceFromTypical / (candleRange + 0.01))
        val closeWeight = 1.0 - (distanceFromClose / (candleRange + 0.01))
        
        // Combine weights with bias toward close price (where candle finished)
        val combinedWeight = (typicalWeight * 0.3) + (closeWeight * 0.7)
        math.max(combinedWeight, 0.1) // Minimum weight to avoid zero volume levels
    }
    
    // Calculate Value Area (70% of volume around POC)
    private def calculateValueArea(levels: List[VolumeProfileLevel], totalVolume: Long): (Double, Double) = {
        val sortedByVolume = levels.sortBy(-_.volume)
        val targetVolume = totalVolume * 0.70
        
        var accumulatedVolume = 0L
        var selectedLevels = List[VolumeProfileLevel]()
        
        for (level <- sortedByVolume if accumulatedVolume < targetVolume) {
            selectedLevels = level :: selectedLevels
            accumulatedVolume += level.volume
        }
        
        if (selectedLevels.nonEmpty) {
            val prices = selectedLevels.map(_.price)
            (prices.max, prices.min)
        } else {
            val allPrices = levels.map(_.price)
            (allPrices.max, allPrices.min)
        }
    }
    
    // Calculate VWAP (Volume Weighted Average Price)
    def calculateVWAP(candles: List[Candle], standardDeviations: List[Int] = List(1, 2, 3)): VWAPAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        if (candles.size == 1) {
            val candle = candles.head
            val price = (candle.high + candle.low + candle.close).toDouble / 3.0
            return VWAPAnalysis(
                vwap = price,
                standardDeviationBands = List(0.5, 1.0, 1.5),
                currentPriceVsVWAP = "At",
                vwapSlope = 0.0,
                volumeWeightedPrice = price
            )
        }
        
        // Calculate cumulative volume-weighted price
        var cumulativePV = 0.0 // Price * Volume
        var cumulativeVolume = 0L
        var cumulativePV2 = 0.0 // Price^2 * Volume for std dev
        
        val vwapProgression = mutable.ListBuffer[Double]()
        
        candles.foreach { candle =>
            val typicalPrice = (candle.high + candle.low + candle.close).toDouble / 3.0
            val volume = candle.volume
            
            cumulativePV += typicalPrice * volume
            cumulativeVolume += volume
            cumulativePV2 += typicalPrice * typicalPrice * volume
            
            val currentVWAP = if (cumulativeVolume > 0) cumulativePV / cumulativeVolume else typicalPrice
            vwapProgression += currentVWAP
        }
        
        val finalVWAP = vwapProgression.last
        
        // Calculate standard deviation
        val variance = if (cumulativeVolume > 0) {
            (cumulativePV2 / cumulativeVolume) - (finalVWAP * finalVWAP)
        } else 0.0
        val stdDev = math.sqrt(math.max(variance, 0.0))
        
        // Generate standard deviation bands
        val bands = standardDeviations.map(_ * stdDev)
        
        // Calculate VWAP slope (trend)
        val slope = if (vwapProgression.size >= 2) {
            val recent = vwapProgression.takeRight(5)
            if (recent.size >= 2) (recent.last - recent.head) / recent.size else 0.0
        } else 0.0
        
        // Current price vs VWAP
        val currentPrice = candles.last.close.toDouble
        val priceVsVWAP = if (currentPrice > finalVWAP + 0.01) "Above"
                         else if (currentPrice < finalVWAP - 0.01) "Below"
                         else "At"
        
        // Current period volume-weighted price
        val lastCandle = candles.last
        val currentVolumeWeightedPrice = (lastCandle.high + lastCandle.low + lastCandle.close).toDouble / 3.0
        
        VWAPAnalysis(
            vwap = finalVWAP,
            standardDeviationBands = bands,
            currentPriceVsVWAP = priceVsVWAP,
            vwapSlope = slope,
            volumeWeightedPrice = currentVolumeWeightedPrice
        )
    }
    
    // Calculate On Balance Volume (OBV)
    def calculateOBV(candles: List[Candle]): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        if (candles.size < 2) return candles.head.volume.toDouble
        
        var obv = 0.0
        for (i <- 1 until candles.length) {
            val current = candles(i)
            val previous = candles(i - 1)
            
            if (current.close > previous.close) {
                obv += current.volume
            } else if (current.close < previous.close) {
                obv -= current.volume
            }
            // If close == previous.close, OBV doesn't change
        }
        
        obv
    }
    
    // Calculate Volume Price Trend (VPT)
    def calculateVPT(candles: List[Candle]): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        if (candles.size < 2) return 0.0
        
        var vpt = 0.0
        for (i <- 1 until candles.length) {
            val current = candles(i)
            val previous = candles(i - 1)
            
            if (previous.close != 0) {
                val priceChange = (current.close - previous.close) / previous.close
                vpt += current.volume * priceChange
            }
        }
        
        vpt
    }
    
    // Calculate relative volume (current vs average)
    def calculateRelativeVolume(candles: List[Candle], lookbackPeriod: Int = 20): Double = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        if (candles.size < 2) return 1.0
        
        val currentVolume = candles.last.volume.toDouble
        val historicalCandles = candles.dropRight(1).takeRight(lookbackPeriod)
        
        if (historicalCandles.nonEmpty) {
            val avgVolume = historicalCandles.map(_.volume.toDouble).sum / historicalCandles.size
            if (avgVolume > 0) currentVolume / avgVolume else 1.0
        } else 1.0
    }
    
    // Determine volume trend
    def calculateVolumeTrend(candles: List[Candle], periods: Int = 5): String = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        if (candles.size < periods) return "Stable"
        
        val recentVolumes = candles.takeRight(periods).map(_.volume.toDouble)
        val firstHalf = recentVolumes.take(periods / 2)
        val secondHalf = recentVolumes.drop(periods / 2)
        
        val firstAvg = firstHalf.sum / firstHalf.size
        val secondAvg = secondHalf.sum / secondHalf.size
        
        val changeRatio = secondAvg / firstAvg
        
        if (changeRatio > 1.2) "Increasing"
        else if (changeRatio < 0.8) "Decreasing"
        else "Stable"
    }
    
    // Main volume analysis function - optimized for real-time updates
    def doVolumeAnalysis(
        candles: List[Candle], 
        existingAnalysis: Option[VolumeAnalysis] = None,
        newCandleCount: Int = 0,
        priceLevels: Int = 30, 
        tickSize: Double = 0.25
    ): VolumeAnalysis = {
        require(candles.nonEmpty, "Candles list cannot be empty")
        
        // Determine appropriate window sizes based on data frequency
        val isHighFrequency = candles.size > 100 && candles.last.timestamp - candles.head.timestamp < 600000 // 10 minutes
        
        val (profileWindow, vwapWindow, relativeVolumeWindow) = if (isHighFrequency) {
            // 1-second candles: use smaller windows for performance
            (300, 1800, 100) // 5 min, 30 min, ~1.5 min
        } else {
            // 1-minute+ candles: use full dataset
            (candles.size, candles.size, math.min(candles.size, 20))
        }
        
        val volumeProfile = buildVolumeProfile(candles.takeRight(profileWindow), priceLevels, tickSize)
        val vwap = calculateVWAP(candles.takeRight(vwapWindow))
        val relativeVolume = calculateRelativeVolume(candles, relativeVolumeWindow)
        val volumeTrend = calculateVolumeTrend(candles.takeRight(math.min(30, candles.size)))
        
        // Use incremental calculation when possible for performance
        val (obv, vpt) = if (existingAnalysis.isDefined && newCandleCount > 0 && newCandleCount <= 10) {
            val existing = existingAnalysis.get
            val newCandles = candles.takeRight(newCandleCount)
            val lastPreviousCandle = candles(candles.size - newCandleCount - 1)
            
            val obvIncrement = calculateOBVIncrement(newCandles, lastPreviousCandle)
            val vptIncrement = calculateVPTIncrement(newCandles, lastPreviousCandle)
            
            (existing.onBalanceVolume + obvIncrement, existing.volumePriceTrend + vptIncrement)
        } else {
            // Full calculation for initial run or large updates
            val window = math.min(candles.size, 200) // Limit for performance
            (calculateOBV(candles.takeRight(window)), calculateVPT(candles.takeRight(window)))
        }
        
        VolumeAnalysis(
            volumeProfile = volumeProfile,
            vwap = vwap,
            relativeVolume = relativeVolume,
            volumeTrend = volumeTrend,
            onBalanceVolume = obv,
            volumePriceTrend = vpt
        )
    }
    
    // Calculate volume-based trading signal score (0.0 to 1.0)
    def volumeTradingSignal(recentVolumeAnalysis: List[VolumeAnalysis], currentPrice: Double): Double = {
        require(recentVolumeAnalysis.nonEmpty, "Volume analysis list cannot be empty")
        
        val current = recentVolumeAnalysis.last
        var score = 0.0
        var maxPossibleScore = 0.0
        
        // 1. Volume confirmation signals - Weight: 0.4
        if (current.isHighVolume) score += 0.2
        if (current.isVolumeIncreasing) score += 0.2
        maxPossibleScore += 0.4
        
        // 2. VWAP analysis - Weight: 0.3
        if (current.vwap.isAboveVWAP(currentPrice) && current.vwap.vwapSlope > 0) {
            score += 0.15 // Price above rising VWAP
        } else if (current.vwap.isBelowVWAP(currentPrice) && current.vwap.vwapSlope < 0) {
            score += 0.10 // Price below falling VWAP (potential reversal)
        }
        
        // Standard deviation level analysis
        val sdLevel = current.vwap.getStandardDeviationLevel(currentPrice)
        if (sdLevel <= 1) score += 0.15 // Price within 1 standard deviation
        else if (sdLevel == 2) score += 0.05 // Price at 2 standard deviations
        
        maxPossibleScore += 0.3
        
        // 3. Volume profile analysis - Weight: 0.2
        val vp = current.volumeProfile
        if (vp.isInValueArea(currentPrice)) {
            score += 0.1 // Price in value area
        }
        if (math.abs(currentPrice - vp.pocPrice) / vp.pocPrice < 0.005) {
            score += 0.1 // Price near POC (within 0.5%)
        }
        maxPossibleScore += 0.2
        
        // 4. Volume indicators - Weight: 0.1
        if (recentVolumeAnalysis.size >= 2) {
            val previous = recentVolumeAnalysis(recentVolumeAnalysis.size - 2)
            if (current.onBalanceVolume > previous.onBalanceVolume) score += 0.05
            if (current.volumePriceTrend > previous.volumePriceTrend) score += 0.05
        }
        maxPossibleScore += 0.1
        
        // Normalize the score
        if (maxPossibleScore > 0) score / maxPossibleScore else 0.0
    }
    
    // Incremental OBV calculation (used internally by doVolumeAnalysis)
    private def calculateOBVIncrement(newCandles: List[Candle], lastPreviousCandle: Candle): Double = {
        var obvIncrement = 0.0
        var previousCandle = lastPreviousCandle
        
        newCandles.foreach { current =>
            if (current.close > previousCandle.close) {
                obvIncrement += current.volume
            } else if (current.close < previousCandle.close) {
                obvIncrement -= current.volume
            }
            previousCandle = current
        }
        
        obvIncrement
    }
    
    // Incremental VPT calculation (used internally by doVolumeAnalysis)
    private def calculateVPTIncrement(newCandles: List[Candle], lastPreviousCandle: Candle): Double = {
        var vptIncrement = 0.0
        var previousCandle = lastPreviousCandle
        
        newCandles.foreach { current =>
            if (previousCandle.close != 0) {
                val priceChange = (current.close - previousCandle.close) / previousCandle.close
                vptIncrement += current.volume * priceChange
            }
            previousCandle = current
        }
        
        vptIncrement
    }
}


