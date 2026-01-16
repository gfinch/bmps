# Advanced Adaptive Multi-Regime OrderService

## Implementation Summary

A sophisticated trading order service that dynamically adapts to market conditions, focuses on quality over quantity, and leverages previously unused analytical features.

## Files Created

### 1. Core Analysis Components

#### `/core/src/main/scala/bmps/core/services/analysis/RegimeDetection.scala`
- **Purpose**: Detects current market regime (Trending High/Low, Ranging Tight/Wide, Breakout)
- **Key Features**:
  - Classifies markets using ADX, volatility, and volume
  - Calculates confidence scores (0-1)
  - Detects regime transitions
  - Returns 0-100 scores for trend, volatility, and volume strength

#### `/core/src/main/scala/bmps/core/services/analysis/VolumeConfluence.scala`
- **Purpose**: Analyzes volume patterns for trade confirmation (previously unused!)
- **Key Features**:
  - Detects volume spikes (>1.5x and >2x average)
  - Identifies bullish/bearish divergence
  - Detects institutional accumulation/distribution
  - Calculates volume confirmation score (0-1)

### 2. Rules & Scoring

#### `/core/src/main/scala/bmps/core/services/rules/SignalScoring.scala`
- **Purpose**: Scores potential trade setups (0-100) to filter high-probability trades
- **Scoring Breakdown**:
  - Trend Alignment: 0-20 points (ADX + direction + MA cross)
  - Volume Confirmation: 0-20 points (spikes + accumulation/distribution)
  - Momentum Convergence: 0-20 points (RSI + Stochastics + Williams%R + CCI)
  - Volatility Context: 0-20 points (ATR + Bollinger Bands + Keltner)
  - Regime Appropriate: 0-20 points (confidence + regime fit)
- **Threshold**: Only trades signals scoring 75+

#### `/core/src/main/scala/bmps/core/services/rules/MultiTimeframeRules.scala`
- **Purpose**: Multi-timeframe analysis for confluence checking
- **Key Features**:
  - Aggregates 1m candles into 5m and 15m timeframes
  - Checks trend alignment across timeframes
  - Checks momentum alignment across timeframes
  - Calculates multi-timeframe confidence (0-1)

### 3. Main Order Service

#### `/core/src/main/scala/bmps/core/services/AdaptiveMultiRegimeOrderService.scala`
- **Purpose**: Main order service with adaptive strategy selection
- **Constructor Parameters**:
  - `accountBalance: Double` - Used for Kelly criterion position sizing
  - `minSignalScore: Int = 75` - Minimum score required to trade

## Strategy Logic

### Trending Markets (TrendingHigh/TrendingLow)
- **Entry**: Golden/Death cross + momentum convergence + volume confirmation
- **Filters**:
  - Recent MA cross (within 3 minutes)
  - RSI not extreme (25-65 for long, 35-75 for short)
  - Volume confirmation > 0.5
  - Signal score >= 75
- **Stops**: 2.0 ATR
- **Target**: 2.5R

### Breakout Markets
- **Entry**: BB squeeze breakout + massive volume spike
- **Filters**:
  - Bollinger Band squeeze active
  - Volume spike > 2x average
  - Clear directional bias from momentum
  - Signal score >= 85 (excellent only!)
- **Stops**: 2.5 ATR (wider for volatility expansion)
- **Target**: 2.5R

### Ranging Markets (RangingTight)
- **Entry**: BB extreme + momentum extreme + low ADX
- **Filters**:
  - ADX < 25 (weak trend)
  - Price at BB extreme (< 20% or > 80%)
  - RSI oversold/overbought
  - Stochastics + Williams%R convergence
  - Signal score >= 75
- **Stops**: 1.5 ATR (tighter for ranging)
- **Target**: 2.5R

### Avoided Conditions
- **RangingWide**: Too choppy, no strategy
- **Unknown Regime**: Insufficient data
- **10am-12pm**: Historically poor performance window
- **+6R Daily Profit**: Lock in gains, stop trading
- **Near Market Close**: No new positions

## Key Improvements Over Existing Services

### 1. Volume Analysis (Previously Unused!)
- VolumeAnalysis was calculated but never used in order decisions
- Now central to confirmation: spikes, divergence, accumulation/distribution
- Adds 20 points to signal score

### 2. Full Momentum Convergence
- Old services used only RSI
- Now uses RSI + Stochastics + Williams%R + CCI
- All four must converge for maximum score

### 3. Regime-Adaptive Strategies
- Old services used fixed rules regardless of market state
- Now switches between trend-following, breakout, and mean-reversion
- Avoids choppy markets entirely

### 4. Comprehensive Scoring
- Old services had binary yes/no decisions
- Now scores every setup 0-100 across 5 dimensions
- Only trades top 25% (score >= 75)

### 5. Better Time Filtering
- Maintains 10am-12pm avoidance (proven effective)
- Adds +6R daily profit cap
- Includes near-close protection

## Integration Example

```scala
// In your OrderService selection logic:
val adaptiveService = new AdaptiveMultiRegimeOrderService(
  accountBalance = leadAccount.accountBalance.getOrElse(50000.0),
  minSignalScore = 75  // Can adjust: 75=good, 80=very good, 85=excellent only
)

// Process state
val updatedState = adaptiveService.processOneMinuteState(state)
```

## Expected Performance Improvements

### Win Rate Target
- **Current**: 40-55% (TechnicalAnalysisOrderService best scenarios)
- **Target**: 60-70% (through stricter filtering and multi-factor confirmation)

### Drawdown Target
- **Previous**: $25k-40k
- **Target**: $15k or less (through regime detection and quality filtering)

### Trade Frequency
- **Expected**: Lower than existing services (quality over quantity)
- **Benefit**: Fewer drawdown-inducing losses, higher confidence per trade

## Testing Recommendations

1. **Baseline Test**: Run against 2023-2025 dataset with existing services
2. **Adaptive Test**: Run AdaptiveMultiRegimeOrderService with same data
3. **Score Sensitivity**: Test with minSignalScore = 75, 80, 85 to find optimal balance
4. **Regime Analysis**: Log regime classifications to validate detection accuracy
5. **Volume Impact**: Compare performance with/without volume signals

## Configuration Options

```scala
// Conservative (fewer trades, highest quality)
new AdaptiveMultiRegimeOrderService(accountBalance, minSignalScore = 85)

// Balanced (recommended starting point)
new AdaptiveMultiRegimeOrderService(accountBalance, minSignalScore = 75)

// Aggressive (more trades, still filtered)
new AdaptiveMultiRegimeOrderService(accountBalance, minSignalScore = 70)
```

## Monitoring & Debugging

The service includes detailed tracking:
- Order `entryType` shows: `Adaptive-[Strategy]-Score[XX]`
  - Example: `Adaptive-TrendRiding-Score82`
  - Helps identify which strategies and score ranges perform best

## Next Steps

1. Add to your OrderService factory/selection logic
2. Run backtests with 2023-2025 data
3. Compare metrics: win rate, max drawdown, total profit, # of trades
4. Adjust `minSignalScore` threshold based on results
5. Consider running in parallel with existing services for A/B testing

## Dependencies

All dependencies are on existing code:
- Uses existing TrendAnalysis, MomentumAnalysis, VolumeAnalysis, VolatilityAnalysis
- Extends existing RiskSizingRules (uses computeRiskMultiplierKelly)
- Uses existing Order, OrderType, EntryType models
- No external libraries required

---

**Ready to test!** All code is pure Scala, follows existing patterns, and integrates seamlessly with your current architecture.
