import pandas as pd
import xgboost as xgb
import os
import numpy as np

# Configuration
RISK_PER_TRADE = 1000.0
REWARD_PER_TRADE = 2000.0
FEES_PER_TRADE = 23.0

# 1. Load Data
csv_path = '../console/strategy_analysis_full.csv'
if not os.path.exists(csv_path):
    print(f"Error: File not found at {csv_path}")
    exit(1)

df = pd.read_csv(csv_path)

# 2. Preprocessing
rule_cols = [f'rule{i}' for i in range(1, 22)]
for col in rule_cols:
    df[col] = df[col].astype(int)

df['datetime_obj'] = pd.to_datetime(df['date'] + ' ' + df['time'])
df['hour'] = df['datetime_obj'].dt.hour
df['minute'] = df['datetime_obj'].dt.minute

feature_cols = [
    'rsi', 'adx', 'trendStrength', 'maSpread', 'atr', 'hour', 'minute'
] + rule_cols

# 3. Test Set
split_idx = int(len(df) * 0.8)
df_test = df.iloc[split_idx:].copy()
unique_dates = df_test['date'].nunique()
print(f"Analysis over {unique_dates} days.")

# 4. Load Models
model_long = xgb.Booster()
model_long.load_model('xgboost_long_strategy.json')

model_short = xgb.Booster()
model_short.load_model('xgboost_short_strategy.json')

# 5. Predict
dtest = xgb.DMatrix(df_test[feature_cols])
probs_long = model_long.predict(dtest)
probs_short = model_short.predict(dtest)

y_long = df_test['longProfit2ATR'].astype(int).values
y_short = df_test['shortProfit2ATR'].astype(int).values

print(f"\n{'Threshold':<10} {'Trades/Day':<12} {'Win Rate':<10} {'Gross Profit':<15} {'Gross Loss':<15} {'Fees':<10} {'Net Daily Profit':<18}")
print("-" * 100)

thresholds = np.arange(0.60, 0.81, 0.01)

for thresh in thresholds:
    # Long Trades
    preds_long = (probs_long >= thresh).astype(int)
    wins_long = y_long[preds_long == 1].sum()
    losses_long = preds_long.sum() - wins_long

    # Short Trades
    preds_short = (probs_short >= thresh).astype(int)
    wins_short = y_short[preds_short == 1].sum()
    losses_short = preds_short.sum() - wins_short

    # Combined
    total_trades = preds_long.sum() + preds_short.sum()
    if total_trades == 0:
        continue

    total_wins = wins_long + wins_short
    total_losses = losses_long + losses_short
    win_rate = total_wins / total_trades
    trades_per_day = total_trades / unique_dates

    gross_profit = total_wins * REWARD_PER_TRADE
    gross_loss = total_losses * RISK_PER_TRADE
    total_fees = total_trades * FEES_PER_TRADE

    net_profit_total = gross_profit - gross_loss - total_fees
    net_daily_profit = net_profit_total / unique_dates

    # Formatting for table
    gross_p_str = f"${gross_profit / unique_dates:,.0f}"  # Daily avg gross profit
    gross_l_str = f"-${gross_loss / unique_dates:,.0f}"  # Daily avg gross loss
    fees_str = f"-${total_fees / unique_dates:,.0f}"
    net_str = f"${net_daily_profit:,.0f}"

    print(f"{thresh:<10.2f} {trades_per_day:<12.1f} {win_rate * 100:<9.1f}% {gross_p_str:<15} {gross_l_str:<15} {fees_str:<10} {net_str:<18}")
