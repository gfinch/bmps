import pandas as pd
import xgboost as xgb
import os
import numpy as np
from sklearn.metrics import accuracy_score, roc_auc_score

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

# 3. Split Data
split_idx = int(len(df) * 0.8)
X_train = df.iloc[:split_idx][feature_cols]
X_test = df.iloc[split_idx:][feature_cols]

# 4. Analyze Overfitting


def check_overfitting(direction, model_path, target_col):
    print(f"\n========== {direction.upper()} Model Overfitting Check ==========")

    y_train = df.iloc[:split_idx][target_col].astype(int)
    y_test = df.iloc[split_idx:][target_col].astype(int)

    model = xgb.Booster()
    model.load_model(model_path)

    dtrain = xgb.DMatrix(X_train)
    dtest = xgb.DMatrix(X_test)

    prob_train = model.predict(dtrain)
    prob_test = model.predict(dtest)

    auc_train = roc_auc_score(y_train, prob_train)
    auc_test = roc_auc_score(y_test, prob_test)

    print(f"AUC Train: {auc_train:.4f}")
    print(f"AUC Test:  {auc_test:.4f}")
    print(f"Drop:      {(auc_train - auc_test):.4f}")

    # Check precision at 0.70 threshold
    thresh = 0.70
    pred_train = (prob_train >= thresh).astype(int)
    pred_test = (prob_test >= thresh).astype(int)

    if pred_train.sum() > 0:
        prec_train = accuracy_score(y_train[pred_train == 1], pred_train[pred_train == 1])
    else:
        prec_train = 0.0

    if pred_test.sum() > 0:
        prec_test = accuracy_score(y_test[pred_test == 1], pred_test[pred_test == 1])
    else:
        prec_test = 0.0

    print(f"\nPrecision @ {thresh}:")
    print(f"Train: {prec_train:.4f} ({pred_train.sum()} trades)")
    print(f"Test:  {prec_test:.4f} ({pred_test.sum()} trades)")


check_overfitting('Long', 'xgboost_long_strategy.json', 'longProfit2ATR')
check_overfitting('Short', 'xgboost_short_strategy.json', 'shortProfit2ATR')
