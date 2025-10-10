# Tradovate Broker Test Console

A simple interactive console application to test the `TradovateBroker` REST API integration.

## Overview

This console application provides an interactive menu-driven interface to:
- Place bracket orders (entry + take profit + stop loss)
- List all orders
- View open positions
- View closed positions
- Cancel orders
- Track complete order lifecycle from placement to position close

## Prerequisites

1. **Tradovate Account**: Sign up for a demo account at [Tradovate](https://www.tradovate.com/)
2. **API Access**: Get your API key from Tradovate's developer portal
3. **Scala & SBT**: Ensure you have SBT installed (`brew install sbt` on macOS)

## Configuration

The console will prompt you for the following on startup (or reads from environment variables):

- **Username**: Your Tradovate login username (env: `TRADOVATE_USER`)
- **Password**: Your Tradovate password (env: `TRADOVATE_PASS`)
- **Client ID**: Your API client ID / cid (env: `TRADOVATE_CID`)
- **Client Secret**: Your API client secret / sec (env: `TRADOVATE_KEY`)
- **Device ID**: **REQUIRED** - A permanent device identifier (env: `TRADOVATE_DEVICE`)
  - **Important**: This must be the same ID every time you authenticate from this machine
  - **Example**: `export TRADOVATE_DEVICE='my-laptop-2024'` or use a UUID you generate once
  - Tradovate tracks devices for security - changing this ID will create a new device session
- **Account ID**: Your numeric Tradovate account ID (e.g., `123456`)
  - **How to find**: Run `FindAccountId` utility (see below)
  - **Note**: This is different from your account name (e.g., "DEMO5364424")
- **Account Spec**: Your account name/specification (e.g., `DEMO5364424` or `Demo123`)
- **Symbol**: Trading symbol (default: `ESZ5` for E-mini S&P 500 December 2025)

## Running

### Quick Start

```bash
./run-tradovate-test.sh
```

### Manual Start

```bash
sbt "project console" run
```

### With Environment Variables (Recommended)

```bash
export TRADOVATE_USER="your_username"
export TRADOVATE_PASS="your_password"
export TRADOVATE_CID="your_client_id"
export TRADOVATE_KEY="your_client_secret"
export TRADOVATE_DEVICE="my-laptop-2024"  # Required: permanent device identifier
./run-tradovate-test.sh
```

### Find Your Account ID

Before running the broker test, discover your numeric account ID:

```bash
sbt "project console" "runMain bmps.console.FindAccountId"
```

This will authenticate and list all your accounts with their numeric IDs.

## Features

### 1. Place New Order
Enter order parameters:
- Entry price (e.g., 6780.0)
- Stop loss price (e.g., 6770.0)
- Take profit price (e.g., 6800.0)
- Contract type (ES or MES)
- Number of contracts (e.g., 1)

The app calculates and displays:
- Risk (entry - stop)
- Reward (takeProfit - entry)
- Risk-to-Reward ratio

### 2. List Orders
Shows all placed orders with:
- Order ID
- Entry, stop loss, and take profit levels
- Contract type and quantity
- Placement timestamp

### 3. List Open Positions
Displays currently open positions with:
- Fill price
- Stop loss and take profit levels
- Contract type and quantity
- Fill timestamp

### 4. List Closed Positions
Shows closed positions with:
- Final status (Profit/Loss/Cancelled)
- Fill and close prices
- Profit/Loss calculation
- Contract type and quantity
- Close timestamp

### 5. Cancel Order
Interactive order cancellation:
1. Displays list of active orders
2. Select order by number
3. Confirms cancellation

### 6. Track Order Lifecycle
Complete workflow tracking:
1. Place order
2. Monitor until filled
3. Track open position
4. Detect position close
5. Display final P/L

## Order Flow

```
Planned Order → Place Order → Placed Order → Fill → Open Position → Close → Closed Position
                                    ↓
                                 Cancel → Cancelled Order
```

## Example Session

```
=== Tradovate Broker Test Console ===

API Key []: your_key_here
Account ID [123456]: 987654
Account Spec [demo123]: demo987
Symbol [MESZ4]: MESZ4

✓ Broker initialized
  - Symbol: MESZ4
  - Account: demo987 (987654)
  - Mode: demo.tradovateapi.com

==================================================
Options:
  1. Place new order
  2. List orders
  3. List open positions
  4. List closed positions
  5. Cancel order
  6. Track order lifecycle
  0. Exit
==================================================

Choice: 1

--- Place Order ---
Entry price [6780.0]: 6780.0
Stop loss [6770.0]: 6770.0
Take profit [6800.0]: 6800.0
Contract type (ES/MES) [MES]: MES
Number of contracts [1]: 1

📤 Placing order...
   Entry: 6780.0
   Stop: 6770.0 (risk: 10.0)
   Target: 6800.0 (reward: 20.0)
   R:R = 2.0

✓ Order placed successfully!
   Order ID: 123456789
   Timestamp: Thu Oct 09 20:15:30 PDT 2025
```

## Architecture

### Components

- **TradovateBrokerTest.scala**: Main console application
- **TradovateBroker.scala**: REST API broker implementation (in `core` project)
- **Order Models**: Domain models for trading orders and positions

### Broker Features

- **HTTP Client**: Uses `java.net.http.HttpClient` for REST API calls
- **Retry Logic**: Exponential backoff for rate limits (HTTP 429/503)
- **OSO Orders**: One-Sends-Other bracket orders (entry + TP + SL)
- **Position Tracking**: Open and closed position monitoring

## API Endpoints Used

- `POST /order/placeoso` - Place bracket order
- `POST /order/cancelorder` - Cancel order
- `GET /order/list` - List orders
- `GET /orderVersion/deps` - Get order details
- `GET /position/list` - List positions
- `GET /fill/list` - List fills (for closed positions)

## Troubleshooting

### Compilation Errors
```bash
cd /Users/gf26229/personal/bmps
sbt clean compile
```

### API Connection Issues
- Verify API key is correct
- Check account ID and spec match your Tradovate account
- Ensure you're using demo environment for testing

### Order Not Filling
- Check market hours (futures trade nearly 24/5)
- Verify entry price is near current market price
- Ensure sufficient margin in account

### Rate Limiting
The broker automatically retries with exponential backoff:
- Initial delay: 1 second
- Max retries: 5
- Doubles each retry: 1s → 2s → 4s → 8s → 16s

## Development

### Project Structure
```
bmps/
├── core/                          # Core trading logic
│   └── src/main/scala/bmps/core/
│       ├── brokers/rest/
│       │   └── TradovateBroker.scala
│       └── models/
│           └── Order.scala
└── console/                       # Test console app
    └── src/main/scala/bmps/console/
        └── TradovateBrokerTest.scala
```

### Adding Features

To add new broker methods:
1. Implement in `TradovateBroker.scala`
2. Add menu option in `TradovateBrokerTest.scala`
3. Create flow function (e.g., `xxxFlow(broker)`)

## Safety Notes

⚠️ **This is demo/testing software**:
- Always use Tradovate's demo environment first
- Never risk real money until thoroughly tested
- Understand trading risks before going live
- Use proper risk management (stop losses, position sizing)

## License

See [LEGAL_DISCLAIMER.md](../LEGAL_DISCLAIMER.md) for important legal information.
