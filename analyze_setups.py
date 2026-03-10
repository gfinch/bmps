#!/usr/bin/env python3
"""
Deep analysis to identify patterns that differentiate good vs bad setups.
Goal: Find filters to reduce quick-stop entries without losing big winners.
"""

import csv
from datetime import datetime, timedelta
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
            entry = float(row['Entry Price'])
            exit_price = float(row['Exit Price'])
            
            orders.append({
                'open_time': open_time,
                'close_time': close_time,
                'date': row['Trading Date'],
                'duration_mins': duration_mins,
                'direction': row['Direction'],
                'entry': entry,
                'exit': exit_price,
                'pnl': float(row['Profit/Loss']),
                'status': row['Status'],
                'entry_hour': open_time.hour,
                'entry_minute': open_time.minute,
                # Stop distance in points
                'stop_dist': abs(entry - exit_price) if row['Status'] == 'Loss' else None,
            })
        except Exception as e:
            continue

# Categorize trades
quick_stops = [o for o in orders if o['duration_mins'] < 15]  # Stopped out quickly
big_winners = [o for o in orders if o['duration_mins'] >= 60 and o['status'] == 'Profit']
medium_trades = [o for o in orders if 15 <= o['duration_mins'] < 60]

print("="*80)
print("SETUP QUALITY ANALYSIS")
print("="*80)
print(f"\nQuick stops (<15 min): {len(quick_stops)} trades, ${sum(o['pnl'] for o in quick_stops):.0f}")
print(f"Big winners (1h+): {len(big_winners)} trades, ${sum(o['pnl'] for o in big_winners):.0f}")
print(f"Medium trades (15-60 min): {len(medium_trades)} trades, ${sum(o['pnl'] for o in medium_trades):.0f}")

# ===========================================================================
# ANALYSIS 1: Entry time within the hour (minute precision)
# ===========================================================================
print("\n" + "="*80)
print("ANALYSIS 1: ENTRY MINUTE WITHIN HOUR")
print("="*80)
print("Do quick stops cluster at certain minutes?")

minute_buckets = [
    ('00-09 min', 0, 10),
    ('10-19 min', 10, 20),
    ('20-29 min', 20, 30),
    ('30-39 min', 30, 40),
    ('40-49 min', 40, 50),
    ('50-59 min', 50, 60)
]

print(f"\n{'Minute':<12} {'QuickStops':<12} {'Winners1h+':<12} {'QS P/L':<12} {'Win P/L':<12}")
print("-"*60)

for name, lo, hi in minute_buckets:
    qs = [o for o in quick_stops if lo <= o['entry_minute'] < hi]
    win = [o for o in big_winners if lo <= o['entry_minute'] < hi]
    qs_pnl = sum(o['pnl'] for o in qs) if qs else 0
    win_pnl = sum(o['pnl'] for o in win) if win else 0
    print(f"{name:<12} {len(qs):<12} {len(win):<12} ${qs_pnl:<10.0f} ${win_pnl:<10.0f}")

# ===========================================================================
# ANALYSIS 2: Time since last trade (same day)
# ===========================================================================
print("\n" + "="*80)
print("ANALYSIS 2: TIME SINCE LAST TRADE (same day)")
print("="*80)
print("Are quick stops happening too soon after previous trades?")

daily_orders = defaultdict(list)
for o in orders:
    daily_orders[o['date']].append(o)

time_gap_stats = defaultdict(lambda: {'quick_stops': [], 'winners': []})

for date, day_orders in daily_orders.items():
    day_orders.sort(key=lambda x: x['open_time'])
    for i, o in enumerate(day_orders):
        if i == 0:
            gap = 999  # First trade
        else:
            gap = (o['open_time'] - day_orders[i-1]['close_time']).total_seconds() / 60
        
        if gap < 5:
            bucket = '< 5 min'
        elif gap < 15:
            bucket = '5-15 min'
        elif gap < 30:
            bucket = '15-30 min'
        elif gap < 60:
            bucket = '30-60 min'
        else:
            bucket = '60+ min or 1st'
        
        if o['duration_mins'] < 15:
            time_gap_stats[bucket]['quick_stops'].append(o)
        elif o['duration_mins'] >= 60 and o['status'] == 'Profit':
            time_gap_stats[bucket]['winners'].append(o)

