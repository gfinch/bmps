"""
Convert CSV files with Chicago timezone to Parquet with UTC timestamps.
CSV format: date;time;open;high;low;close;volume
Times are in America/Chicago timezone.
Output parquet will have UTC timestamps stored as int64 (epoch milliseconds).
"""

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
from datetime import datetime
import sys

def convert_csv_to_parquet_utc(csv_path, parquet_path, timeframe):
    """
    Read CSV with Chicago timezone and write Parquet with UTC timestamps.
    
    Args:
        csv_path: Path to input CSV file
        parquet_path: Path to output parquet file
        timeframe: Timeframe string (e.g., "1h", "1m")
    """
    print(f"Reading {csv_path}...")
    
    # Read CSV - semicolon separated, no header
    df = pd.read_csv(
        csv_path,
        sep=';',
        header=None,
        names=['date', 'time', 'open', 'high', 'low', 'close', 'volume']
    )
    
    print(f"Loaded {len(df)} rows")
    print(f"Sample data:\n{df.head()}")
    
    # Combine date and time columns
    df['datetime_str'] = df['date'] + ' ' + df['time']
    
    # Parse as Chicago timezone - European format DD/MM/YYYY
    df['timestamp'] = pd.to_datetime(
        df['datetime_str'], 
        format='%d/%m/%Y %H:%M'
    )
    
    # Localize to Chicago timezone
    df['timestamp'] = df['timestamp'].dt.tz_localize('America/Chicago', ambiguous='NaT', nonexistent='NaT')
    
    # Drop any rows with NaT (DST transition issues)
    before_drop = len(df)
    df = df.dropna(subset=['timestamp'])
    after_drop = len(df)
    if before_drop != after_drop:
        print(f"Dropped {before_drop - after_drop} rows with invalid timestamps (DST transitions)")
    
    # Convert to UTC
    df['timestamp'] = df['timestamp'].dt.tz_convert('UTC')
    
    # Convert to epoch milliseconds (int64)
    df['timestamp_ms'] = (df['timestamp'].astype('int64') / 1_000_000).astype('int64')
    
    # Add symbol and timeframe columns
    df['symbol'] = 'ES'
    df['timeframe'] = timeframe
    
    # Select and reorder columns for output
    output_df = df[['symbol', 'timestamp_ms', 'timeframe', 'open', 'high', 'low', 'close', 'volume']]
    output_df = output_df.rename(columns={'timestamp_ms': 'timestamp'})
    
    print(f"\nOutput DataFrame info:")
    print(f"Shape: {output_df.shape}")
    print(f"Columns: {output_df.columns.tolist()}")
    print(f"Dtypes:\n{output_df.dtypes}")
    print(f"\nFirst few rows:")
    print(output_df.head())
    print(f"\nTimestamp range:")
    print(f"  Min: {output_df['timestamp'].min()} ({pd.to_datetime(output_df['timestamp'].min(), unit='ms', utc=True)})")
    print(f"  Max: {output_df['timestamp'].max()} ({pd.to_datetime(output_df['timestamp'].max(), unit='ms', utc=True)})")
    
    # Create PyArrow schema with explicit types
    schema = pa.schema([
        ('symbol', pa.string()),
        ('timestamp', pa.int64()),  # UTC epoch milliseconds
        ('timeframe', pa.string()),
        ('open', pa.float64()),
        ('high', pa.float64()),
        ('low', pa.float64()),
        ('close', pa.float64()),
        ('volume', pa.int64())
    ])
    
    # Convert to PyArrow Table
    table = pa.Table.from_pandas(output_df, schema=schema)
    
    # Write to Parquet
    print(f"\nWriting to {parquet_path}...")
    pq.write_table(table, parquet_path, compression='snappy')
    
    print(f"✓ Successfully wrote {len(output_df)} rows to {parquet_path}")
    print(f"  File size: {pq.ParquetFile(parquet_path).metadata.serialized_size / 1024 / 1024:.2f} MB")

if __name__ == '__main__':
    # Convert both files
    files = [
        ('core/src/main/resources/backtest/es-1h_bk.csv', 
         'core/src/main/resources/backtest/es-1h_bk.parquet', 
         '1h'),
        ('core/src/main/resources/backtest/es-1m_bk.csv', 
         'core/src/main/resources/backtest/es-1m_bk.parquet', 
         '1m')
    ]
    
    for csv_path, parquet_path, timeframe in files:
        print(f"\n{'='*70}")
        print(f"Converting {csv_path}")
        print(f"{'='*70}")
        try:
            convert_csv_to_parquet_utc(csv_path, parquet_path, timeframe)
        except Exception as e:
            print(f"ERROR: {e}")
            import traceback
            traceback.print_exc()
    
    print(f"\n{'='*70}")
    print("Conversion complete!")
    print(f"{'='*70}")
