#!/usr/bin/env python3
"""
Fix the ET timestamp columns in aggregated_orders.csv.
The Unix timestamps are already adjusted to ET (not standard UTC timestamps),
so we just need to format them without any timezone conversion.
"""

import csv
from datetime import datetime, timezone
import sys

def unix_ms_to_et_string(unix_ms_str):
    """Convert Unix timestamp in milliseconds to formatted string (treating as UTC for display)."""
    if not unix_ms_str or unix_ms_str.strip() == '':
        return ''
    
    try:
        unix_ms = int(unix_ms_str)
        # Convert milliseconds to seconds
        unix_sec = unix_ms / 1000.0
        # Create datetime object in UTC (but the timestamp is actually already in ET)
        dt = datetime.fromtimestamp(unix_sec, tz=timezone.utc)
        # Format as YYYY-MM-DD HH:MM:SS (drop timezone info)
        return dt.strftime('%Y-%m-%d %H:%M:%S')
    except (ValueError, OSError) as e:
        print(f"Warning: Could not convert timestamp '{unix_ms_str}': {e}", file=sys.stderr)
        return unix_ms_str

def fix_csv_timestamps(input_file, output_file):
    """Read CSV, fix ET timestamp columns, and write to output."""
    rows_processed = 0
    
    # Read all data first
    with open(input_file, 'r', newline='', encoding='utf-8') as infile:
        reader = csv.reader(infile)
        
        # Read header
        header = next(reader)
        
        # Find indices of timestamp columns
        timestamp_idx = header.index('Timestamp')
        timestamp_et_idx = header.index('Timestamp_ET')
        placed_timestamp_idx = header.index('Placed_Timestamp')
        placed_timestamp_et_idx = header.index('Placed_Timestamp_ET')
        filled_timestamp_idx = header.index('Filled_Timestamp')
        filled_timestamp_et_idx = header.index('Filled_Timestamp_ET')
        close_timestamp_idx = header.index('Close_Timestamp')
        close_timestamp_et_idx = header.index('Close_Timestamp_ET')
        
        # Read all rows into memory
        all_rows = list(reader)
    
    # Now process and write
    with open(output_file, 'w', newline='', encoding='utf-8') as outfile:
        writer = csv.writer(outfile)
        
        # Write header
        writer.writerow(header)
        
        # Process data rows
        for row in all_rows:
            # Fix Timestamp_ET
            row[timestamp_et_idx] = unix_ms_to_et_string(row[timestamp_idx])
            
            # Fix Placed_Timestamp_ET
            row[placed_timestamp_et_idx] = unix_ms_to_et_string(row[placed_timestamp_idx])
            
            # Fix Filled_Timestamp_ET
            row[filled_timestamp_et_idx] = unix_ms_to_et_string(row[filled_timestamp_idx])
            
            # Fix Close_Timestamp_ET
            row[close_timestamp_et_idx] = unix_ms_to_et_string(row[close_timestamp_idx])
            
            writer.writerow(row)
            rows_processed += 1
    
    print(f"Successfully processed {rows_processed} rows")
    print(f"Fixed timestamps written to: {output_file}")

if __name__ == '__main__':
    input_file = 'aggregated_orders.csv'
    output_file = 'aggregated_orders.csv'
    
    print(f"Reading from: {input_file}")
    print(f"Writing to: {output_file}")
    print("Fixing ET timestamp columns...")
    
    fix_csv_timestamps(input_file, output_file)
    print("Done!")
