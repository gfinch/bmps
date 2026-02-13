#!/usr/bin/env python3
"""
Validate 2024 patterns against 2025 data
"""

import pandas as pd
import numpy as np

# Load both datasets
df_2024 = pd.read_csv('orders.csv', 
                 names=['tradingDay','timestamp','contract','orderType','entryType','status','low','high','profitCap','profitMultiplier','placedTimestamp','filledTimestamp','closeTimestamp','cancelReason','accountId','closedAt','entryReason'],
                 header=0)

df_2025 = pd.read_csv('orders_2025.csv', 
                 names=['tradingDay','timestamp','contract','orderType','entryType','status','low','high','profitCap','profitMultiplier','placedTimestamp','filledTimestamp','closeTimestamp','cancelReason','accountId','closedAt','entryReason'],
                 header=0)

def prepare_df(df, year):
    df['tradingDay'] = pd.to_datetime(df['tradingDay'])
    df['is_win'] = df['status'] == 'Profit'
    df['year'] = year
    
    def parse_entry_reason(reason):
        if pd.isna(reason):
            return {}
        parts = str(reason).split('|')
        if len(parts) >= 6:
            return {
                'prior_trend': parts[0],
                'range_quality': parts[1],
                'position': parts[2],
                'momentum': parts[3],
                'volume': parts[4],
                'time_of_day': parts[5]
            }
        return {}
    
    parsed = df['entryReason'].apply(parse_entry_reason)
    df['prior_trend'] = parsed.apply(lambda x: x.get('prior_trend', 'UNKNOWN'))
    df['range_quality'] = parsed.apply(lambda x: x.get('range_quality', 'UNKNOWN'))
    df['position'] = parsed.apply(lambda x: x.get('position', 'UNKNOWN'))
    df['momentum'] = parsed.apply(lambda x: x.get('momentum', 'UNKNOWN'))
    df['volume'] = parsed.apply(lambda x: x.get('volume', 'UNKNOWN'))
    df['time_of_day'] = parsed.apply(lambda x: x.get('time_of_day', 'UNKNOWN'))
    df['day_of_week'] = df['tradingDay'].dt.day_name()
    
    return df[df['status'].isin(['Profit', 'Loss'])].copy()

c2024 = prepare_df(df_2024, 2024)
c2025 = prepare_df(df_2025, 2025)

print("=" * 80)
print("2024 vs 2025 PATTERN VALIDATION")
print("=" * 80)

print(f"\n2024: {len(c2024)} orders, {c2024['is_win'].mean()*100:.1f}% win rate")
print(f"2025: {len(c2025)} orders, {c2025['is_win'].mean()*100:.1f}% win rate")

# =============================================================================
# Compare each pattern
# =============================================================================

def compare_pattern(col, name, c2024, c2025):
    print(f"\n--- {name} ---")
    
    s2024 = c2024.groupby(col)['is_win'].agg(['mean', 'count']).reset_index()
    s2024.columns = [col, 'wr_2024', 'n_2024']
    
    s2025 = c2025.groupby(col)['is_win'].agg(['mean', 'count']).reset_index()
    s2025.columns = [col, 'wr_2025', 'n_2025']
    
    merged = s2024.merge(s2025, on=col, how='outer').fillna(0)
    merged['diff'] = (merged['wr_2025'] - merged['wr_2024']) * 100
    merged = merged.sort_values('wr_2024')
    
    print(f"{'Value':<25} {'2024 WR':>10} {'2024 N':>8} {'2025 WR':>10} {'2025 N':>8} {'Diff':>10} {'Pattern Holds?':<15}")
    print("-" * 95)
    
    for _, row in merged.iterrows():
        wr24 = row['wr_2024'] * 100
        wr25 = row['wr_2025'] * 100
        diff = row['diff']
        
        # Check if pattern holds (same relative ranking/direction)
        if row['n_2025'] < 5:
            holds = "Low sample"
        elif abs(diff) < 5:
            holds = "✓ HOLDS"
        elif (wr24 < 83 and wr25 < 83) or (wr24 > 87 and wr25 > 87):
            holds = "✓ HOLDS"
        elif diff > 10:
            holds = "⚠ BETTER in 2025"
        elif diff < -10:
            holds = "✗ WORSE in 2025"
        else:
            holds = "~ Similar"
            
        print(f"{row[col]:<25} {wr24:>9.1f}% {int(row['n_2024']):>8} {wr25:>9.1f}% {int(row['n_2025']):>8} {diff:>+9.1f}% {holds:<15}")

