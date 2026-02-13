#!/usr/bin/env python3
"""
Analyze order performance by time period and entry conditions.
Focus on understanding why Jan-Mar 2024 performed poorly.
"""

import pandas as pd
import numpy as np
from datetime import datetime

# Load the data - fix column names (entryReason is missing from header)
df = pd.read_csv('orders.csv', 
                 names=['tradingDay','timestamp','contract','orderType','entryType','status','low','high','profitCap','profitMultiplier','placedTimestamp','filledTimestamp','closeTimestamp','cancelReason','accountId','closedAt','entryReason'],
                 header=0)

# Parse trading day as date
df['tradingDay'] = pd.to_datetime(df['tradingDay'])
df['month'] = df['tradingDay'].dt.month
df['month_name'] = df['tradingDay'].dt.strftime('%b')
df['quarter'] = df['tradingDay'].dt.quarter

# Create win/loss indicator
df['is_win'] = df['status'] == 'Profit'

# Parse entry reason components from entryReason column
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

# Filter to completed orders only
completed = df[df['status'].isin(['Profit', 'Loss'])].copy()

print("=" * 80)
print("CONSOLIDATION FADE ANALYSIS - Full Year Performance")
print("=" * 80)

# Overall stats
total_orders = len(completed)
wins = completed['is_win'].sum()
losses = total_orders - wins
overall_wr = wins / total_orders * 100

print(f"\nTotal Orders: {total_orders}")
print(f"Wins: {wins}, Losses: {losses}")
print(f"Overall Win Rate: {overall_wr:.1f}%")

# Monthly breakdown
print("\n" + "=" * 80)
print("MONTHLY PERFORMANCE")
print("=" * 80)
print(f"\n{'Month':<10} {'Orders':>8} {'Wins':>8} {'Losses':>8} {'Win Rate':>10}")
print("-" * 50)

monthly = completed.groupby(['month', 'month_name']).agg({
    'is_win': ['count', 'sum']
}).reset_index()
monthly.columns = ['month', 'month_name', 'orders', 'wins']
monthly['losses'] = monthly['orders'] - monthly['wins']
monthly['win_rate'] = monthly['wins'] / monthly['orders'] * 100

for _, row in monthly.iterrows():
    marker = "  <-- POOR" if row['win_rate'] < 50 else ""
    print(f"{row['month_name']:<10} {row['orders']:>8} {row['wins']:>8} {row['losses']:>8} {row['win_rate']:>9.1f}%{marker}")

# Q1 vs Rest of Year
print("\n" + "=" * 80)
print("Q1 (Jan-Mar) vs REST OF YEAR")
print("=" * 80)

q1 = completed[completed['quarter'] == 1]
rest = completed[completed['quarter'] > 1]

q1_wr = q1['is_win'].mean() * 100
rest_wr = rest['is_win'].mean() * 100

print(f"\nQ1 (Jan-Mar):     {len(q1)} orders, Win Rate: {q1_wr:.1f}%")
print(f"Rest of Year:     {len(rest)} orders, Win Rate: {rest_wr:.1f}%")
print(f"Difference:       {rest_wr - q1_wr:.1f} percentage points")

# Now compare conditions between Q1 and rest of year
print("\n" + "=" * 80)
print("WHAT'S DIFFERENT ABOUT Q1? (Comparing Conditions)")
print("=" * 80)

def compare_distributions(col_name, display_name):
    print(f"\n--- {display_name} ---")
    
    q1_dist = q1[col_name].value_counts(normalize=True) * 100
    rest_dist = rest[col_name].value_counts(normalize=True) * 100
    
    # Get all unique values
    all_vals = set(q1_dist.index) | set(rest_dist.index)
    
    print(f"{'Value':<25} {'Q1 %':>10} {'Rest %':>10} {'Diff':>10}")
    print("-" * 60)
    
    for val in sorted(all_vals):
        q1_pct = q1_dist.get(val, 0)
        rest_pct = rest_dist.get(val, 0)
        diff = q1_pct - rest_pct
        marker = " ***" if abs(diff) > 5 else ""
        print(f"{val:<25} {q1_pct:>9.1f}% {rest_pct:>9.1f}% {diff:>+9.1f}%{marker}")

compare_distributions('prior_trend', 'Prior Trend')
compare_distributions('range_quality', 'Range Quality')
compare_distributions('position', 'Position in Range')
compare_distributions('momentum', 'Momentum State')
compare_distributions('volume', 'Volume State')
compare_distributions('time_of_day', 'Time of Day')

