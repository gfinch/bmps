import pyarrow.parquet as pq
import pandas as pd
import sys

# Read the parquet file
parquet_file = sys.argv[1] if len(sys.argv) > 1 else "core/src/main/resources/backtest/es-1h_bk.parquet"

print("=== Parquet File Schema ===")
print(f"File: {parquet_file}\n")

# Get schema
table = pq.read_table(parquet_file)
schema = table.schema

print("Column Name          | Type                          | Nullable")
print("-" * 70)
for field in schema:
    nullable = "YES" if field.nullable else "NO"
    print(f"{field.name:<20} | {str(field.type):<28} | {nullable}")

# Get row count
print(f"\nTotal rows: {len(table)}")

# Show first few rows
print("\n=== Sample Data (first 5 rows) ===")
df = table.to_pandas()
print(df.head())

# Show timestamp details
print("\n=== Timestamp Column Analysis ===")
print(f"Timestamp dtype: {df['timestamp'].dtype}")
print(f"Min timestamp: {df['timestamp'].min()}")
print(f"Max timestamp: {df['timestamp'].max()}")

# If timestamp is integer, show what it represents
if pd.api.types.is_integer_dtype(df['timestamp']):
    print("\nTimestamp appears to be epoch milliseconds")
    print(f"Min date (UTC): {pd.to_datetime(df['timestamp'].min(), unit='ms', utc=True)}")
    print(f"Max date (UTC): {pd.to_datetime(df['timestamp'].max(), unit='ms', utc=True)}")
    print(f"Min date (Eastern): {pd.to_datetime(df['timestamp'].min(), unit='ms', utc=True).tz_convert('America/New_York')}")
    print(f"Max date (Eastern): {pd.to_datetime(df['timestamp'].max(), unit='ms', utc=True).tz_convert('America/New_York')}")

# Show all columns
print("\n=== All Columns ===")
print(df.columns.tolist())
