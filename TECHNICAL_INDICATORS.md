# Technical Analysis Indicators

This document describes all technical analysis indicators implemented in the BMPS trading system, what they signify, and how they are commonly used.

---

## Overview

The system organizes indicators into four primary categories:

| Category | Purpose | Examples |
|----------|---------|----------|
| **Trend** | Identify market direction and trend strength | Moving Averages, ADX, Golden/Death Cross |
| **Momentum** | Measure speed of price changes and overbought/oversold conditions | RSI, Stochastic, Williams %R, CCI |
| **Volatility** | Gauge market uncertainty and potential breakouts | ATR, Keltner Channels, Bollinger Bands |
| **Volume** | Confirm price moves with trading activity | VWAP, OBV, Volume Profile |

---

## Trend Indicators

### Simple Moving Average (SMA)
**What it is**: The average closing price over a specified period. Provides a smoothed view of price action by filtering out short-term noise.

**Common usage**: Baseline for trend direction. Price above SMA suggests uptrend; price below suggests downtrend. Often used as the foundation for other indicators.

---

### Exponential Moving Average (EMA)
**What it is**: A weighted moving average that gives more importance to recent prices, making it more responsive to current price action than SMA.

**Common usage**: Primary trend indicator. The system uses EMA(9) for short-term and EMA(21) for long-term trend analysis. Faster response helps identify trend changes earlier.

---

### Triangular Moving Average (TMA)
**What it is**: A double-smoothed moving average that applies SMA twice, creating an extra-smooth trend line with reduced noise.

**Common usage**: Long-term trend confirmation. Less reactive to short-term volatility, useful for identifying the broader market direction.

---

### DMI/ADX (Directional Movement Index / Average Directional Index)
**What it is**: Measures both trend direction (+DI/-DI) and trend strength (ADX). ADX quantifies how strong a trend is, regardless of direction.

| ADX Value | Interpretation |
|-----------|----------------|
| 0-20 | Weak or no trend (consolidation) |
| 20-25 | Emerging trend |
| 25-50 | Strong trend |
| 50-75 | Very strong trend |
| 75-100 | Extremely strong trend |

**Common usage**: 
- Filter trades: Only enter trending markets when ADX > 20-25
- Direction: +DI > -DI indicates uptrend; -DI > +DI indicates downtrend
- Avoid choppy markets when ADX is low

---

### Golden Cross / Death Cross
**What it is**: A crossover signal when two moving averages intersect.

| Signal | Condition | Meaning |
|--------|-----------|---------|
| **Golden Cross** | Short-term MA crosses above long-term MA | Bullish signal |
| **Death Cross** | Short-term MA crosses below long-term MA | Bearish signal |

**Common usage**: Primary entry signals. The crossover strength (distance between MAs relative to price) indicates conviction. Stronger crossovers with increasing ADX are more reliable.

---

### Trend Strength
**What it is**: A custom indicator measuring the spread between short and long-term moving averages, normalized by channel width (Keltner).

**Common usage**: Scaled 0-100, it quantifies momentum after MA crossovers. Higher values indicate stronger trend momentum. Used in ML models for signal confirmation.

---

## Momentum Indicators

### RSI (Relative Strength Index)
**What it is**: Measures the speed and magnitude of recent price changes on a scale of 0-100.

| RSI Value | Interpretation |
|-----------|----------------|
| > 70 | Overbought (potential reversal down) |
| 30-70 | Neutral zone |
| < 30 | Oversold (potential reversal up) |

**Common usage**: 
- Identify overbought/oversold conditions
- Divergence: Price makes new high but RSI doesn't = potential reversal
- Centerline crossovers (50) for trend confirmation

---

### Inverse RSI (iRSI)
**What it is**: Simply `100 - RSI`. Flips the RSI scale so that high values indicate oversold conditions.

**Common usage**: Used in the system to calculate intersection points with trend strength for long entry signals. When trend strength crosses above iRSI, it can signal long opportunities.

---

