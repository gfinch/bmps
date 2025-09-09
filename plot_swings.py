import pyarrow.parquet as pq
import pandas as pd
import json

# Read the Parquet file
df = pq.read_table('core/src/main/resources/samples/es_futures_1h_60days.parquet').to_pandas()

# Convert timestamp to epoch ms
df['timestamp'] = (df['timestamp'].astype(int) // 10**6).astype(int)

# Select relevant columns
df = df[['open', 'high', 'low', 'close', 'timestamp']]

# Save to JSON
df.to_json('candles.json', orient='records')

print("Candles saved to candles.json")
