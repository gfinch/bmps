#!/usr/bin/env python3
"""
Deeper analysis - looking for generalizable patterns, not just Q1-specific
"""

import pandas as pd
import numpy as np

# Load the data
df = pd.read_csv('orders.csv', 
                 names=['tradingDay','timestamp','contract','orderType','entryType','status','low','high','profitCap','profitMultiplier','placedTimestamp','filledTimestamp','closeTimestamp','cancelReason','accountId','closedAt','entryReason'],
                 header=0)

df['tradingDay'] = pd.to_datetime(df['tradingDay'])
df['is_win'] = df['status'] == 'Profit'

# Parse entry reason
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

completed = df[df['status'].isin(['Profit', 'Loss'])].copy()

print("=" * 80)
print("DEEPER PATTERN ANALYSIS - Looking for Generalizable Insights")
print("=" * 80)

# =============================================================================
# 1. OVERALL WIN RATES BY EACH CONDITION (full year)
# =============================================================================
print("\n" + "=" * 80)
print("1. WIN RATES BY CONDITION (Full Year - Ranked)")
print("=" * 80)

def analyze_condition(col, name):
    print(f"\n--- {name} ---")
    stats = completed.groupby(col).agg({
        'is_win': ['mean', 'count', 'sum']
    }).reset_index()
    stats.columns = [col, 'win_rate', 'total', 'wins']
    stats['losses'] = stats['total'] - stats['wins']
    stats = stats.sort_values('win_rate', ascending=False)
    
    print(f"{'Value':<25} {'Win Rate':>10} {'Wins':>8} {'Losses':>8} {'Total':>8}")
    print("-" * 65)
    for _, row in stats.iterrows():
        marker = "  <-- AVOID" if row['win_rate'] < 0.80 and row['total'] >= 20 else ""
        marker = "  <-- BEST" if row['win_rate'] >= 0.90 and row['total'] >= 20 else marker
        print(f"{row[col]:<25} {row['win_rate']*100:>9.1f}% {int(row['wins']):>8} {int(row['losses']):>8} {int(row['total']):>8}{marker}")
    return stats

analyze_condition('prior_trend', 'Prior Trend')
analyze_condition('volume', 'Volume State')
analyze_condition('momentum', 'Momentum State')
analyze_condition('time_of_day', 'Time of Day')
analyze_condition('position', 'Position in Range')

# =============================================================================
# 2. INTERACTION EFFECTS - Two-way combinations
# =============================================================================
print("\n" + "=" * 80)
print("2. DANGEROUS COMBINATIONS (Two conditions that interact badly)")
print("=" * 80)

# Prior Trend + Volume
print("\n--- Prior Trend × Volume ---")
combo = completed.groupby(['prior_trend', 'volume']).agg({
    'is_win': ['mean', 'count']
}).reset_index()
combo.columns = ['prior_trend', 'volume', 'win_rate', 'count']
combo = combo[combo['count'] >= 10].sort_values('win_rate')

print(f"{'Prior Trend':<15} {'Volume':<20} {'Win Rate':>10} {'Orders':>8}")
print("-" * 60)
for _, row in combo.head(10).iterrows():
    marker = " ***" if row['win_rate'] < 0.80 else ""
    print(f"{row['prior_trend']:<15} {row['volume']:<20} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}{marker}")

# Prior Trend + Time of Day
print("\n--- Prior Trend × Time of Day ---")
combo = completed.groupby(['prior_trend', 'time_of_day']).agg({
    'is_win': ['mean', 'count']
}).reset_index()
combo.columns = ['prior_trend', 'time_of_day', 'win_rate', 'count']
combo = combo[combo['count'] >= 10].sort_values('win_rate')

print(f"{'Prior Trend':<15} {'Time of Day':<15} {'Win Rate':>10} {'Orders':>8}")
print("-" * 55)
for _, row in combo.iterrows():
    marker = " ***" if row['win_rate'] < 0.82 else ""
    print(f"{row['prior_trend']:<15} {row['time_of_day']:<15} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}{marker}")

# Volume + Time of Day
print("\n--- Volume × Time of Day ---")
combo = completed.groupby(['volume', 'time_of_day']).agg({
    'is_win': ['mean', 'count']
}).reset_index()
combo.columns = ['volume', 'time_of_day', 'win_rate', 'count']
combo = combo[combo['count'] >= 8].sort_values('win_rate')

