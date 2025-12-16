import pandas as pd
import os


def add_volume():
    csv_path = '../console/strategy_analysis_full.csv'
    parquet_path = '../core/src/main/resources/databento/es-1m.parquet'

    print(f"Loading CSV from {csv_path}...")
    if not os.path.exists(csv_path):
        print(f"Error: {csv_path} not found.")
        return

    df_csv = pd.read_csv(csv_path)
    print(f"CSV loaded. Shape: {df_csv.shape}")

    # 1. Ensure Volume Exists
    if 'volume' not in df_csv.columns:
        print("Volume not found in CSV. Fetching from Parquet...")
        print(f"Loading Parquet from {parquet_path}...")
        if not os.path.exists(parquet_path):
            print(f"Error: {parquet_path} not found.")
            return

        df_parquet = pd.read_parquet(parquet_path)

        min_ts = df_csv['timestamp'].min()
        max_ts = df_csv['timestamp'].max()

        df_parquet = df_parquet[(df_parquet['timestamp'] >= min_ts) & (df_parquet['timestamp'] <= max_ts)]

        vol_col = 'volume'
        if 'volume' not in df_parquet.columns:
            if 'size' in df_parquet.columns:
                vol_col = 'size'
            else:
                print(f"Could not find volume column. Columns: {df_parquet.columns}")
                return

        print(f"Merging volume (from '{vol_col}')...")
        df_csv = pd.merge(df_csv, df_parquet[['timestamp', vol_col]], on='timestamp', how='left')

        if vol_col != 'volume':
            df_csv = df_csv.rename(columns={vol_col: 'volume'})

        df_csv['volume'] = df_csv['volume'].fillna(0)
    else:
        print("Volume column exists.")

    # 2. Calculate Relative Volume
    print("Calculating Relative Volume (20-period MA)...")
    # Ensure sorted by time
    df_csv = df_csv.sort_values('timestamp')

    # Calculate Rolling Average (shift 1 to avoid lookahead if we were strict,
    # but for 'current candle' relative volume, using previous 20 is standard)
    # We want: current volume / average of *previous* 20 volumes.
    # rolling(20).mean() includes current row by default.
    # So we should shift the volume column for the MA calculation?
    # Actually, standard Relative Volume is usually (Current Vol) / (Avg Vol of past N periods).
    # If we include current volume in the average, it dampens the signal of a spike.
    # So let's calculate MA of *previous* 20.

    df_csv['vol_ma_20'] = df_csv['volume'].shift(1).rolling(window=20).mean()

    # Avoid division by zero
    df_csv['vol_ma_20'] = df_csv['vol_ma_20'].replace(0, 1)
    df_csv['relativeVolume'] = df_csv['volume'] / df_csv['vol_ma_20']

    # Fill NaNs (first 20 rows)
    df_csv['relativeVolume'] = df_csv['relativeVolume'].fillna(1.0)

    print(f"Saving to {csv_path}...")
    df_csv.to_csv(csv_path, index=False)
    print("Done.")


if __name__ == "__main__":
    add_volume()