# Win rates by condition - Q1 vs Rest
print("\n" + "=" * 80)
print("WIN RATES BY CONDITION - Q1 vs REST OF YEAR")
print("=" * 80)

def compare_win_rates(col_name, display_name):
    print(f"\n--- {display_name} ---")
    
    q1_wr = q1.groupby(col_name)['is_win'].agg(['mean', 'count'])
    rest_wr = rest.groupby(col_name)['is_win'].agg(['mean', 'count'])
    
    all_vals = set(q1_wr.index) | set(rest_wr.index)
    
    print(f"{'Value':<25} {'Q1 WR':>10} {'Q1 N':>6} {'Rest WR':>10} {'Rest N':>6} {'Diff':>10}")
    print("-" * 75)
    
    results = []
    for val in all_vals:
        q1_rate = q1_wr.loc[val, 'mean'] * 100 if val in q1_wr.index else 0
        q1_n = int(q1_wr.loc[val, 'count']) if val in q1_wr.index else 0
        rest_rate = rest_wr.loc[val, 'mean'] * 100 if val in rest_wr.index else 0
        rest_n = int(rest_wr.loc[val, 'count']) if val in rest_wr.index else 0
        diff = q1_rate - rest_rate
        results.append((val, q1_rate, q1_n, rest_rate, rest_n, diff))
    
    # Sort by difference (worst performers in Q1 first)
    results.sort(key=lambda x: x[5])
    
    for val, q1_rate, q1_n, rest_rate, rest_n, diff in results:
        marker = " ***" if diff < -15 and q1_n >= 5 else ""
        print(f"{val:<25} {q1_rate:>9.1f}% {q1_n:>6} {rest_rate:>9.1f}% {rest_n:>6} {diff:>+9.1f}%{marker}")

compare_win_rates('prior_trend', 'Prior Trend')
compare_win_rates('volume', 'Volume State')
compare_win_rates('momentum', 'Momentum State')
compare_win_rates('time_of_day', 'Time of Day')

# Look at specific bad combinations in Q1
print("\n" + "=" * 80)
print("WORST PERFORMING COMBINATIONS IN Q1")
print("=" * 80)

# Create combo column
q1['combo'] = q1['prior_trend'] + '|' + q1['volume'] + '|' + q1['momentum']

combo_stats = q1.groupby('combo').agg({
    'is_win': ['mean', 'count']
}).reset_index()
combo_stats.columns = ['combo', 'win_rate', 'count']
combo_stats = combo_stats[combo_stats['count'] >= 3]  # At least 3 orders
combo_stats = combo_stats.sort_values('win_rate')

print(f"\n{'Combo (Trend|Volume|Momentum)':<50} {'Win Rate':>10} {'Orders':>8}")
print("-" * 70)

for _, row in combo_stats.head(15).iterrows():
    print(f"{row['combo']:<50} {row['win_rate']*100:>9.1f}% {row['count']:>8}")

# Best performers for comparison
print("\n" + "=" * 80)
print("BEST PERFORMING COMBINATIONS IN Q1 (for contrast)")
print("=" * 80)

print(f"\n{'Combo (Trend|Volume|Momentum)':<50} {'Win Rate':>10} {'Orders':>8}")
print("-" * 70)

for _, row in combo_stats.tail(10).iloc[::-1].iterrows():
    print(f"{row['combo']:<50} {row['win_rate']*100:>9.1f}% {row['count']:>8}")

# Orders by order type
print("\n" + "=" * 80)
print("LONG vs SHORT PERFORMANCE")
print("=" * 80)

for period, data in [("Q1", q1), ("Rest", rest)]:
    print(f"\n{period}:")
    for otype in ['Long', 'Short']:
        subset = data[data['orderType'] == otype]
        if len(subset) > 0:
            wr = subset['is_win'].mean() * 100
            print(f"  {otype}: {len(subset)} orders, {wr:.1f}% win rate")

# Key insights
print("\n" + "=" * 80)
print("KEY INSIGHTS")
print("=" * 80)

# Find the biggest differences
print("\n*** Look for conditions where Q1 win rate is much lower than rest of year ***")
print("*** These are the conditions that may indicate algo struggles in certain markets ***")
print("\n*** Also look for condition distributions - if Q1 had more of a 'bad' condition ***")
print("*** that could explain the poor performance ***")
