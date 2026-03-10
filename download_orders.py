#!/usr/bin/env python3
"""
One-off script to download orders from the BMPS API and export to CSV.

Usage:
    python download_orders.py [output_file] [account_id]
    
Examples:
    python download_orders.py                          # Uses defaults
    python download_orders.py orders.csv               # Custom output file
    python download_orders.py orders.csv MyAccount123  # With account ID
"""

import requests
import csv
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

API_URL = "http://localhost:8081"
ET_ZONE = ZoneInfo("America/New_York")


def fetch_aggregate_report(account_id: str | None = None) -> dict:
    """Fetch the aggregate order report from the API."""
    url = f"{API_URL}/aggregateOrderReport"
    if account_id:
        url += f"?accountId={account_id}"

    response = requests.get(url)
    response.raise_for_status()
    return response.json()


def format_timestamp(ts_millis: int | None) -> str:
    """Convert millisecond timestamp to readable ET datetime string."""
    if ts_millis is None:
        return ""
    dt = datetime.fromtimestamp(ts_millis / 1000, tz=ET_ZONE)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def get_trading_date(ts_millis: int) -> str:
    """Extract trading date (YYYY-MM-DD) from timestamp."""
    dt = datetime.fromtimestamp(ts_millis / 1000, tz=ET_ZONE)
    return dt.strftime("%Y-%m-%d")


def calculate_profit(order: dict) -> float | None:
    """Calculate profit/loss for a completed order."""
    status = order.get("status", {})

    # Handle status as either string or object
    if isinstance(status, str):
        status_name = status
    elif isinstance(status, dict):
        status_name = list(status.keys())[0] if status else ""
    else:
        status_name = ""

    if status_name not in ("Profit", "Loss"):
        return None

    entry = order.get("entryPrice", 0)
    exit_price = order.get("exitPrice")

    # If no exit price, use takeProfit for wins, stopLoss for losses
    if exit_price is None:
        if status_name == "Profit":
            exit_price = order.get("takeProfit", entry)
        else:
            exit_price = order.get("stopLoss", entry)

    # Get order type
    order_type = order.get("orderType", {})
    if isinstance(order_type, str):
        type_name = order_type
    elif isinstance(order_type, dict):
        type_name = list(order_type.keys())[0] if order_type else ""
    else:
        type_name = ""

    # Calculate profit based on direction
    contracts = order.get("contracts", 1)
    # Detect contract type: ES = $50 per point, MES = $5 per point
    contract_type = order.get("contractType", {})
    if isinstance(contract_type, dict):
        contract_name = list(contract_type.keys())[0] if contract_type else "MES"
    else:
        contract_name = str(contract_type) if contract_type else "MES"
    dollars_per_point = 50.0 if contract_name == "ES" else 5.0

    if type_name == "Long":
        profit = (exit_price - entry) * contracts * dollars_per_point
    else:  # Short
        profit = (entry - exit_price) * contracts * dollars_per_point

    return round(profit, 2)


def get_order_type(order: dict) -> str:
    """Extract order type (Long/Short) from order."""
    order_type = order.get("orderType", {})
    if isinstance(order_type, str):
        return order_type
    elif isinstance(order_type, dict):
        return list(order_type.keys())[0] if order_type else "Unknown"
    return "Unknown"


def get_status(order: dict) -> str:
    """Extract status from order."""
    status = order.get("status", {})
    if isinstance(status, str):
        return status
    elif isinstance(status, dict):
        return list(status.keys())[0] if status else "Unknown"
    return "Unknown"


def export_to_csv(report: dict, output_file: str):
    """Export orders to CSV with daily running totals."""
    orders = report.get("orders", [])

    # Filter to completed orders only and sort by close timestamp
    completed_orders = [
        o for o in orders
        if get_status(o) in ("Profit", "Loss") and o.get("closeTimestamp")
    ]
    completed_orders.sort(key=lambda o: o.get("closeTimestamp", 0))

    if not completed_orders:
        print("No completed orders found.")
        return

    with open(output_file, "w", newline="") as f:
        writer = csv.writer(f)

        # Write header
        writer.writerow([
            "Open Time (ET)",
            "Close Time (ET)",
            "Trading Date",
            "Direction",
            "Entry Price",
            "Exit Price",
            "Profit/Loss",
            "Daily Running Total",
            "Overall Running Total",
            "Status"
        ])

        # Track running totals
        overall_total = 0.0
        daily_totals = {}  # date -> running total for that day

        for order in completed_orders:
            filled_ts = order.get("filledTimestamp")
            open_time = format_timestamp(filled_ts)
            close_ts = order.get("closeTimestamp")
            close_time = format_timestamp(close_ts)
            trading_date = get_trading_date(close_ts)

            direction = get_order_type(order)
            entry_price = order.get("entryPrice", 0)

            # Get exit price
            exit_price = order.get("exitPrice")
            status = get_status(order)
            if exit_price is None:
                if status == "Profit":
                    exit_price = order.get("takeProfit", entry_price)
                else:
                    exit_price = order.get("stopLoss", entry_price)

            profit = calculate_profit(order) or 0.0

            # Update totals
            overall_total += profit
            if trading_date not in daily_totals:
                daily_totals[trading_date] = 0.0
            daily_totals[trading_date] += profit

            writer.writerow([
                open_time,
                close_time,
                trading_date,
                direction,
                f"{entry_price:.2f}",
                f"{exit_price:.2f}",
                f"{profit:.2f}",
                f"{daily_totals[trading_date]:.2f}",
                f"{overall_total:.2f}",
                status
            ])

        # Write daily summary section
        writer.writerow([])
        writer.writerow(["=== Daily Summary ==="])
        writer.writerow(["Date", "Daily P/L"])

        for date in sorted(daily_totals.keys()):
            writer.writerow([date, f"{daily_totals[date]:.2f}"])

        writer.writerow([])
        writer.writerow(["Total P/L", f"{overall_total:.2f}"])

    print(f"Exported {len(completed_orders)} orders to {output_file}")
    print(f"Total P/L: ${overall_total:.2f}")
    print(f"Trading days: {len(daily_totals)}")


def main():
    output_file = sys.argv[1] if len(sys.argv) > 1 else "orders_export.csv"
    account_id = sys.argv[2] if len(sys.argv) > 2 else None

    print("=" * 60)
    print("BMPS Order Export")
    print("=" * 60)
    print(f"Output file: {output_file}")
    print(f"Account ID: {account_id or '(lead account)'}")
    print()

    try:
        print("Fetching orders from API...")
        report = fetch_aggregate_report(account_id)
        print(f"Received {len(report.get('orders', []))} total orders")
        print()

        export_to_csv(report, output_file)
        print()
        print("Done!")

    except requests.RequestException as e:
        print(f"Error connecting to API: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
