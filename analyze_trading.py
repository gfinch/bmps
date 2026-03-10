#!/usr/bin/env python3
"""
Trading Performance Analysis Script
Analyzes order data to find patterns and optimization opportunities.
"""

import csv
from datetime import datetime
from collections import defaultdict
from zoneinfo import ZoneInfo

ET_ZONE = ZoneInfo("America/New_York")

def parse_csv(filepath):
    """Parse the orders CSV file."""
    orders = []
    with open(filepath, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            # Stop at summary section
            if row.get('Open Time (ET)', '').startswith('==='):
                break
            if not row.get('Open Time (ET)'):
                continue
            try:
                orders.append({
                    'open_time': datetime.strptime(row['Open Time (ET)'], '%Y-%m-%d %H:%M:%S'),
                    'close_time': datetime.strptime(row['Close Time (ET)'], '%Y-%m-%d %H:%M:%S'),
                    'date': row['Trading Date'],
                    'direction': row['Direction'],
                    'entry': float(row['Entry Price']),
                    'exit': float(row['Exit Price']),
                    'pnl': float(row['Profit/Loss']),
                    'status': row['Status']
                })
            except (ValueError, KeyError):
                continue
    return orders


def analyze_time_of_day(orders):
    """Analyze performance by time of day."""
    print("\n" + "="*80)
    print("TIME OF DAY ANALYSIS")
    print("="*80)
    
    # Group by hour
    hourly_stats = defaultdict(lambda: {'wins': 0, 'losses': 0, 'pnl': 0, 'count': 0})
    
    for o in orders:
        hour = o['open_time'].hour
        hourly_stats[hour]['count'] += 1
        hourly_stats[hour]['pnl'] += o['pnl']
        if o['status'] == 'Profit':
            hourly_stats[hour]['wins'] += 1
        else:
            hourly_stats[hour]['losses'] += 1
    
    print(f"\n{'Hour':<8} {'Trades':<8} {'Wins':<8} {'Losses':<8} {'Win%':<10} {'Total P/L':<12} {'Avg P/L':<10}")
    print("-" * 70)
    
    for hour in sorted(hourly_stats.keys()):
        stats = hourly_stats[hour]
        win_rate = (stats['wins'] / stats['count'] * 100) if stats['count'] > 0 else 0
        avg_pnl = stats['pnl'] / stats['count'] if stats['count'] > 0 else 0
        print(f"{hour:02d}:00    {stats['count']:<8} {stats['wins']:<8} {stats['losses']:<8} {win_rate:<10.1f} ${stats['pnl']:>10.2f} ${avg_pnl:>8.2f}")
    
    # Find worst hours
    worst_hours = sorted(hourly_stats.items(), key=lambda x: x[1]['pnl'])[:3]
    best_hours = sorted(hourly_stats.items(), key=lambda x: x[1]['pnl'], reverse=True)[:3]
    
    print(f"\n⚠️  WORST HOURS: {', '.join(f'{h:02d}:00 (${s['pnl']:.0f})' for h, s in worst_hours)}")
    print(f"✅ BEST HOURS: {', '.join(f'{h:02d}:00 (${s['pnl']:.0f})' for h, s in best_hours)}")
    
    # Calculate impact of avoiding worst hour
    if worst_hours:
        worst_hour = worst_hours[0][0]
        avoided_pnl = sum(o['pnl'] for o in orders if o['open_time'].hour != worst_hour)
        worst_hour_loss = worst_hours[0][1]['pnl']
        print(f"\n💡 If you avoided {worst_hour:02d}:00 trades: Total P/L would be ${avoided_pnl:.2f} (+${-worst_hour_loss:.2f})")


def analyze_direction(orders):
    """Analyze Long vs Short performance."""
    print("\n" + "="*80)
    print("DIRECTION ANALYSIS (Long vs Short)")
    print("="*80)
    
    for direction in ['Long', 'Short']:
        dir_orders = [o for o in orders if o['direction'] == direction]
        wins = sum(1 for o in dir_orders if o['status'] == 'Profit')
        losses = len(dir_orders) - wins
        total_pnl = sum(o['pnl'] for o in dir_orders)
        win_rate = (wins / len(dir_orders) * 100) if dir_orders else 0
        avg_pnl = total_pnl / len(dir_orders) if dir_orders else 0
        
        print(f"\n{direction}:")
        print(f"  Trades: {len(dir_orders)}, Wins: {wins}, Losses: {losses}")
        print(f"  Win Rate: {win_rate:.1f}%")
        print(f"  Total P/L: ${total_pnl:.2f}, Avg P/L: ${avg_pnl:.2f}")


def analyze_daily_caps(orders):
    """Analyze impact of daily loss/win caps."""
    print("\n" + "="*80)
    print("DAILY CAP ANALYSIS")
    print("="*80)
    
    # Group orders by date
    daily_orders = defaultdict(list)
    for o in orders:
        daily_orders[o['date']].append(o)
    
    # Sort each day's orders by time
    for date in daily_orders:
        daily_orders[date].sort(key=lambda x: x['open_time'])
    
    actual_total = sum(o['pnl'] for o in orders)
    print(f"\nActual Total P/L: ${actual_total:.2f}")
    
    # Test various loss caps
    print(f"\n--- DAILY LOSS CAPS ---")
    loss_caps = [500, 750, 1000, 1500, 2000]
    
    for cap in loss_caps:
        capped_pnl = 0
        trades_avoided = 0
        for date, day_orders in daily_orders.items():
            running = 0
            for o in day_orders:
                if running <= -cap:
                    trades_avoided += 1
                    continue
                running += o['pnl']
                capped_pnl += o['pnl']
        
        diff = capped_pnl - actual_total
        print(f"  -${cap} daily loss cap: P/L = ${capped_pnl:.2f} ({'+' if diff >= 0 else ''}{diff:.2f}), {trades_avoided} trades avoided")
    
    # Test various win caps (stop trading after reaching daily target)
    print(f"\n--- DAILY WIN CAPS (stop after reaching target) ---")
    win_caps = [500, 1000, 1500, 2000, 2500]
    
    for cap in win_caps:
        capped_pnl = 0
        trades_avoided = 0
        for date, day_orders in daily_orders.items():
            running = 0
            for o in day_orders:
                if running >= cap:
                    trades_avoided += 1
                    continue
                running += o['pnl']
                capped_pnl += o['pnl']
        
        diff = capped_pnl - actual_total
        print(f"  +${cap} daily win cap: P/L = ${capped_pnl:.2f} ({'+' if diff >= 0 else ''}{diff:.2f}), {trades_avoided} trades avoided")
    
    # Combined caps
    print(f"\n--- COMBINED CAPS ---")
    combos = [(750, 1500), (1000, 2000), (500, 1000), (1000, 1500)]
    
    for loss_cap, win_cap in combos:
        capped_pnl = 0
        trades_avoided = 0
        for date, day_orders in daily_orders.items():
            running = 0
            for o in day_orders:
                if running <= -loss_cap or running >= win_cap:
                    trades_avoided += 1
                    continue
                running += o['pnl']
                capped_pnl += o['pnl']
        
        diff = capped_pnl - actual_total
        print(f"  Loss -${loss_cap} / Win +${win_cap}: P/L = ${capped_pnl:.2f} ({'+' if diff >= 0 else ''}{diff:.2f}), {trades_avoided} trades avoided")


def analyze_first_trade(orders):
    """Analyze first trade of day performance."""
    print("\n" + "="*80)
    print("FIRST TRADE OF DAY ANALYSIS")
    print("="*80)
    
    # Group by date and get first trade
    daily_orders = defaultdict(list)
    for o in orders:
        daily_orders[o['date']].append(o)
    
    first_trades = []
    for date, day_orders in daily_orders.items():
        day_orders.sort(key=lambda x: x['open_time'])
        if day_orders:
            first_trades.append(day_orders[0])
    
    wins = sum(1 for o in first_trades if o['status'] == 'Profit')
    total_pnl = sum(o['pnl'] for o in first_trades)
    win_rate = (wins / len(first_trades) * 100) if first_trades else 0
    
    print(f"\nFirst trades: {len(first_trades)}")
    print(f"Wins: {wins}, Losses: {len(first_trades) - wins}")
    print(f"Win Rate: {win_rate:.1f}%")
    print(f"Total P/L from first trades: ${total_pnl:.2f}")
    print(f"Average P/L: ${total_pnl/len(first_trades):.2f}")
    
    # Analyze first trade by hour
    first_by_hour = defaultdict(lambda: {'count': 0, 'pnl': 0, 'wins': 0})
    for o in first_trades:
        hour = o['open_time'].hour
        first_by_hour[hour]['count'] += 1
        first_by_hour[hour]['pnl'] += o['pnl']
        if o['status'] == 'Profit':
            first_by_hour[hour]['wins'] += 1
    
    print(f"\nFirst trade by start hour:")
    for hour in sorted(first_by_hour.keys()):
        stats = first_by_hour[hour]
        wr = (stats['wins']/stats['count']*100) if stats['count'] > 0 else 0
        print(f"  {hour:02d}:00 - {stats['count']} trades, Win Rate: {wr:.0f}%, P/L: ${stats['pnl']:.2f}")


def analyze_consecutive_patterns(orders):
    """Analyze patterns after wins/losses."""
    print("\n" + "="*80)
    print("CONSECUTIVE TRADE PATTERNS")
    print("="*80)
    
    # Group by date
    daily_orders = defaultdict(list)
    for o in orders:
        daily_orders[o['date']].append(o)
    
    # Track performance after consecutive losses
    after_1_loss = []
    after_2_losses = []
    after_3_losses = []
    
    for date, day_orders in daily_orders.items():
        day_orders.sort(key=lambda x: x['open_time'])
        consecutive_losses = 0
        
        for o in day_orders:
            if consecutive_losses == 1:
                after_1_loss.append(o)
            elif consecutive_losses == 2:
                after_2_losses.append(o)
            elif consecutive_losses >= 3:
                after_3_losses.append(o)
            
            if o['status'] == 'Loss':
                consecutive_losses += 1
            else:
                consecutive_losses = 0
    
    print("\n--- Performance AFTER consecutive losses (same day) ---")
    
    for name, trades in [("1 loss", after_1_loss), ("2 losses", after_2_losses), ("3+ losses", after_3_losses)]:
        if trades:
            wins = sum(1 for o in trades if o['status'] == 'Profit')
            pnl = sum(o['pnl'] for o in trades)
            wr = (wins/len(trades)*100) if trades else 0
            print(f"After {name}: {len(trades)} trades, Win Rate: {wr:.1f}%, P/L: ${pnl:.2f}")
    
    # What if we stopped after N consecutive losses?
    print("\n--- Impact of stopping after N consecutive losses ---")
    actual_total = sum(o['pnl'] for o in orders)
    
    for stop_after in [2, 3, 4]:
        stopped_pnl = 0
        trades_avoided = 0
        for date, day_orders in daily_orders.items():
            day_orders.sort(key=lambda x: x['open_time'])
            consecutive_losses = 0
            
            for o in day_orders:
                if consecutive_losses >= stop_after:
                    trades_avoided += 1
                    continue
                    
                stopped_pnl += o['pnl']
                
                if o['status'] == 'Loss':
                    consecutive_losses += 1
                else:
                    consecutive_losses = 0
        
        diff = stopped_pnl - actual_total
        print(f"Stop after {stop_after} losses: P/L = ${stopped_pnl:.2f} ({'+' if diff >= 0 else ''}{diff:.2f}), {trades_avoided} trades avoided")


def analyze_win_size(orders):
    """Analyze win/loss sizes."""
    print("\n" + "="*80)
    print("WIN/LOSS SIZE ANALYSIS")
    print("="*80)
    
    wins = [o for o in orders if o['status'] == 'Profit']
    losses = [o for o in orders if o['status'] == 'Loss']
    
    win_amounts = [o['pnl'] for o in wins]
    loss_amounts = [-o['pnl'] for o in losses]  # Make positive for comparison
    
    if win_amounts:
        print(f"\nWINS ({len(wins)} trades):")
        print(f"  Average: ${sum(win_amounts)/len(win_amounts):.2f}")
        print(f"  Median: ${sorted(win_amounts)[len(win_amounts)//2]:.2f}")
        print(f"  Max: ${max(win_amounts):.2f}")
        print(f"  Min: ${min(win_amounts):.2f}")
        
        # Distribution
        small = sum(1 for w in win_amounts if w < 200)
        medium = sum(1 for w in win_amounts if 200 <= w < 1000)
        large = sum(1 for w in win_amounts if w >= 1000)
        print(f"  Distribution: {small} small (<$200), {medium} medium ($200-$1000), {large} large (>$1000)")
    
    if loss_amounts:
        print(f"\nLOSSES ({len(losses)} trades):")
        print(f"  Average: ${sum(loss_amounts)/len(loss_amounts):.2f}")
        print(f"  Median: ${sorted(loss_amounts)[len(loss_amounts)//2]:.2f}")
        print(f"  Max: ${max(loss_amounts):.2f}")
        print(f"  Min: ${min(loss_amounts):.2f}")
        
        # Big loss analysis
        big_losses = [o for o in losses if o['pnl'] < -500]
        if big_losses:
            print(f"\n  BIG LOSSES (> $500): {len(big_losses)} trades, Total: ${sum(o['pnl'] for o in big_losses):.2f}")
            print(f"  Hours: {', '.join(f'{o['open_time'].hour:02d}:00' for o in big_losses[:10])}...")


def analyze_trade_position(orders):
    """Analyze performance by trade position in day."""
    print("\n" + "="*80)
    print("TRADE POSITION IN DAY ANALYSIS")
    print("="*80)
    
    daily_orders = defaultdict(list)
    for o in orders:
        daily_orders[o['date']].append(o)
    
    position_stats = defaultdict(lambda: {'count': 0, 'pnl': 0, 'wins': 0})
    
    for date, day_orders in daily_orders.items():
        day_orders.sort(key=lambda x: x['open_time'])
        for i, o in enumerate(day_orders):
            pos = min(i + 1, 6)  # Group 6+ together
            position_stats[pos]['count'] += 1
            position_stats[pos]['pnl'] += o['pnl']
            if o['status'] == 'Profit':
                position_stats[pos]['wins'] += 1
    
    print(f"\n{'Position':<12} {'Trades':<8} {'Win%':<10} {'Total P/L':<12} {'Avg P/L':<10}")
    print("-" * 55)
    
    for pos in sorted(position_stats.keys()):
        stats = position_stats[pos]
        wr = (stats['wins']/stats['count']*100) if stats['count'] > 0 else 0
        avg = stats['pnl']/stats['count'] if stats['count'] > 0 else 0
        pos_label = f"{pos}th" if pos < 6 else "6th+"
        print(f"Trade #{pos_label:<6} {stats['count']:<8} {wr:<10.1f} ${stats['pnl']:>10.2f} ${avg:>8.2f}")


def main():
    filepath = "/Users/gf26229/Downloads/3xOrders.csv"
    
    print("="*80)
    print("TRADING PERFORMANCE ANALYSIS")
    print("="*80)
    
    orders = parse_csv(filepath)
    print(f"\nLoaded {len(orders)} trades")
    
    total_pnl = sum(o['pnl'] for o in orders)
    wins = sum(1 for o in orders if o['status'] == 'Profit')
    losses = len(orders) - wins
    win_rate = (wins / len(orders) * 100) if orders else 0
    
    print(f"Total P/L: ${total_pnl:.2f}")
    print(f"Win Rate: {win_rate:.1f}% ({wins} wins, {losses} losses)")
    
    analyze_time_of_day(orders)
    analyze_direction(orders)
    analyze_daily_caps(orders)
    analyze_first_trade(orders)
    analyze_consecutive_patterns(orders)
    analyze_win_size(orders)
    analyze_trade_position(orders)
    
    # Final recommendations
    print("\n" + "="*80)
    print("💡 RECOMMENDATIONS SUMMARY")
    print("="*80)
    print("""
Based on the analysis above, consider these optimizations:

1. TIME-BASED FILTERS:
   - Check the hourly breakdown for consistently unprofitable hours
   - Early morning (9:30-10:00) often shows higher volatility/losses
   - Consider avoiding or reducing size during worst-performing hours

2. DAILY RISK MANAGEMENT:
   - Implement a daily loss cap (check the analysis for optimal level)
   - Consider a daily profit target to lock in gains
   - Combined caps can significantly reduce drawdowns

3. CONSECUTIVE LOSS RULES:
   - Stop trading after N consecutive losses to prevent tilt
   - This preserves capital for better setups

4. POSITION SIZING:
   - Review big loss trades for patterns
   - Consider reducing size after consecutive losses

5. FIRST TRADE CAUTION:
   - If first trades underperform, consider waiting for the market to settle
""")


if __name__ == "__main__":
    main()