### Stochastic Oscillator (%K and %D)
**What it is**: Compares a closing price to its price range over a period. %K is the fast line; %D is the smoothed signal line.

| Value | Interpretation |
|-------|----------------|
| > 80 | Overbought |
| < 20 | Oversold |

**Common usage**:
- Bullish signal: %K crosses above %D in oversold zone
- Bearish signal: %K crosses below %D in overbought zone
- More effective in ranging markets

---

### Williams %R
**What it is**: Similar to Stochastic but inverted, measuring where the close is relative to the high-low range. Ranges from -100 to 0.

| Value | Interpretation |
|-------|----------------|
| > -20 | Overbought |
| < -80 | Oversold |

**Common usage**: Quick identification of momentum extremes. Often used alongside RSI for confirmation.

---

### CCI (Commodity Channel Index)
**What it is**: Measures the deviation of price from its statistical mean. Can range from -300 to +300 or beyond.

| Value | Interpretation |
|-------|----------------|
| > +100 | Overbought / strong uptrend |
| < -100 | Oversold / strong downtrend |

**Common usage**: Identify cyclical trends and extreme price levels. Useful for detecting when price has moved significantly from "normal" levels.

---

## Volatility Indicators

### ATR (Average True Range)
**What it is**: Measures market volatility by calculating the average range of price movement, accounting for gaps.

| Ratio vs Average | Interpretation |
|------------------|----------------|
| > 2.0 | Extreme volatility |
| > 1.5 | High volatility |
| < 0.5 | Low volatility |

**Common usage**:
- **Position sizing**: Larger ATR = smaller position
- **Stop loss placement**: Typically 1.5-2x ATR from entry
- **Take profit targets**: Often 2-3x ATR
- **Breakout confirmation**: Expanding ATR confirms real moves

---

### Keltner Channels
**What it is**: Volatility bands around a moving average, using ATR to set the band width.

| Component | Description |
|-----------|-------------|
| Center Line | SMA of close prices |
| Upper Band | Center + (ATR × multiplier) |
| Lower Band | Center - (ATR × multiplier) |

**Common usage**:
- **Breakouts**: Price closing outside channels signals potential trend start
- **Squeeze detection**: When Bollinger Bands move inside Keltner Channels, a squeeze is forming
- **Mean reversion**: Price tends to return to the center line
- **Exit signals**: Return to center after breakout can signal exit

---

### Bollinger Bands
**What it is**: Volatility bands using standard deviation around a moving average.

| Component | Description |
|-----------|-------------|
| Center Line | SMA of close prices |
| Upper Band | Center + (2 × standard deviation) |
| Lower Band | Center - (2 × standard deviation) |
| %B | Position within bands (0 = lower, 1 = upper) |
| Bandwidth | Band width as % of center |

**Key signals**:
- **Squeeze**: Bands narrowing (bandwidth < 10%) = volatility contraction, breakout imminent
- **Expansion**: Bands widening (bandwidth > 20%) = volatility expansion
- **%B > 0.8**: Overbought
- **%B < 0.2**: Oversold

**Common usage**: Squeeze patterns preceding breakouts are a primary entry signal. Bollinger Bands inside Keltner Channels indicate maximum compression.

---

### Standard Deviation Analysis
**What it is**: Statistical measure of price dispersion from the mean.

**Common usage**:
- **±1σ**: 68% of prices fall within this range
- **±2σ**: 95% of prices fall within this range
- Extreme moves beyond 2σ have high probability of reversal

---

## Volume Indicators

### Volume Profile
**What it is**: Displays trading activity at different price levels, showing where most volume occurred.

| Component | Description |
|-----------|-------------|
| POC (Point of Control) | Price level with highest volume |
| Value Area High | Upper boundary of 70% volume zone |
| Value Area Low | Lower boundary of 70% volume zone |

**Common usage**:
- POC acts as a magnet for price (support/resistance)
- Breakouts from value area can signal new trends
- High volume nodes = strong support/resistance

---