compare_pattern('volume', 'Volume State', c2024, c2025)
compare_pattern('momentum', 'Momentum State', c2024, c2025)
compare_pattern('time_of_day', 'Time of Day', c2024, c2025)
compare_pattern('prior_trend', 'Prior Trend', c2024, c2025)
compare_pattern('position', 'Position in Range', c2024, c2025)
compare_pattern('day_of_week', 'Day of Week', c2024, c2025)

# =============================================================================
# Test specific danger patterns from 2024
# =============================================================================
print("\n" + "=" * 80)
print("TESTING 2024 DANGER PATTERNS IN 2025")
print("=" * 80)

def test_danger_pattern(name, condition_func, c2024, c2025):
    d2024 = c2024[condition_func(c2024)]
    d2025 = c2025[condition_func(c2025)]
    
    wr24 = d2024['is_win'].mean() * 100 if len(d2024) > 0 else 0
    wr25 = d2025['is_win'].mean() * 100 if len(d2025) > 0 else 0
    
    status = "✓ Still dangerous" if wr25 < 83 else "✗ Not dangerous in 2025" if wr25 > 87 else "~ Borderline"
    
    print(f"\n{name}:")
    print(f"  2024: {len(d2024)} orders, {wr24:.1f}% WR")
    print(f"  2025: {len(d2025)} orders, {wr25:.1f}% WR")
    print(f"  Status: {status}")

test_danger_pattern(
    "VOL_HIGH",
    lambda df: df['volume'] == 'VOL_HIGH',
    c2024, c2025
)

test_danger_pattern(
    "VOL_VERY_LOW", 
    lambda df: df['volume'] == 'VOL_VERY_LOW',
    c2024, c2025
)

test_danger_pattern(
    "MOM_BEARISH",
    lambda df: df['momentum'] == 'MOM_BEARISH',
    c2024, c2025
)

test_danger_pattern(
    "MOM_BEARISH + VOL_DECLINING",
    lambda df: (df['momentum'] == 'MOM_BEARISH') & (df['volume'] == 'VOL_DECLINING'),
    c2024, c2025
)

test_danger_pattern(
    "PRIOR_DOWN + MOM_BEARISH",
    lambda df: (df['prior_trend'] == 'PRIOR_DOWN') & (df['momentum'] == 'MOM_BEARISH'),
    c2024, c2025
)

test_danger_pattern(
    "VOL_HIGH + MIDMORNING",
    lambda df: (df['volume'] == 'VOL_HIGH') & (df['time_of_day'] == 'MIDMORNING'),
    c2024, c2025
)

test_danger_pattern(
    "Wednesday",
    lambda df: df['day_of_week'] == 'Wednesday',
    c2024, c2025
)

# =============================================================================
# Test best patterns
# =============================================================================
print("\n" + "=" * 80)
print("TESTING 2024 BEST PATTERNS IN 2025")
print("=" * 80)

test_danger_pattern(
    "MOM_NEUTRAL",
    lambda df: df['momentum'] == 'MOM_NEUTRAL',
    c2024, c2025
)

test_danger_pattern(
    "FADE_FROM_LOWER (not MID)",
    lambda df: df['position'] == 'FADE_FROM_LOWER',
    c2024, c2025
)

test_danger_pattern(
    "Monday",
    lambda df: df['day_of_week'] == 'Monday',
    c2024, c2025
)

test_danger_pattern(
    "VOL_SURGING (surprisingly good in 2024)",
    lambda df: df['volume'] == 'VOL_SURGING',
    c2024, c2025
)

# =============================================================================
# Combined filter test
# =============================================================================
print("\n" + "=" * 80)
print("COMBINED FILTER TEST")
print("=" * 80)

def apply_filter(df):
    return ~(
        (df['volume'] == 'VOL_HIGH') | 
        (df['volume'] == 'VOL_VERY_LOW') |
        ((df['prior_trend'] == 'PRIOR_DOWN') & (df['momentum'] == 'MOM_BEARISH'))
    )

for year, data in [('2024', c2024), ('2025', c2025)]:
    filtered = data[apply_filter(data)]
    removed = data[~apply_filter(data)]
    
    print(f"\n{year}:")
    print(f"  Without filter: {len(data)} orders, {data['is_win'].mean()*100:.1f}% WR")
    print(f"  With filter:    {len(filtered)} orders, {filtered['is_win'].mean()*100:.1f}% WR")
    print(f"  Filtered out:   {len(removed)} orders, {removed['is_win'].mean()*100:.1f}% WR")
    print(f"  Improvement:    +{filtered['is_win'].mean()*100 - data['is_win'].mean()*100:.1f}%")
