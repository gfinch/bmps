#!/usr/bin/env python3
# inspect_parquet.py - quick inspector for parquet files

import sys
import pyarrow.parquet as pq
import pyarrow as pa
import pandas as pd


def inspect(path, rows=10):
    print("="*80)
    print("File:", path)
    pf = pq.ParquetFile(path)
    print("num_row_groups:", pf.num_row_groups)
    try:
        meta = pf.metadata
        print("num_rows (metadata):", meta.num_rows)
    except Exception as e:
        print("metadata error:", e)

    # Arrow schema
    try:
        schema = pf.schema_arrow
        print("\nArrow schema:")
        print(schema)
    except Exception as e:
        print("schema error:", e)

    # Physical column info from parquet metadata (row 0)
    try:
        print("\nPhysical column info (first row group):")
        rg = 0 if pf.num_row_groups>0 else None
        if rg is not None:
            rgmeta = pf.metadata.row_group(rg)
            for i in range(rgmeta.num_columns):
                col = rgmeta.column(i)
                print(f"  column[{i}] name={col.path_in_schema} phys_type={col.physical_type} encodings={col.encodings}")
    except Exception as e:
        print("parquet metadata error:", e)

    # Read small portion as pandas (may load whole file if small)
    try:
        tbl = pq.read_table(path)
        print("\nColumns:", tbl.column_names)
        df = tbl.to_pandas()
        print(f"\nFirst {rows} rows (pandas):")
        print(df.head(rows))
        if 'timestamp' in df.columns:
            print("\nSample types for 'timestamp' column values (first non-null up to 10):")
            nonnull = df['timestamp'].dropna().head(10)
            for v in nonnull:
                print("  python type:", type(v), "value:", v)
            # show pandas dtype
            print("pandas dtype:", df['timestamp'].dtype)
        else:
            print("\nNo 'timestamp' column found in table.")
    except Exception as e:
        print("read_table error:", e)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: inspect_parquet.py <file1.parquet> [file2.parquet ...]")
        sys.exit(1)
    for fp in sys.argv[1:]:
        inspect(fp)