### VWAP (Volume Weighted Average Price)
**What it is**: The average price weighted by volume throughout the session. The "fair value" benchmark used by institutional traders.

**Common usage**:
- **Above VWAP**: Bullish bias
- **Below VWAP**: Bearish bias
- Institutions often execute around VWAP
- Standard deviation bands around VWAP indicate overextension

---

### OBV (On Balance Volume)
**What it is**: Cumulative indicator that adds volume on up days and subtracts volume on down days.

**Common usage**:
- **Confirmation**: OBV trending with price confirms the move
- **Divergence**: Price rising but OBV falling = potential reversal
- Rising OBV indicates accumulation; falling OBV indicates distribution

---

### VPT (Volume Price Trend)
**What it is**: Similar to OBV but weights volume by the percentage price change, not just direction.

**Common usage**: More nuanced than OBV. Large price moves with high volume have greater impact. Used to confirm trend strength.

---

### Relative Volume (RVOL)
**What it is**: Current volume compared to average historical volume.

| RVOL | Interpretation |
|------|----------------|
| > 1.5 | High volume (confirms moves) |
| 1.0 | Average volume |
| < 0.5 | Low volume (weak conviction) |

**Common usage**: 
- Breakouts should occur on high relative volume
- Low volume breakouts often fail
- Spikes above 2.0 indicate significant institutional activity

---

## Regime Detection

### Market Regime
**What it is**: Classification of current market conditions based on trend and volatility.

| Regime | Characteristics | Trading Approach |
|--------|-----------------|------------------|
| **Trending High** | Strong trend + high volatility | Ride the trend with wider stops |
| **Trending Low** | Strong trend + low volatility | Tighter stops, clear direction |
| **Ranging Tight** | Weak trend + low volatility | Fade extremes, await breakout |
| **Ranging Wide** | Weak trend + high volatility | Avoid trading (choppy) |
| **Breakout** | Squeeze releasing | Enter on confirmation |

**Common usage**: Adapt strategy selection to current regime. Trend-following works in trending regimes; mean-reversion works in ranging regimes.

---

### Volume Confluence
**What it is**: Combines multiple volume signals to assess conviction behind price moves.

| Signal | Condition |
|--------|-----------|
| Volume Spike | RVOL > 1.5 |
| Massive Spike | RVOL > 2.0 |
| Accumulation | Falling price + rising OBV |
| Distribution | Rising price + falling OBV |
| Bullish Divergence | Price falling + OBV rising |
| Bearish Divergence | Price rising + OBV falling |

**Common usage**: Validate that price moves have genuine conviction. Breakouts without volume spikes are suspect.

---

## Strategy Summary

How indicators are combined in the system's trading strategies:

| Strategy | Key Indicators | Entry Logic |
|----------|---------------|-------------|
| **Momentum Crossover** | EMA(9/21), ADX, ATR, Bollinger | Golden/Death cross with ADX > 20 |
| **Squeeze** | Bollinger, Keltner, ATR | BB inside KC, then breakout through both |
| **Keltner Breakout** | Keltner Channels, ATR | Full candle closes outside Keltner band |
| **Zone Trend** | Golden/Death Cross, Trend Strength | MA cross with zone confirmation |
| **Consolidation Fade** | ADX, Keltner, Support/Resistance | Fade moves when ADX < 20 |

---

## Quick Reference

### Overbought/Oversold Thresholds

| Indicator | Overbought | Oversold |
|-----------|------------|----------|
| RSI | > 70 | < 30 |
| Stochastic | > 80 | < 20 |
| Williams %R | > -20 | < -80 |
| CCI | > +100 | < -100 |
| Bollinger %B | > 0.8 | < 0.2 |

### Trend Strength Thresholds

| Indicator | Strong Trend | Weak/No Trend |
|-----------|-------------|---------------|
| ADX | > 25 | < 20 |
| MA Crossover Strength | > 0.5% | < 0.2% |
| ATR Trend | Increasing | Decreasing |
