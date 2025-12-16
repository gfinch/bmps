import pandas as pd
import xgboost as xgb
import os
import numpy as np

# 1. Load Data
csv_path = '../console/strategy_analysis_full.csv'
if not os.path.exists(csv_path):
    print(f"Error: File not found at {csv_path}")
    exit(1)

df = pd.read_csv(csv_path)

# 2. Preprocessing
# Convert boolean rules to int (0/1)
rule_cols = [f'rule{i}' for i in range(1, 22)]
for col in rule_cols:
    df[col] = df[col].astype(int)

# Feature Engineering
df['datetime_obj'] = pd.to_datetime(df['date'] + ' ' + df['time'])
df['hour'] = df['datetime_obj'].dt.hour
df['minute'] = df['datetime_obj'].dt.minute

feature_cols = [
    'rsi', 'adx', 'trendStrength', 'maSpread', 'atr', 'hour', 'minute'
] + rule_cols

# 3. Split Data (Use last 20% as Test Set, same as training)
split_idx = int(len(df) * 0.8)
df_test = df.iloc[split_idx:].copy()
unique_dates = df_test['date'].nunique()

print(f"Analysis performed on Test Set ({unique_dates} days)")
print("Assumptions: Target = 2R, Stop Loss = 1R. (Profit = 2 * Risk, Loss = 1 * Risk)")


def analyze_model(direction, model_path, target_col):
    print(f"\n\n========== {direction.upper()} Model Analysis ==========")
    if not os.path.exists(model_path):
        print(f"Model not found: {model_path}")
        return

    model = xgb.Booster()
    model.load_model(model_path)

    # Prepare DMatrix
    X_test = df_test[feature_cols]
    y_test = df_test[target_col].astype(int)
    dtest = xgb.DMatrix(X_test)

    # Predict
    probs = model.predict(dtest)

    print(f"{'Threshold':<10} {'Win Rate':<10} {'Trades':<8} {'Trades/Day':<12} {'EV (R)':<12} {'Total Profit (R)':<15}")
    print("-" * 80)

    thresholds = np.arange(0.60, 0.90, 0.01)

    for thresh in thresholds:
        preds = (probs >= thresh).astype(int)

        if preds.sum() == 0:
            continue

        # Filter for trades taken
        trades_idx = preds == 1
        wins = y_test[trades_idx].sum()
        total_trades = preds.sum()
        losses = total_trades - wins

        win_rate = wins / total_trades
        trades_per_day = total_trades / unique_dates

        # Expectation: Win = 2R, Loss = -1R
        # EV = (Win% * 2) - (Loss% * 1)
        ev_per_trade = (win_rate * 2.0) - ((1 - win_rate) * 1.0)
        total_profit_r = (wins * 2.0) - (losses * 1.0)

        print(f"{thresh:<10.2f} {win_rate * 100:<9.1f}% {total_trades:<8} {trades_per_day:<12.1f} {ev_per_trade:<12.2f} {total_profit_r:<15.1f}")


analyze_model('Long', 'xgboost_long_strategy.json', 'longProfit2ATR')
analyze_model('Short', 'xgboost_short_strategy.json', 'shortProfit2ATR')
