package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.services.analysis.Trend
import bmps.core.services.analysis.Momentum
import bmps.core.services.analysis.Volume
import bmps.core.services.analysis.Volatility
import bmps.core.models.OrderType
import bmps.core.models.Order
import bmps.core.models.EntryType
import bmps.core.brokers.rest.OrderState
import bmps.core.models.OrderStatus
import bmps.core.utils.MarketCalendar
import bmps.core.utils.TimestampUtils

class TechnicalAnalysisService(trend: Trend = new Trend(),
                               momentum: Momentum = new Momentum(),
                               volume: Volume = new Volume(),
                               volatility: Volatility = new Volatility()) {

    var bestSignal: Option[BuyingSignal] = None

    def processOneSecondState(state: SystemState): SystemState = {
        val lastCandle = state.recentOneSecondCandles.last
        val secondsInTimestamp = (lastCandle.timestamp / 1000) % 60 // Extract seconds from Unix timestamp
        
        if (secondsInTimestamp % 15 == 0) {
            // Process the candle at 0, 15, 30, or 45 seconds
            // Volume analysis works best with 1-second candles for detailed volume profile
            val volumeAnalysis = volume.doVolumeAnalysis(
                candles = state.recentOneSecondCandles,
                existingAnalysis = state.recentVolumeAnalysis.lastOption,
                newCandleCount = 15 // 15 seconds worth of new data
            )
            
            // Update system state with new volume analysis
            state.copy(
                recentVolumeAnalysis = state.recentVolumeAnalysis.take(19) :+ volumeAnalysis // Keep last 20 analyses
            )
        } else {
            state
        }
    }

    def processOneMinuteState(state: SystemState): SystemState = {
        val lastCandle = state.tradingCandles.last

        // Perform trend analysis on 1-minute candles
        val trendAnalysis = trend.doTrendAnalysis(state.tradingCandles)
        
        // Perform momentum analysis on 1-minute candles
        val momentumAnalysis = momentum.doMomentumAnalysis(state.tradingCandles)
        
        // Perform volatility analysis on 1-minute candles
        val volatilityAnalysis = volatility.doVolatilityAnalysis(state.tradingCandles)
        
        // Update system state with all new analyses
        val recentTrendAnalysis = state.recentTrendAnalysis.take(19) :+ trendAnalysis
        val recentMomentumAnalysis = state.recentMomentumAnalysis.take(19) :+ momentumAnalysis
        val recentVolatilityAnalysis = state.recentVolatilityAnalysis.take(19) :+ volatilityAnalysis

        val stateWithAnalysis = state.copy(
            recentTrendAnalysis = recentTrendAnalysis,
            recentMomentumAnalysis = recentMomentumAnalysis,
            recentVolatilityAnalysis = recentVolatilityAnalysis
        )
        
        if (stateWithAnalysis.orders.exists(_.isActive) || TimestampUtils.isNearTradingClose(lastCandle.timestamp)) stateWithAnalysis
        else buildOrders(stateWithAnalysis)
    }

    private def buildOrders(state: SystemState): SystemState = {
        val lastCandle = state.tradingCandles.last
        val scoreTrend = trend.trendReversalSignal(state.recentTrendAnalysis, lastCandle.close)
        val scoreMomentum = momentum.buyingOpportunityScore(state.recentMomentumAnalysis)
        val scoreVolume = volume.volumeTradingSignal(state.recentVolumeAnalysis, lastCandle.close)
        val scoreVolatility = volatility.volatilityTradingSignal(state.recentVolatilityAnalysis, lastCandle.close)

        // Calculate composite buying signal
        val buyingSignal = calculateBuyingSignal(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility, state)
        
        // Update best signal if this one is stronger
        // bestSignal match {
        //     case None => bestSignal = Some(buyingSignal)
        //     case Some(current) => 
        //         if (buyingSignal.strength * buyingSignal.confidence > current.strength * current.confidence) {
        //             println(buyingSignal)
        //             bestSignal = Some(buyingSignal)
        //         }
        // }
        
        if (Set("STRONG_BUY", "BUY", "WEAK_BUY").contains(buyingSignal.recommendation)) {
            val newOrder = buyingSignal.direction match {
                case OrderType.Long => 
                    val low = roundToNearestQuarter(buyingSignal.stopLoss).toFloat
                    val entry = roundToNearestQuarter(buyingSignal.entryPrice).toFloat
                    Order(low, entry, lastCandle.timestamp, buyingSignal.direction,
                        EntryType.TechnicalAnalysisOrderBlock, state.contractSymbol.get, status = OrderStatus.PlaceNow)
                case OrderType.Short => 
                    val low = roundToNearestQuarter(buyingSignal.entryPrice).toFloat
                    val entry = roundToNearestQuarter(buyingSignal.stopLoss).toFloat
                    Order(low, entry, lastCandle.timestamp, buyingSignal.direction,
                        EntryType.TechnicalAnalysisOrderBlock, state.contractSymbol.get, status = OrderStatus.PlaceNow)
            }
            state.copy(orders = state.orders :+ newOrder)
        } else state
    }

    def roundToNearestQuarter(value: Double): Double = {
        (math.round(value * 4.0) / 4.0)
    }
    
    // Case class to hold buying decision results
    case class BuyingSignal(
        strength: Double,      // 0.0-1.0: Signal strength
        confidence: Double,    // 0.0-1.0: Confidence in signal
        recommendation: String, // Action recommendation
        direction: OrderType,     // "LONG" or "SHORT"
        entryPrice: Double,    // Suggested entry price
        stopLoss: Double,      // Calculated stop loss price
        atrStopDistance: Double, // ATR-based stop distance
        breakdown: Map[String, Double], // Individual score contributions
        reasoning: List[String] // Human-readable reasoning
    ) {
       override def toString(): String = {
            s"""
                |╔═══════════════════════════════════════════════════════════════════════════════╗
                |║                           BUYING SIGNAL ANALYSIS                              ║
                |╠═══════════════════════════════════════════════════════════════════════════════╣
                |║ Recommendation: ${recommendation.padTo(20, ' ')}                              ║
                |║ Direction:      ${direction.toString.padTo(20, ' ')}                          ║
                |║ Signal Strength: ${f"$strength%.2f".padTo(19, ' ')} Confidence: ${f"$confidence%.2f"}  ║
                |╠═══════════════════════════════════════════════════════════════════════════════╣
                |║ Entry Price:    $$${f"$entryPrice%.2f".padTo(18, ' ')}                        ║
                |║ Stop Loss:      $$${f"$stopLoss%.2f".padTo(18, ' ')}                          ║
                |║ ATR Distance:   $$${f"$atrStopDistance%.2f".padTo(18, ' ')}                   ║
                |╠═══════════════════════════════════════════════════════════════════════════════╣
                |║ COMPONENT BREAKDOWN:                                                          ║
                |║   Trend:        ${f"${breakdown("trend")}%.2f".padTo(20, ' ')}                ║
                |║   Momentum:     ${f"${breakdown("momentum")}%.2f".padTo(20, ' ')}             ║
                |║   Volume:       ${f"${breakdown("volume")}%.2f".padTo(20, ' ')}               ║
                |║   Volatility:   ${f"${breakdown("volatility")}%.2f".padTo(20, ' ')}           ║
                |║   Composite:    ${f"${breakdown("composite")}%.2f".padTo(20, ' ')}            ║
                |║   Adjusted:     ${f"${breakdown("adjusted")}%.2f".padTo(20, ' ')}             ║
                |╠═══════════════════════════════════════════════════════════════════════════════╣
                |║ REASONING:                                                                    ║
                |${reasoning.map(r => s"║   • $r".padTo(80, ' ') + "║").mkString("\n")}
                |╚═══════════════════════════════════════════════════════════════════════════════╝
                |""".stripMargin
        }
    }
    
    private def calculateBuyingSignal(scoreTrend: Double, scoreMomentum: Double, 
                                    scoreVolume: Double, scoreVolatility: Double,
                                    state: SystemState): BuyingSignal = {
        
        // 1. ADAPTIVE WEIGHTING based on market conditions
        val weights = calculateAdaptiveWeights(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility, state)
        
        // 2. WEIGHTED COMPOSITE SCORE
        val compositeScore = (
            scoreTrend * weights("trend") +
            scoreMomentum * weights("momentum") + 
            scoreVolume * weights("volume") +
            scoreVolatility * weights("volatility")
        )
        
        // 3. CONFLUENCE ANALYSIS (how many signals agree) - Less punitive
        val strongSignals = List(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility).count(_ >= 0.6)
        val confluenceFactor = strongSignals match {
            case 4 => 1.2  // All agree - boost signal
            case 3 => 1.15 // Strong majority - better boost
            case 2 => 1.05 // Split decision - small boost
            case 1 => 0.95 // Weak consensus - small reduction
            case 0 => 0.85 // No strong signals - moderate reduction (was 0.5)
        }
        
        // 4. RISK ADJUSTMENT based on market volatility
        val currentVolatility = state.recentVolatilityAnalysis.lastOption
            .map(_.overallVolatility).getOrElse("Medium")
        val riskAdjustment = currentVolatility match {
            case "Low" => 1.1    // Low vol = higher confidence
            case "Medium" => 1.0 // Normal conditions
            case "High" => 0.8   // High vol = reduce confidence
            case _ => 1.0
        }
        
        // 5. DIRECTION ANALYSIS
        val direction = determineDirection(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility, state)
        
        // 6. ENTRY PRICE & STOP LOSS CALCULATION
        val lastCandle = state.tradingCandles.last
        val entryPrice = lastCandle.close.toDouble
        val (stopLoss, atrDistance) = calculateStopLoss(entryPrice, direction, state)
        
        // 7. FINAL CALCULATIONS
        val adjustedScore = math.min(compositeScore * confluenceFactor * riskAdjustment, 1.0)
        val confidence = calculateConfidence(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility, strongSignals)
        val recommendation = determineRecommendation(adjustedScore, confidence)
        
        // 8. REASONING
        val reasoning = buildReasoning(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility, 
                                     strongSignals, currentVolatility, weights, direction, stopLoss)
        
        BuyingSignal(
            strength = adjustedScore,
            confidence = confidence,
            recommendation = recommendation,
            direction = direction,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            atrStopDistance = atrDistance,
            breakdown = Map(
                "trend" -> scoreTrend,
                "momentum" -> scoreMomentum,
                "volume" -> scoreVolume,
                "volatility" -> scoreVolatility,
                "composite" -> compositeScore,
                "adjusted" -> adjustedScore
            ),
            reasoning = reasoning
        )
    }
    
    private def calculateAdaptiveWeights(scoreTrend: Double, scoreMomentum: Double,
                                       scoreVolume: Double, scoreVolatility: Double,
                                       state: SystemState): Map[String, Double] = {
        
        // Base weights (default balanced approach)
        var trendWeight = 0.30     // Trend is king for direction
        var momentumWeight = 0.25  // Momentum for entry timing
        var volumeWeight = 0.25    // Volume for confirmation
        var volatilityWeight = 0.20 // Volatility for risk/timing
        
        // ADAPTIVE ADJUSTMENTS based on market conditions:
        
        // 1. In trending markets, increase trend weight
        if (scoreTrend >= 0.7) {
            trendWeight += 0.1
            momentumWeight -= 0.05
            volumeWeight -= 0.05
        }
        
        // 2. In choppy/sideways markets, favor momentum and volume more aggressively
        if (scoreTrend < 0.3) {
            momentumWeight += 0.15  // Increased boost
            volumeWeight += 0.15    // Increased boost  
            trendWeight -= 0.25     // More aggressive reduction
            volatilityWeight -= 0.05
        }
        
        // 3. In high volatility, increase volatility consideration
        val currentVolatility = state.recentVolatilityAnalysis.lastOption
            .map(_.overallVolatility).getOrElse("Medium")
        if (currentVolatility == "High") {
            volatilityWeight += 0.1
            trendWeight -= 0.05
            momentumWeight -= 0.05
        }
        
        // 4. When volume is exceptionally strong, boost its weight
        if (scoreVolume >= 0.8) {
            volumeWeight += 0.1
            trendWeight -= 0.05
            momentumWeight -= 0.05
        }
        
        // Normalize to ensure weights sum to 1.0
        val total = trendWeight + momentumWeight + volumeWeight + volatilityWeight
        Map(
            "trend" -> trendWeight / total,
            "momentum" -> momentumWeight / total,
            "volume" -> volumeWeight / total,
            "volatility" -> volatilityWeight / total
        )
    }
    
    private def calculateConfidence(scoreTrend: Double, scoreMomentum: Double,
                                  scoreVolume: Double, scoreVolatility: Double,
                                  strongSignals: Int): Double = {
        val scores = List(scoreTrend, scoreMomentum, scoreVolume, scoreVolatility)
        
        // Calculate standard deviation of scores (low = high confidence)
        val mean = scores.sum / scores.length
        val variance = scores.map(score => math.pow(score - mean, 2)).sum / scores.length
        val stdDev = math.sqrt(variance)
        
        // High confidence when:
        // 1. Low standard deviation (scores agree)
        // 2. Multiple strong signals
        // 3. Scores are not in middle range (decisive)
        
        val agreementFactor = 1.0 - stdDev // Higher when scores agree
        val strengthFactor = strongSignals / 4.0 // Higher with more strong signals
        val decisivenessFactor = scores.map(s => if (s < 0.3 || s > 0.7) 1.0 else 0.5).sum / 4.0
        
        val confidence = (agreementFactor * 0.4 + strengthFactor * 0.4 + decisivenessFactor * 0.2)
        math.min(math.max(confidence, 0.0), 1.0)
    }
    
    private def determineRecommendation(strength: Double, confidence: Double): String = {
        val combinedScore = strength * confidence
        
        combinedScore match {
            case s if s >= 0.65 => "STRONG_BUY"  // Lowered from 0.75
            case s if s >= 0.50 => "BUY"         // Lowered from 0.60  
            case s if s >= 0.35 => "WEAK_BUY"    // Lowered from 0.45
            case s if s >= 0.25 => "HOLD"        // Lowered from 0.30
            case _ => "AVOID"
        }
    }
    
    private def determineDirection(scoreTrend: Double, scoreMomentum: Double,
                                 scoreVolume: Double, scoreVolatility: Double,
                                 state: SystemState): OrderType = {
        // Analyze trend direction from recent trend analysis
        val recentTrend = state.recentTrendAnalysis.lastOption
        val trendDirection = recentTrend.map { t =>
            if (t.isUptrend) "Uptrend"
            else if (t.isDowntrend) "Downtrend"  
            else "Sideways"
        }.getOrElse("Unknown")
        
        // Get momentum bias (oversold favors long, overbought favors short)
        val recentMomentum = state.recentMomentumAnalysis.lastOption
        val momentumBias = recentMomentum.map { m =>
            if (m.rsiOversold || m.stochastics.isOversold || m.williamsROversold || m.cciOversold) "LONG"
            else if (m.rsiOverbought || m.stochastics.isOverbought || m.williamsROverbought || m.cciOverbought) "SHORT"
            else "NEUTRAL"
        }.getOrElse("NEUTRAL")
        
        // Primary decision based on trend direction and momentum
        (trendDirection, momentumBias) match {
            case ("Uptrend", "LONG") => OrderType.Long    // Strong uptrend + oversold = buy dip
            case ("Uptrend", _) => OrderType.Long         // Follow uptrend
            case ("Downtrend", "SHORT") => OrderType.Short // Strong downtrend + overbought = short rally
            case ("Downtrend", _) => OrderType.Short      // Follow downtrend
            case (_, "LONG") if scoreMomentum >= 0.6 => OrderType.Long // Strong momentum oversold signal
            case (_, "SHORT") if scoreMomentum >= 0.6 => OrderType.Short // Strong momentum overbought signal
            case _ => OrderType.Long // Default to long bias in uncertain conditions
        }
    }
    
    private def calculateStopLoss(entryPrice: Double, direction: OrderType, state: SystemState): (Double, Double) = {
        // Get ATR from volatility analysis
        val currentVolatility = state.recentVolatilityAnalysis.lastOption
        val atr = currentVolatility.map(_.trueRange.atr).getOrElse(entryPrice * 0.02) // Fallback to 2% if no ATR
        
        // Use 2 ATRs for stop loss distance (configurable)
        val atrMultiplier = 2.0
        val stopDistance = atr * atrMultiplier
        
        val stopLoss = direction match {
            case OrderType.Long => entryPrice - stopDistance  // Stop below entry for long
            case OrderType.Short => entryPrice + stopDistance // Stop above entry for short
            case _ => entryPrice - stopDistance       // Default to long
        }
        
        (stopLoss, stopDistance)
    }
    
    private def buildReasoning(scoreTrend: Double, scoreMomentum: Double, 
                             scoreVolume: Double, scoreVolatility: Double,
                             strongSignals: Int, volatility: String,
                             weights: Map[String, Double], direction: OrderType, 
                             stopLoss: Double): List[String] = {
        val reasons = scala.collection.mutable.ListBuffer[String]()
        
        // Analyze individual components
        if (scoreTrend >= 0.6) reasons += "Positive trend signals detected"
        else if (scoreTrend <= 0.3) reasons += "Weak trend conditions"
        
        if (scoreMomentum >= 0.6) reasons += "Favorable momentum indicators"
        else if (scoreMomentum <= 0.3) reasons += "Poor momentum conditions"
        
        if (scoreVolume >= 0.6) reasons += "Strong volume confirmation"
        else if (scoreVolume <= 0.3) reasons += "Weak volume support"
        
        if (scoreVolatility >= 0.6) reasons += "Volatility supports entry timing"
        else if (scoreVolatility <= 0.3) reasons += "Volatility conditions unfavorable"
        
        // Confluence analysis
        strongSignals match {
            case 4 => reasons += "All four pillars align - high conviction setup"
            case 3 => reasons += "Strong consensus across most indicators"
            case 2 => reasons += "Mixed signals - moderate confidence"
            case 1 => reasons += "Limited support from indicators"
            case 0 => reasons += "No strong supporting signals"
        }
        
        // Market condition context
        volatility match {
            case "High" => reasons += "High volatility environment - increased caution"
            case "Low" => reasons += "Low volatility supports stable entry"
            case _ => reasons += "Normal volatility conditions"
        }
        
        // Direction and risk management
        reasons += s"Recommended direction: $direction"
        reasons += f"Stop loss set at: $$${stopLoss}%.2f (ATR-based risk management)"
        
        reasons.toList
    }

}