print(f"\n{'Gap to last trade':<18} {'QuickStops':<12} {'Winners1h+':<12} {'QS P/L':<12} {'Win P/L':<12}")
print("-"*70)
for bucket in ['< 5 min', '5-15 min', '15-30 min', '30-60 min', '60+ min or 1st']:
    qs = time_gap_stats[bucket]['quick_stops']
    win = time_gap_stats[bucket]['winners']
    qs_pnl = sum(o['pnl'] for o in qs) if qs else 0
    win_pnl = sum(o['pnl'] for o in win) if win else 0
    print(f"{bucket:<18} {len(qs):<12} {len(win):<12} ${qs_pnl:<10.0f} ${win_pnl:<10.0f}")

# ===========================================================================
# ANALYSIS 3: Direction after previous trade result
# ===========================================================================
print("\n" + "="*80)
print("ANALYSIS 3: DIRECTION REVERSAL AFTER LOSS")
print("="*80)
print("Do reversals after losses perform worse?")

reversal_stats = {
    'same_after_win': {'qs': [], 'big': []},
    'reverse_after_win': {'qs': [], 'big': []},
    'same_after_loss': {'qs': [], 'big': []},
    'reverse_after_loss': {'qs': [], 'big': []},
    'first': {'qs': [], 'big': []}
}

for date, day_orders in daily_orders.items():
    day_orders.sort(key=lambda x: x['open_time'])
    for i, o in enumerate(day_orders):
        if i == 0:
            cat = 'first'
        else:
            prev = day_orders[i-1]
            same_dir = (o['direction'] == prev['direction'])
            prev_won = (prev['status'] == 'Profit')
            
            if prev_won and same_dir:
                cat = 'same_after_win'
            elif prev_won and not same_dir:
                cat = 'reverse_after_win'
            elif not prev_won and same_dir:
                cat = 'same_after_loss'
            else:
                cat = 'reverse_after_loss'
        
        if o['duration_mins'] < 15:
            reversal_stats[cat]['qs'].append(o)
        elif o['duration_mins'] >= 60 and o['status'] == 'Profit':
            reversal_stats[cat]['big'].append(o)

print(f"\n{'Pattern':<22} {'QuickStops':<12} {'Winners1h+':<12} {'QS P/L':<12} {'Win P/L':<12}")
print("-"*70)
for cat in ['first', 'same_after_win', 'reverse_after_win', 'same_after_loss', 'reverse_after_loss']:
    qs = reversal_stats[cat]['qs']
    win = reversal_stats[cat]['big']
    qs_pnl = sum(o['pnl'] for o in qs) if qs else 0
    win_pnl = sum(o['pnl'] for o in win) if win else 0
    print(f"{cat:<22} {len(qs):<12} {len(win):<12} ${qs_pnl:<10.0f} ${win_pnl:<10.0f}")

# ===========================================================================
# ANALYSIS 4: Long vs Short by time of day
# ===========================================================================
print("\n" + "="*80)
print("ANALYSIS 4: DIRECTION BY HOUR (which direction works when)")
print("="*80)

dir_hour_stats = defaultdict(lambda: {'long_qs': 0, 'long_win': 0, 'short_qs': 0, 'short_win': 0,
                                       'long_qs_pnl': 0, 'long_win_pnl': 0, 'short_qs_pnl': 0, 'short_win_pnl': 0})

for o in orders:
    hour = o['entry_hour']
    direction = o['direction']
    is_qs = o['duration_mins'] < 15
    is_big_win = o['duration_mins'] >= 60 and o['status'] == 'Profit'
    
    if direction == 'Long':
        if is_qs:
            dir_hour_stats[hour]['long_qs'] += 1
            dir_hour_stats[hour]['long_qs_pnl'] += o['pnl']
        if is_big_win:
            dir_hour_stats[hour]['long_win'] += 1
            dir_hour_stats[hour]['long_win_pnl'] += o['pnl']
    else:
        if is_qs:
            dir_hour_stats[hour]['short_qs'] += 1
            dir_hour_stats[hour]['short_qs_pnl'] += o['pnl']
        if is_big_win:
            dir_hour_stats[hour]['short_win'] += 1
            dir_hour_stats[hour]['short_win_pnl'] += o['pnl']