print(f"{'Volume':<20} {'Time of Day':<15} {'Win Rate':>10} {'Orders':>8}")
print("-" * 60)
for _, row in combo.head(15).iterrows():
    marker = " ***" if row['win_rate'] < 0.80 else ""
    print(f"{row['volume']:<20} {row['time_of_day']:<15} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}{marker}")

# Momentum + Volume
print("\n--- Momentum × Volume ---")
combo = completed.groupby(['momentum', 'volume']).agg({
    'is_win': ['mean', 'count']
}).reset_index()
combo.columns = ['momentum', 'volume', 'win_rate', 'count']
combo = combo[combo['count'] >= 8].sort_values('win_rate')

print(f"{'Momentum':<20} {'Volume':<20} {'Win Rate':>10} {'Orders':>8}")
print("-" * 65)
for _, row in combo.head(15).iterrows():
    marker = " ***" if row['win_rate'] < 0.80 else ""
    print(f"{row['momentum']:<20} {row['volume']:<20} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}{marker}")

# =============================================================================
# 3. ORDER TYPE ANALYSIS
# =============================================================================
print("\n" + "=" * 80)
print("3. LONG vs SHORT BY CONDITION")
print("=" * 80)

for otype in ['Long', 'Short']:
    print(f"\n--- {otype} Orders ---")
    subset = completed[completed['orderType'] == otype]
    
    # By volume
    vol_stats = subset.groupby('volume').agg({'is_win': ['mean', 'count']}).reset_index()
    vol_stats.columns = ['volume', 'win_rate', 'count']
    vol_stats = vol_stats[vol_stats['count'] >= 5].sort_values('win_rate')
    
    print(f"{'Volume':<20} {'Win Rate':>10} {'Orders':>8}")
    print("-" * 45)
    for _, row in vol_stats.iterrows():
        print(f"{row['volume']:<20} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}")

# =============================================================================
# 4. STREAK ANALYSIS - Do losses cluster?
# =============================================================================
print("\n" + "=" * 80)
print("4. LOSS CLUSTERING - Do losses come in streaks?")
print("=" * 80)

completed_sorted = completed.sort_values('timestamp').copy()
completed_sorted['prev_win'] = completed_sorted['is_win'].shift(1)
completed_sorted['after_loss'] = completed_sorted['prev_win'] == False

after_loss = completed_sorted[completed_sorted['after_loss'] == True]
after_win = completed_sorted[completed_sorted['prev_win'] == True]

print(f"\nWin rate after a LOSS:  {after_loss['is_win'].mean()*100:.1f}% ({len(after_loss)} orders)")
print(f"Win rate after a WIN:   {after_win['is_win'].mean()*100:.1f}% ({len(after_win)} orders)")

if after_loss['is_win'].mean() < after_win['is_win'].mean() - 0.05:
    print("\n*** LOSSES DO CLUSTER - Consider pausing after a loss ***")

# =============================================================================
# 5. TIME-BASED PATTERNS
# =============================================================================
print("\n" + "=" * 80)
print("5. DAY OF WEEK ANALYSIS")
print("=" * 80)

completed['day_of_week'] = completed['tradingDay'].dt.day_name()
dow_order = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday']
dow_stats = completed.groupby('day_of_week').agg({'is_win': ['mean', 'count']}).reset_index()
dow_stats.columns = ['day', 'win_rate', 'count']

print(f"\n{'Day':<15} {'Win Rate':>10} {'Orders':>8}")
print("-" * 40)
for day in dow_order:
    row = dow_stats[dow_stats['day'] == day]
    if len(row) > 0:
        wr = row['win_rate'].values[0]
        cnt = row['count'].values[0]
        marker = " <-- WEAK" if wr < 0.83 else ""
        print(f"{day:<15} {wr*100:>9.1f}% {int(cnt):>8}{marker}")

# =============================================================================
# 6. BEST AND WORST THREE-WAY COMBINATIONS
# =============================================================================
print("\n" + "=" * 80)
print("6. FULL CONDITION ANALYSIS (Three-way combos)")
print("=" * 80)

completed['full_combo'] = completed['prior_trend'] + '|' + completed['volume'] + '|' + completed['time_of_day']

combo_stats = completed.groupby('full_combo').agg({
    'is_win': ['mean', 'count']
}).reset_index()
combo_stats.columns = ['combo', 'win_rate', 'count']
combo_stats = combo_stats[combo_stats['count'] >= 5]

print("\n--- WORST COMBINATIONS (avoid these) ---")
worst = combo_stats.sort_values('win_rate').head(15)
print(f"{'Combo (Trend|Volume|Time)':<55} {'Win Rate':>10} {'Orders':>8}")
print("-" * 75)
for _, row in worst.iterrows():
    print(f"{row['combo']:<55} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}")

