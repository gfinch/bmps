#!/usr/bin/env python3
"""
Script to fix the incorrect labels in training data.

The original bug was in MarketFeaturesService.computeLabels where orders were 
being evaluated against the entry candle instead of progressing through future candles.

This script:
1. Reads existing training data with incorrect labels
2. For each trade timestamp, finds the next 20 candles from the raw candle data
3. Simulates orders correctly using the future candles
4. Generates corrected labels and updates the training files
"""

import pandas as pd
import numpy as np
import json
import os
import glob
from typing import List, Dict, Tuple, Optional
import argparse
from dataclasses import dataclass

@dataclass
class Candle:
    timestamp: int
    open: float
    high: float
    low: float
    close: float
    volume: int

@dataclass
class Order:
    order_type: str  # "Long" or "Short"
    entry_price: float
    stop_loss_ticks: int
    profit_target_ticks: int
    entry_timestamp: int
    placed_timestamp: int
    tick_size: float = 0.25
    
    @property
    def stop_loss_price(self) -> float:
        if self.order_type == "Long":
            return self.entry_price - (self.stop_loss_ticks * self.tick_size)
        else:  # Short
            return self.entry_price + (self.stop_loss_ticks * self.tick_size)
    
    @property
    def profit_target_price(self) -> float:
        if self.order_type == "Long":
            return self.entry_price + (self.profit_target_ticks * self.tick_size)
        else:  # Short
            return self.entry_price - (self.profit_target_ticks * self.tick_size)

def simulate_order(order: Order, future_candles: List[Candle]) -> bool:
    """
    Simulate an order through future candles.
    Returns True if order hits profit target, False if hits stop loss or expires.
    """
    for candle in future_candles:
        if order.order_type == "Long":
            # Check if stop loss hit (candle low touches stop loss)
            if candle.low <= order.stop_loss_price:
                return False
            # Check if profit target hit (candle high touches profit target)
            if candle.high >= order.profit_target_price:
                return True
        else:  # Short
            # Check if stop loss hit (candle high touches stop loss)
            if candle.high >= order.stop_loss_price:
                return False
            # Check if profit target hit (candle low touches profit target)
            if candle.low <= order.profit_target_price:
                return True
    
    # If we get here, order expired without hitting either target
    return False

def get_candles_around_timestamp(candle_df: pd.DataFrame, timestamp: int, lag_candles: int = 20) -> List[Candle]:
    """
    Get the next lag_candles candles after the given timestamp.
    """
    # Find candles after the timestamp
    future_mask = candle_df['timestamp'] > timestamp
    future_candles_df = candle_df[future_mask].head(lag_candles)
    
    candles = []
    for _, row in future_candles_df.iterrows():
        candles.append(Candle(
            timestamp=int(row['timestamp']),
            open=float(row['open']),
            high=float(row['high']),
            low=float(row['low']),
            close=float(row['close']),
            volume=int(row['volume'])
        ))
    
    return candles

def generate_correct_labels(entry_timestamp: int, candle_df: pd.DataFrame, lag_candles: int = 20) -> List[float]:
    """
    Generate corrected labels for a given entry timestamp.
    Returns a list of 194 labels (97 stop loss levels * 2 order types).
    """
    # Get future candles
    future_candles = get_candles_around_timestamp(candle_df, entry_timestamp, lag_candles)
    
    if len(future_candles) < lag_candles:
        print(f"Warning: Only {len(future_candles)} future candles available for timestamp {entry_timestamp}")
    
    if not future_candles:
        # No future candles available, return all zeros
        return [0.0] * 194
    
    # Get entry price from the first future candle (this is what the original code intended)
    entry_candle = future_candles[0]
    entry_price = entry_candle.close
    simulation_candles = future_candles[1:]  # Skip the entry candle for simulation
    
    # Generate orders for all combinations
    stop_loss_ticks = list(range(4, 101))  # 4 to 100 ticks
    order_types = ["Long", "Short"]
    
    labels = []
    
    for stop_loss in stop_loss_ticks:
        for order_type in order_types:
            # Create order with 2:1 risk/reward ratio
            profit_target = stop_loss * 2
            
            order = Order(
                order_type=order_type,
                entry_price=entry_price,
                stop_loss_ticks=stop_loss,
                profit_target_ticks=profit_target,
                entry_timestamp=entry_timestamp,
                placed_timestamp=entry_candle.timestamp
            )
            
            # Simulate the order
            is_winner = simulate_order(order, simulation_candles)
            labels.append(1.0 if is_winner else 0.0)
    
    return labels