print(f"\n{'Hour':<8} {'Long QS':<10} {'Long Win':<10} {'Short QS':<10} {'Short Win':<10} {'Long P/L':<12} {'Short P/L':<12}")
print("-"*85)
for hour in sorted(dir_hour_stats.keys()):
    s = dir_hour_stats[hour]
    long_total_pnl = s['long_qs_pnl'] + s['long_win_pnl']
    short_total_pnl = s['short_qs_pnl'] + s['short_win_pnl']
    long_count = s['long_qs'] + s['long_win']
    short_count = s['short_qs'] + s['short_win']
    # Only show QS and big wins to keep it clean
    print(f"{hour:02d}:00    {s['long_qs']:<10} {s['long_win']:<10} {s['short_qs']:<10} {s['short_win']:<10} ${s['long_qs_pnl'] + s['long_win_pnl']:<10.0f} ${s['short_qs_pnl'] + s['short_win_pnl']:<10.0f}")

# ===========================================================================
# ANALYSIS 5: Position in day (nth trade)
# ===========================================================================
print("\n" + "="*80)
print("ANALYSIS 5: TRADE POSITION IN DAY")
print("="*80)
print("Which position in the day produces most quick stops vs winners?")

position_stats = defaultdict(lambda: {'qs': [], 'big': []})

for date, day_orders in daily_orders.items():
    day_orders.sort(key=lambda x: x['open_time'])
    for i, o in enumerate(day_orders):
        pos = min(i + 1, 7)  # Group 7+ together
        if o['duration_mins'] < 15:
            position_stats[pos]['qs'].append(o)
        elif o['duration_mins'] >= 60 and o['status'] == 'Profit':
            position_stats[pos]['big'].append(o)

print(f"\n{'Position':<12} {'QuickStops':<12} {'Winners1h+':<12} {'QS P/L':<12} {'Win P/L':<12} {'Ratio':<10}")
print("-"*70)
for pos in sorted(position_stats.keys()):
    qs = position_stats[pos]['qs']
    win = position_stats[pos]['big']
    qs_pnl = sum(o['pnl'] for o in qs) if qs else 0
    win_pnl = sum(o['pnl'] for o in win) if win else 0
    ratio = len(win) / len(qs) if len(qs) > 0 else float('inf')
    pos_label = f"Trade #{pos}" if pos < 7 else "Trade #7+"
    print(f"{pos_label:<12} {len(qs):<12} {len(win):<12} ${qs_pnl:<10.0f} ${win_pnl:<10.0f} {ratio:<10.2f}")

# ===========================================================================
# ANALYSIS 6: Day of Week
# ===========================================================================
print("\n" + "="*80)
print("ANALYSIS 6: DAY OF WEEK")
print("="*80)

dow_names = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
dow_stats = defaultdict(lambda: {'qs': [], 'big': [], 'all': []})

for o in orders:
    dow = o['open_time'].weekday()
    dow_stats[dow]['all'].append(o)
    if o['duration_mins'] < 15:
        dow_stats[dow]['qs'].append(o)
    elif o['duration_mins'] >= 60 and o['status'] == 'Profit':
        dow_stats[dow]['big'].append(o)

print(f"\n{'Day':<8} {'Trades':<10} {'QuickStops':<12} {'Winners1h+':<12} {'QS P/L':<12} {'Win P/L':<12} {'Total P/L':<12}")
print("-"*85)
for dow in range(5):  # Mon-Fri
    qs = dow_stats[dow]['qs']
    win = dow_stats[dow]['big']
    all_trades = dow_stats[dow]['all']
    qs_pnl = sum(o['pnl'] for o in qs)
    win_pnl = sum(o['pnl'] for o in win)
    total_pnl = sum(o['pnl'] for o in all_trades)
    print(f"{dow_names[dow]:<8} {len(all_trades):<10} {len(qs):<12} {len(win):<12} ${qs_pnl:<10.0f} ${win_pnl:<10.0f} ${total_pnl:<10.0f}")

# ===========================================================================
# SPECIFIC RECOMMENDATIONS
# ===========================================================================
print("\n" + "="*80)
print("SPECIFIC FILTER RECOMMENDATIONS")
print("="*80)

