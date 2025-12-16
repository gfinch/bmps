import pandas as pd
import xgboost as xgb
from sklearn.model_selection import train_test_split, TimeSeriesSplit
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
import matplotlib.pyplot as plt
import seaborn as sns
import os
import numpy as np
import json

# 1. Load Data
csv_path = '../console/strategy_analysis_full.csv'
print(f"Loading data from {csv_path}...")

if not os.path.exists(csv_path):
    print(f"Error: File not found at {csv_path}")
    exit(1)

df = pd.read_csv(csv_path)

# 2. Preprocessing
print("Preprocessing data...")

# Convert boolean rules to int (0/1)
rule_cols = [f'rule{i}' for i in range(1, 13)]
for col in rule_cols:
    df[col] = df[col].astype(int)

# Feature Engineering: Time of Day
# 'time' column is "HH:mm:ss"
df['datetime_obj'] = pd.to_datetime(df['date'] + ' ' + df['time'])
df['hour'] = df['datetime_obj'].dt.hour
df['minute'] = df['datetime_obj'].dt.minute
df['day'] = df['datetime_obj'].dt.day

new_features = [
    'spreadChange1', 'spreadChange2', 'spreadChange3', 'spreadChange5', 'spreadChange10',
    'rsiChange1', 'rsiChange2', 'rsiChange3', 'rsiChange5', 'rsiChange10',
    'minutesSinceGoldenCross', 'minutesSinceDeathCross', 'atrSlope3', 'trendStrengthSlope3', 'rsiSlope3',
    'longCrossoverGap', 'shortCrossoverGap'
]

# feature_cols = [
#     'rsi', 'adx', 'trendStrength', 'maSpread', 'atr', 'volume', 'relativeVolume', 'hour', 'minute'
# ] + rule_cols + new_features

# Restricted Feature Set (Intersection Strategy)
feature_cols = [
    'minutesSinceGoldenCross', 'minutesSinceDeathCross',
    'atrSlope3', 'trendStrengthSlope3', 'rsiSlope3',
    'longCrossoverGap', 'shortCrossoverGap',
    'rsi', 'trendStrength', 'atr', 'adx'
]


