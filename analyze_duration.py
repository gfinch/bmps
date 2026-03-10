#!/usr/bin/env python3
"""Analyze trade duration combined with entry time."""

import csv
from datetime import datetime
from collections import defaultdict

orders = []
with open('/Users/gf26229/Downloads/3xOrders.csv', 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        if row.get('Open Time (ET)', '').startswith('===') or not row.get('Open Time (ET)'):
            break
        try:
            open_time = datetime.strptime(row['Open Time (ET)'], '%Y-%m-%d %H:%M:%S')
            close_time = datetime.strptime(row['Close Time (ET)'], '%Y-%m-%d %H:%M:%S')
            duration_mins = (close_time - open_time).total_seconds() / 60
            orders.append({
                'open_time': open_time,
                'close_time': close_time,
                'duration_mins': duration_mins,
                'pnl': float(row['Profit/Loss']),
                'status': row['Status'],
                'entry_hour': open_time.hour
            })
        except:
            continue

print('='*80)
print('TRADE DURATION ANALYSIS')
print('='*80)

# Duration buckets
buckets = [
    ('< 5 min', 0, 5),
    ('5-15 min', 5, 15),
    ('15-30 min', 15, 30),
    ('30-60 min', 30, 60),
    ('1-2 hours', 60, 120),
    ('2-4 hours', 120, 240),
    ('4+ hours', 240, 9999)
]

print(f"\n{'Duration':<12} {'Trades':<8} {'Wins':<6} {'Loss':<6} {'Win%':<8} {'Total P/L':<14} {'Avg P/L':<10}")
print('-' * 75)

for name, lo, hi in buckets:
    trades = [o for o in orders if lo <= o['duration_mins'] < hi]
    if not trades:
        continue
    wins = sum(1 for o in trades if o['status'] == 'Profit')
    pnl = sum(o['pnl'] for o in trades)
    wr = (wins/len(trades)*100) if trades else 0
    avg = pnl/len(trades) if trades else 0
    print(f"{name:<12} {len(trades):<8} {wins:<6} {len(trades)-wins:<6} {wr:<8.1f} ${pnl:>12.2f} ${avg:>8.2f}")

print('\n' + '='*80)
print('ENTRY HOUR + DURATION MATRIX')
print('='*80)
print('(Shows P/L for each entry hour x duration combination)')
print()

# Create matrix
matrix = defaultdict(lambda: defaultdict(lambda: {'count': 0, 'pnl': 0, 'wins': 0}))
duration_labels = ['<15m', '15-60m', '1-2h', '2h+']

for o in orders:
    hour = o['entry_hour']
    dur = o['duration_mins']
    if dur < 15:
        dur_label = '<15m'
    elif dur < 60:
        dur_label = '15-60m'
    elif dur < 120:
        dur_label = '1-2h'
    else:
        dur_label = '2h+'
    
    matrix[hour][dur_label]['count'] += 1
    matrix[hour][dur_label]['pnl'] += o['pnl']
    if o['status'] == 'Profit':
        matrix[hour][dur_label]['wins'] += 1

# Print header
header = f"{'Hour':<8}"
for dl in duration_labels:
    header += f"{dl:>15}"
header += f"{'TOTAL':>15}"
print(header)
print('-' * 75)

for hour in sorted(matrix.keys()):
    row = f"{hour:02d}:00   "
    row_total = 0
    for dl in duration_labels:
        stats = matrix[hour][dl]
        if stats['count'] > 0:
            row += f"${stats['pnl']:>8.0f}({stats['count']:>2})"
            row_total += stats['pnl']
        else:
            row += f"{'---':>14}"
    row += f"${row_total:>12.0f}"
    print(row)

print()
print('KEY FINDINGS:')
print()

# Find best and worst combos
all_combos = []
for hour in matrix:
    for dl in duration_labels:
        stats = matrix[hour][dl]
        if stats['count'] >= 3:
            all_combos.append((hour, dl, stats['pnl'], stats['count'], stats['wins']))

best = sorted(all_combos, key=lambda x: x[2], reverse=True)[:5]
worst = sorted(all_combos, key=lambda x: x[2])[:5]

print('✅ BEST combos (entry hour + duration):')
for h, d, pnl, cnt, wins in best:
    wr = (wins/cnt*100) if cnt > 0 else 0
    print(f"   {h:02d}:00 + {d}: ${pnl:.0f} ({cnt} trades, {wr:.0f}% win)")

print()
print('🔴 WORST combos (entry hour + duration):')
for h, d, pnl, cnt, wins in worst:
    wr = (wins/cnt*100) if cnt > 0 else 0
    print(f"   {h:02d}:00 + {d}: ${pnl:.0f} ({cnt} trades, {wr:.0f}% win)")

# Quick trades analysis
print()
print('='*80)
print('QUICK TRADES (< 15 min) BY HOUR')
print('='*80)
quick = [o for o in orders if o['duration_mins'] < 15]
quick_by_hour = defaultdict(lambda: {'count': 0, 'pnl': 0, 'wins': 0})
for o in quick:
    quick_by_hour[o['entry_hour']]['count'] += 1
    quick_by_hour[o['entry_hour']]['pnl'] += o['pnl']
    if o['status'] == 'Profit':
        quick_by_hour[o['entry_hour']]['wins'] += 1

print(f"\n{'Hour':<8} {'Trades':<8} {'Win%':<8} {'P/L':<12}")
print('-' * 40)
for hour in sorted(quick_by_hour.keys()):
    s = quick_by_hour[hour]
    wr = (s['wins']/s['count']*100) if s['count'] > 0 else 0
    print(f"{hour:02d}:00    {s['count']:<8} {wr:<8.0f} ${s['pnl']:>8.2f}")

# Long-running winners
print()
print('='*80)
print('LONG-RUNNING TRADES (2+ hours)')
print('='*80)
long_trades = [o for o in orders if o['duration_mins'] >= 120]
long_by_hour = defaultdict(lambda: {'count': 0, 'pnl': 0, 'wins': 0})
for o in long_trades:
    long_by_hour[o['entry_hour']]['count'] += 1
    long_by_hour[o['entry_hour']]['pnl'] += o['pnl']
    if o['status'] == 'Profit':
        long_by_hour[o['entry_hour']]['wins'] += 1

print(f"\n{'Hour':<8} {'Trades':<8} {'Win%':<8} {'P/L':<12}")
print('-' * 40)
for hour in sorted(long_by_hour.keys()):
    s = long_by_hour[hour]
    wr = (s['wins']/s['count']*100) if s['count'] > 0 else 0
    print(f"{hour:02d}:00    {s['count']:<8} {wr:<8.0f} ${s['pnl']:>8.2f}")