# Calculate impact of each filter
print("\n1. TIME GAP FILTER - Wait X minutes after a trade closes before next entry:")
for min_gap in [5, 10, 15, 20, 30]:
    avoided_qs = 0
    avoided_qs_pnl = 0
    missed_winners = 0
    missed_winners_pnl = 0
    
    for date, day_orders in daily_orders.items():
        day_orders.sort(key=lambda x: x['open_time'])
        for i, o in enumerate(day_orders):
            if i == 0:
                continue
            gap = (o['open_time'] - day_orders[i-1]['close_time']).total_seconds() / 60
            if gap < min_gap:
                if o['duration_mins'] < 15:
                    avoided_qs += 1
                    avoided_qs_pnl += o['pnl']
                elif o['duration_mins'] >= 60 and o['status'] == 'Profit':
                    missed_winners += 1
                    missed_winners_pnl += o['pnl']
    
    net = -avoided_qs_pnl - missed_winners_pnl  # pnl avoided (negative) minus pnl missed
    print(f"   {min_gap} min gap: Avoid {avoided_qs} QS (${-avoided_qs_pnl:.0f}), Miss {missed_winners} winners (${missed_winners_pnl:.0f}), Net: ${net:.0f}")

print("\n2. SKIP FIRST TRADE FILTER:")
first_qs = [o for o in quick_stops if any(daily_orders[o['date']][0] == o for _ in [1])]
first_win = [o for o in big_winners if any(daily_orders[o['date']][0] == o for _ in [1])]
# Actually calculate it properly
first_qs_count = 0
first_qs_pnl = 0
first_win_count = 0
first_win_pnl = 0
for date, day_orders in daily_orders.items():
    day_orders.sort(key=lambda x: x['open_time'])
    first = day_orders[0]
    if first['duration_mins'] < 15:
        first_qs_count += 1
        first_qs_pnl += first['pnl']
    elif first['duration_mins'] >= 60 and first['status'] == 'Profit':
        first_win_count += 1
        first_win_pnl += first['pnl']

print(f"   Would avoid {first_qs_count} QS (${-first_qs_pnl:.0f}), miss {first_win_count} big winners (${first_win_pnl:.0f})")
print(f"   Net impact: ${-first_qs_pnl - first_win_pnl:.0f}")

print("\n3. DIRECTION-SPECIFIC TIME FILTERS:")
# Shorts in early hours vs Longs
early_short_qs = [o for o in quick_stops if o['direction'] == 'Short' and o['entry_hour'] < 11]
early_short_win = [o for o in big_winners if o['direction'] == 'Short' and o['entry_hour'] < 11]
early_long_qs = [o for o in quick_stops if o['direction'] == 'Long' and o['entry_hour'] < 11]
early_long_win = [o for o in big_winners if o['direction'] == 'Long' and o['entry_hour'] < 11]

print(f"   Early (9-10) Shorts: {len(early_short_qs)} QS (${sum(o['pnl'] for o in early_short_qs):.0f}), {len(early_short_win)} winners (${sum(o['pnl'] for o in early_short_win):.0f})")
print(f"   Early (9-10) Longs: {len(early_long_qs)} QS (${sum(o['pnl'] for o in early_long_qs):.0f}), {len(early_long_win)} winners (${sum(o['pnl'] for o in early_long_win):.0f})")

# Lunch hour direction
lunch_short_qs = [o for o in quick_stops if o['direction'] == 'Short' and 12 <= o['entry_hour'] <= 13]
lunch_short_win = [o for o in big_winners if o['direction'] == 'Short' and 12 <= o['entry_hour'] <= 13]
lunch_long_qs = [o for o in quick_stops if o['direction'] == 'Long' and 12 <= o['entry_hour'] <= 13]
lunch_long_win = [o for o in big_winners if o['direction'] == 'Long' and 12 <= o['entry_hour'] <= 13]

print(f"   Lunch (12-13) Shorts: {len(lunch_short_qs)} QS (${sum(o['pnl'] for o in lunch_short_qs):.0f}), {len(lunch_short_win)} winners (${sum(o['pnl'] for o in lunch_short_win):.0f})")
print(f"   Lunch (12-13) Longs: {len(lunch_long_qs)} QS (${sum(o['pnl'] for o in lunch_long_qs):.0f}), {len(lunch_long_win)} winners (${sum(o['pnl'] for o in lunch_long_win):.0f})")

print("\n4. AVOID REVERSAL AFTER LOSS:")
rev_after_loss_qs = reversal_stats['reverse_after_loss']['qs']
rev_after_loss_win = reversal_stats['reverse_after_loss']['big']
print(f"   Would avoid {len(rev_after_loss_qs)} QS (${-sum(o['pnl'] for o in rev_after_loss_qs):.0f}), miss {len(rev_after_loss_win)} winners (${sum(o['pnl'] for o in rev_after_loss_win):.0f})")
print(f"   Net: ${-sum(o['pnl'] for o in rev_after_loss_qs) - sum(o['pnl'] for o in rev_after_loss_win):.0f}")