print("\n--- BEST COMBINATIONS (favor these) ---")
best = combo_stats.sort_values('win_rate', ascending=False).head(15)
print(f"{'Combo (Trend|Volume|Time)':<55} {'Win Rate':>10} {'Orders':>8}")
print("-" * 75)
for _, row in best.iterrows():
    print(f"{row['combo']:<55} {row['win_rate']*100:>9.1f}% {int(row['count']):>8}")

# =============================================================================
# 7. WHAT VOLUME STATES MEAN (from market_conditions.csv context)
# =============================================================================
print("\n" + "=" * 80)
print("7. VOLUME STATE INTERPRETATION")
print("=" * 80)

print("""
Based on the data and market_conditions.csv definitions:

VOL_DRYING_UP (84.9% WR, 271 orders) - "Classic consolidation, good for fade"
  → Volume declining + relative vol < 0.7
  → This IS what consolidation should look like - RELIABLE

VOL_DECLINING (84.6% WR, 136 orders) - "Volume decreasing"  
  → Typical consolidation behavior
  → Slightly less reliable than drying up

VOL_RISING (86.5% WR, 285 orders) - "Volume increasing"
  → Watch for breakout, but still works
  → Surprisingly good win rate

VOL_SURGING (87.3% WR, 165 orders) - "BREAKOUT WARNING, fade risky"
  → Volume increasing + relative vol > 1.5
  → Actually performs well overall, but had issues in Q1

VOL_VERY_LOW (82.4% WR, 51 orders) - "Relative volume < 0.5"
  → Very quiet market
  → Lower win rate - maybe not enough participation

VOL_NORMAL (83.3% WR, 66 orders) - "Stable volume"
  → No clear signal
  → Middling performance

VOL_HIGH (80.9% WR, 47 orders) - "Elevated activity"
  → Relative volume > 1.3
  → WORST performer - high activity may signal breakout coming
""")

# =============================================================================
# 8. ACTIONABLE FILTERS
# =============================================================================
print("\n" + "=" * 80)
print("8. SUGGESTED FILTERS")
print("=" * 80)

# Calculate impact of each potential filter
print("\nIf you AVOIDED these conditions, here's the impact:\n")

def calc_filter_impact(col, bad_values, name):
    avoid = completed[completed[col].isin(bad_values)]
    keep = completed[~completed[col].isin(bad_values)]
    
    avoid_wr = avoid['is_win'].mean() * 100
    keep_wr = keep['is_win'].mean() * 100
    avoid_n = len(avoid)
    keep_n = len(keep)
    
    # Losses avoided
    losses_in_avoid = len(avoid[avoid['is_win'] == False])
    wins_missed = len(avoid[avoid['is_win'] == True])
    
    print(f"{name}:")
    print(f"  Trades filtered out: {avoid_n} ({avoid_wr:.1f}% WR)")
    print(f"  Remaining trades:    {keep_n} ({keep_wr:.1f}% WR)")
    print(f"  Losses avoided: {losses_in_avoid}, Wins missed: {wins_missed}")
    print(f"  Net improvement: +{keep_wr - 85.4:.1f}% win rate")
    print()

calc_filter_impact('volume', ['VOL_HIGH'], "Avoid VOL_HIGH")
calc_filter_impact('volume', ['VOL_HIGH', 'VOL_VERY_LOW'], "Avoid VOL_HIGH + VOL_VERY_LOW")
calc_filter_impact('momentum', ['MOM_BEARISH'], "Avoid MOM_BEARISH")

# Combined filter
bad_combos = completed[
    (completed['volume'] == 'VOL_HIGH') | 
    (completed['volume'] == 'VOL_VERY_LOW') |
    ((completed['prior_trend'] == 'PRIOR_DOWN') & (completed['momentum'] == 'MOM_BEARISH'))
]
good = completed[~completed.index.isin(bad_combos.index)]

print("COMBINED FILTER (VOL_HIGH, VOL_VERY_LOW, or PRIOR_DOWN+MOM_BEARISH):")
print(f"  Filtered out: {len(bad_combos)} trades ({bad_combos['is_win'].mean()*100:.1f}% WR)")
print(f"  Remaining:    {len(good)} trades ({good['is_win'].mean()*100:.1f}% WR)")
print(f"  Improvement:  +{good['is_win'].mean()*100 - 85.4:.1f}% win rate")
