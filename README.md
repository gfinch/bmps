# BMPS - Backtesting and Market Pattern System

A Scala-based algorithmic trading research and backtesting framework for futures markets.

## ⚠️ Important Legal Notice

**This is personal educational software for individual use only.**

Before using this software, **you must read** [LEGAL_DISCLAIMER.md](./LEGAL_DISCLAIMER.md).

Key points:
- ✅ For personal research and education
- ✅ Each user runs their own independent instance
- ✅ Each user subscribes to their own market data
- ❌ Not investment advice
- ❌ No performance guarantees
- ❌ Use at your own risk

## Project Components

- **Core**: Streaming data processing, strategy backtesting, and live trading engine using fs2
- **Web**: Real-time visualization dashboard for monitoring strategies and positions
- **Console**: Command-line interface for backtesting and analysis

## Setup

1. Ensure Scala and sbt are installed.
2. Clone the repo and run `sbt compile`.

## Running

- Core: `sbt core/run` (if needed)
- Web: `sbt web/run`
- Console: `sbt console/run`

## Modules

- `core/`: fs2-based streaming library.
- `web/`: http4s-based web server.
- `console/`: Simple console client.

## Where to find most relevant contracts (most volume):
- https://www.cmegroup.com/markets/equities/sp/e-mini-sandp500.volume.html


