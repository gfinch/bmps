import pandas as pd
import numpy as np
import os


def add_features():
    csv_path = '../console/strategy_analysis_full.csv'
    print(f"Loading CSV from {csv_path}...")

    if not os.path.exists(csv_path):
        print(f"Error: {csv_path} not found.")
        return

    df = pd.read_csv(csv_path)
    print(f"CSV loaded. Shape: {df.shape}")

    # Ensure sorted by timestamp
    df = df.sort_values('timestamp').reset_index(drop=True)

    # 1. Reconstruct Moving Averages (EMA 9 and EMA 21)
    print("Calculating EMAs (9 and 21)...")
    df['ema9'] = df['close'].ewm(span=9, adjust=False).mean()
    df['ema21'] = df['close'].ewm(span=21, adjust=False).mean()

    # 2. Identify Crosses
    print("Identifying Crosses...")
    # Golden Cross: EMA9 > EMA21
    df['isGolden'] = (df['ema9'] > df['ema21'])
    df['isDeath'] = (df['ema9'] < df['ema21'])

    # Detect changes
    # Golden Cross happens when isGolden becomes True (was False)
    df['goldenCross'] = df['isGolden'] & (~df['isGolden'].shift(1).fillna(False))

    # Death Cross happens when isDeath becomes True (was False)
    df['deathCross'] = df['isDeath'] & (~df['isDeath'].shift(1).fillna(False))

    # 3. Calculate Minutes Since Cross
    print("Calculating Minutes Since Cross...")

    # Helper function to calculate time since last event
    def minutes_since(series):
        # Create a group ID that increments every time the event happens
        # cumsum() of boolean gives groups: 0, 0, 1, 1, 1, 2, 2...
        groups = series.cumsum()
        # We want to count how many steps since the group started
        # We can use groupby + cumcount
        return series.groupby(groups).cumcount()

    # This simple approach counts from the *start* of the group.
    # But if the event happened at index 10, index 10 is 0 minutes since.
    # Index 11 is 1 minute since.
    # However, if the event hasn't happened yet (group 0), it will count up from start of file.
    # We should mask those as a large number or handle them.

    # Better approach:
    # Create a series where values are the index, but only where goldenCross is True
    golden_idx_series = pd.Series(np.where(df['goldenCross'], df.index, np.nan), index=df.index)
    death_idx_series = pd.Series(np.where(df['deathCross'], df.index, np.nan), index=df.index)

    df['last_golden_idx'] = golden_idx_series.ffill()
    df['last_death_idx'] = death_idx_series.ffill()

    df['minutesSinceGoldenCross'] = df.index - df['last_golden_idx']
    df['minutesSinceDeathCross'] = df.index - df['last_death_idx']

    # Fill NaNs (before first cross) with 999
    df['minutesSinceGoldenCross'] = df['minutesSinceGoldenCross'].fillna(999).astype(int)
    df['minutesSinceDeathCross'] = df['minutesSinceDeathCross'].fillna(999).astype(int)

    # 4. Calculate Slopes (3-minute lookback)
    print("Calculating Slopes...")
    # Slope = (Current - 3_mins_ago) / 3
    df['atrSlope3'] = (df['atr'] - df['atr'].shift(3)) / 3.0
    df['trendStrengthSlope3'] = (df['trendStrength'] - df['trendStrength'].shift(3)) / 3.0
    df['rsiSlope3'] = (df['rsi'] - df['rsi'].shift(3)) / 3.0

    # Fill NaNs
    df['atrSlope3'] = df['atrSlope3'].fillna(0)
    df['trendStrengthSlope3'] = df['trendStrengthSlope3'].fillna(0)
    df['rsiSlope3'] = df['rsiSlope3'].fillna(0)

    # 5. Calculate Crossover Gaps
    print("Calculating Crossover Gaps...")
    # Long: TrendStrength - (100 - RSI)
    df['longCrossoverGap'] = df['trendStrength'] - (100 - df['rsi'])

    # Short: TrendStrength - RSI
    df['shortCrossoverGap'] = df['trendStrength'] - df['rsi']

    # 6. Price Action & Volatility
    print("Calculating Price Action & Volatility...")
    df['isGreen'] = (df['close'] > df['open']).astype(int)

    # Avoid division by zero for closeLocation
    range_len = df['high'] - df['low']
    df['closeLocation'] = np.where(range_len == 0, 0.5, (df['close'] - df['low']) / range_len)

    df['volatilityRatio'] = df['atr'] / df['close']

    # 7. Calculate New Targets (1.5 ATR)
    print("Calculating 1.5 ATR Targets (Simulating Price Action)...")

    # Convert to numpy for speed
    highs = df['high'].values
    lows = df['low'].values
    closes = df['close'].values
    atrs = df['atr'].values

    n = len(df)
    long_targets = np.zeros(n, dtype=int)
    short_targets = np.zeros(n, dtype=int)

    # Horizon (e.g., 120 minutes)
    horizon = 120

    # We can optimize this loop slightly, but a raw loop is safest for logic
    for i in range(n):
        if i % 50000 == 0:
            print(f"Processing row {i}/{n}...")

        entry = closes[i]
        cur_atr = atrs[i]

        if np.isnan(cur_atr) or cur_atr == 0:
            continue

        # Long Params
        long_tp = entry + (1.5 * cur_atr)
        long_sl = entry - (1.0 * cur_atr)

        # Short Params
        short_tp = entry - (1.5 * cur_atr)
        short_sl = entry + (1.0 * cur_atr)

        # Look forward
        end_idx = min(i + horizon, n)

        # Long Logic
        for j in range(i + 1, end_idx):
            # Check Stop First (conservative) or High/Low simultaneously?
            # If a bar covers both, it's usually a loss (whipsaw) unless we know intra-bar path.
            # Conservative assumption: If Low <= SL, we stopped out.
            if lows[j] <= long_sl:
                long_targets[i] = 0  # Loss
                break
            if highs[j] >= long_tp:
                long_targets[i] = 1  # Win
                break

        # Short Logic
        for j in range(i + 1, end_idx):
            if highs[j] >= short_sl:
                short_targets[i] = 0  # Loss
                break
            if lows[j] <= short_tp:
                short_targets[i] = 1  # Win
                break

    df['longProfit1_5ATR'] = long_targets
    df['shortProfit1_5ATR'] = short_targets

    # Cleanup temporary columns
    df.drop(columns=['ema9', 'ema21', 'isGolden', 'isDeath', 'goldenCross', 'deathCross', 'last_golden_idx', 'last_death_idx'], inplace=True)

    print(f"Saving to {csv_path}...")
    df.to_csv(csv_path, index=False)
    print("Done.")


if __name__ == "__main__":
    add_features()