def fix_training_file(file_path: str, candle_df: pd.DataFrame, dry_run: bool = False) -> Dict[str, int]:
    """
    Fix labels in a single training data file.
    Returns statistics about the fixes applied.
    """
    print(f"Processing {file_path}...")
    
    # Read training data
    df = pd.read_parquet(file_path)
    
    stats = {
        'total_rows': len(df),
        'rows_with_all_zeros': 0,
        'rows_fixed': 0,
        'total_labels_changed': 0
    }
    
    # Process each row
    for idx, row in df.iterrows():
        timestamp = row['timestamp']
        current_labels = json.loads(row['label_vector'])
        
        # Track rows with all zeros for statistics
        if sum(current_labels) == 0:
            stats['rows_with_all_zeros'] += 1
        
        # Generate corrected labels for ALL rows (the bug affected every row)
        corrected_labels = generate_correct_labels(timestamp, candle_df)
        
        # Always update labels since the bug affected all rows
        stats['rows_fixed'] += 1
        stats['total_labels_changed'] += sum(1 for old, new in zip(current_labels, corrected_labels) if old != new)
        
        # Update the label vector
        df.at[idx, 'label_vector'] = json.dumps(corrected_labels)
        
        if idx % 50 == 0:  # Progress indicator
            print(f"  Processed {idx+1}/{len(df)} rows, fixed {stats['rows_fixed']} rows so far")
    
    # Save the updated file (only if not dry run)
    if not dry_run and stats['rows_fixed'] > 0:
        df.to_parquet(file_path, index=False)
        print(f"  Saved updated file with {stats['rows_fixed']} fixed rows")
    elif dry_run:
        print(f"  DRY RUN: Would fix {stats['rows_fixed']} rows")
    else:
        print(f"  No rows needed fixing")
    
    return stats

def main():
    parser = argparse.ArgumentParser(description='Fix incorrect labels in training data')
    parser.add_argument('--data-dir', default='py-bmps/data', help='Directory containing training data')
    parser.add_argument('--candle-file', default='core/src/main/resources/backtest/es-1m_bk.parquet', 
                       help='Path to candle data file')
    parser.add_argument('--dry-run', action='store_true', help='Show what would be done without making changes')
    parser.add_argument('--file-pattern', default='training_data_*.parquet', help='Pattern for training files')
    parser.add_argument('--date-filter', help='Process only files containing this date (e.g., 2024-10)')
    
    args = parser.parse_args()
    
    # Load candle data
    print(f"Loading candle data from {args.candle_file}...")
    candle_df = pd.read_parquet(args.candle_file)
    print(f"Loaded {len(candle_df)} candles")
    
    # Filter to ES symbol only (if needed)
    if 'symbol' in candle_df.columns:
        candle_df = candle_df[candle_df['symbol'] == 'ES'].copy()
        print(f"Filtered to {len(candle_df)} ES candles")
    
    # Sort by timestamp for efficient lookups
    candle_df = candle_df.sort_values('timestamp').reset_index(drop=True)
    
    # Find training files
    search_pattern = os.path.join(args.data_dir, '**', args.file_pattern)
    training_files = glob.glob(search_pattern, recursive=True)
    
    if args.date_filter:
        training_files = [f for f in training_files if args.date_filter in f]
    
    print(f"Found {len(training_files)} training files to process")
    
    # Process each file
    total_stats = {
        'total_rows': 0,
        'rows_with_all_zeros': 0,
        'rows_fixed': 0,
        'total_labels_changed': 0
    }
    
    for file_path in sorted(training_files):
        try:
            file_stats = fix_training_file(file_path, candle_df, args.dry_run)
            
            # Accumulate stats
            for key in total_stats:
                total_stats[key] += file_stats[key]
                
            print(f"  File stats: {file_stats}")
            
        except Exception as e:
            print(f"Error processing {file_path}: {e}")
            continue
    
    print("\n=== SUMMARY ===")
    print(f"Total rows processed: {total_stats['total_rows']}")
    print(f"Rows with all-zero labels: {total_stats['rows_with_all_zeros']}")
    print(f"Rows fixed: {total_stats['rows_fixed']}")
    print(f"Total labels changed: {total_stats['total_labels_changed']}")
    
    if args.dry_run:
        print("\nThis was a DRY RUN - no files were modified")
        print("Remove --dry-run flag to apply the fixes")

if __name__ == "__main__":
    main()