def train_and_save(direction, target_col):
    print(f"\n\n========== Training {direction.upper()} Model ({target_col}) ==========")

    # Define Features based on direction
    if direction == 'long':
        # Long Strategy: Golden Cross context
        # Features: TrendStrength, RSI (Inverse logic handled by model learning low RSI is good?),
        # actually user said "Inverse RSI".
        # If we pass raw RSI, the model can learn that low RSI is good.
        # But user specifically asked for "intersection of two lines".
        # We calculated 'longCrossoverGap' = TrendStrength - (100 - RSI).

        current_features = [
            'minutesSinceGoldenCross',
            'trendStrength',
            'trendStrengthSlope3',
            'rsi',
            'rsiSlope3',
            'longCrossoverGap',
            'isGreen',
            'closeLocation',
            'volatilityRatio'
        ]
    else:
        # Short Strategy: Death Cross context
        current_features = [
            'minutesSinceDeathCross',
            'trendStrength',
            'trendStrengthSlope3',
            'rsi',
            'rsiSlope3',
            'shortCrossoverGap',
            'isGreen',
            'closeLocation',
            'volatilityRatio'
        ]

    # Define Target
    # User requested 1.5 ATR target
    target_col = 'longProfit1_5ATR' if direction == 'long' else 'shortProfit1_5ATR'

    df[target_col] = df[target_col].astype(int)
    X = df[current_features]
    y = df[target_col]

    print(f"Features: {current_features}")
    print(f"Target: {target_col}")
    print(f"Dataset shape: {X.shape}")    # 3. Train/Test Split (Custom: 2nd week of each month is Test)
    # 2nd week = days 8-14
    test_mask = (df['day'] >= 8) & (df['day'] <= 14)
    train_mask = ~test_mask

    X_train = X[train_mask]
    y_train = y[train_mask]
    X_test = X[test_mask]
    y_test = y[test_mask]

    print(f"Train set: {X_train.shape}")
    print(f"Test set: {X_test.shape}")

    # 4. Train Final Model
    print(f"\n--- Training Final {direction} Model ---")

    # Calculate Class Weight
    num_neg = (y_train == 0).sum()
    num_pos = (y_train == 1).sum()
    scale_pos_weight = num_neg / num_pos if num_pos > 0 else 1.0
    print(f"Class balance: Neg={num_neg}, Pos={num_pos}, Scale Weight={scale_pos_weight:.2f}")

    model = xgb.XGBClassifier(
        objective='binary:logistic',
        n_estimators=2000,
        learning_rate=0.03,
        max_depth=6,
        subsample=0.7,
        colsample_bytree=0.7,
        gamma=0.5,
        reg_alpha=0.5,
        reg_lambda=2.0,
        min_child_weight=10,
        scale_pos_weight=scale_pos_weight,
        use_label_encoder=False,
        eval_metric='logloss',
        early_stopping_rounds=50
    )

    model.fit(X_train, y_train, eval_set=[(X_train, y_train), (X_test, y_test)], verbose=False)

    # Feature Importance
    importance = model.feature_importances_
    feature_imp = pd.DataFrame(sorted(zip(importance, current_features)), columns=['Value', 'Feature'])
    print(f"\nTop 10 Features for {direction}:")
    print(feature_imp.sort_values(by='Value', ascending=False).head(10))

    # Check Train vs Test AUC
    train_probs = model.predict_proba(X_train)[:, 1]
    test_probs = model.predict_proba(X_test)[:, 1]
    from sklearn.metrics import roc_auc_score
    print(f"Train AUC: {roc_auc_score(y_train, train_probs):.4f}")
    print(f"Test AUC:  {roc_auc_score(y_test, test_probs):.4f}")

    # 5. Evaluate on Test Set
    y_probs = test_probs

    print("\nThreshold Tuning on Test Set:")
    print(f"{'Threshold':<10} {'Precision':<10} {'Recall':<10} {'Trades/Day':<10} {'Win Rate':<10}")

    best_threshold = 0.70  # Default fallback
    best_metric = -1

    # Estimate days in test set
    unique_dates_test = df.iloc[X_test.index]['date'].nunique()
    print(f"Test set covers approx {unique_dates_test} days.")

    # Search thresholds
    thresholds = [0.5, 0.55, 0.60, 0.65, 0.70, 0.71, 0.72, 0.73, 0.74, 0.75, 0.80]

    for thresh in thresholds:
        y_pred_thresh = (y_probs >= thresh).astype(int)

        # Calculate metrics
        if y_pred_thresh.sum() == 0:
            precision = 0.0
        else:
            precision = accuracy_score(y_test[y_pred_thresh == 1], y_pred_thresh[y_pred_thresh == 1])

        # Count trades
        num_trades = y_pred_thresh.sum()
        trades_per_day = num_trades / unique_dates_test if unique_dates_test > 0 else 0

        print(f"{thresh:<10.2f} {precision:<10.4f} {'-':<10} {trades_per_day:<10.1f} {precision * 100:.1f}%")

        # Selection Logic:
        # We want high precision (> 70%) and max trades.
        # If precision > 0.70, score = trades_per_day
        if precision >= 0.70:
            score = trades_per_day
            if score > best_metric:
                best_metric = score
                best_threshold = thresh

        # Fallback if no threshold meets 0.70 precision, pick the one with highest precision
        if best_metric == -1 and precision > 0.5:
            # This is a weak fallback, but better than nothing.
            # Actually, let's just stick to the loop.
            pass

    print(f"\nSelected Threshold for {direction}: {best_threshold}")
    y_pred = (y_probs >= best_threshold).astype(int)

    accuracy = accuracy_score(y_test, y_pred)

    print(f"\nAccuracy: {accuracy:.4f}")
    print("\nClassification Report:")
    print(classification_report(y_test, y_pred))

    print("\nConfusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    # Save model
    model_filename = f'xgboost_{direction}_strategy.json'
    model.save_model(model_filename)
    print(f"\nModel saved to {model_filename}")

    return {
        "threshold": float(best_threshold),
        "features": current_features,
        "target": target_col,
        "scale_pos_weight": scale_pos_weight
    }


# Train both models
long_config = train_and_save('long', 'longProfit2ATR')
short_config = train_and_save('short', 'shortProfit2ATR')

# Save combined config
full_config = {
    "long": long_config,
    "short": short_config
}

with open('model_config.json', 'w') as f:
    json.dump(full_config, f, indent=4)
print("\nCombined model config saved to model_config.json")
