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

### Local Development

1. Ensure Scala and sbt are installed.
2. Clone the repo and run `sbt compile`.

### Docker Deployment

1. **Prerequisites**
   - Docker and Docker Compose installed
   - API keys for Databento and Polygon.io

2. **Configuration**
   ```bash
   # Copy the example environment file
   cp .env.example .env
   
   # Edit .env and add your API keys
   # NEVER commit .env to version control!
   nano .env
   ```

3. **Build and Run**
   ```bash
   # Build the Docker image
   docker-compose build
   
   # Start the application
   docker-compose up -d
   
   # View logs
   docker-compose logs -f bmps-core
   
   # Stop the application
   docker-compose down
   ```

4. **Custom Configuration**
   - Edit `core/src/main/resources/application.conf` to configure:
     - Broker accounts (Simulated or Tradovate)
     - Risk parameters
     - Data source selection (Databento or Parquet)

## Running

### Local Development
- Core: `sbt core/run`
- Web: `sbt web/run` (if needed)
- Console: `sbt console/run`

### Docker
```bash
# Run with docker-compose (recommended)
docker-compose up

# Or run the image directly
docker run -d \
  -p 8081:8081 \
  -e DATABENTO_KEY=your_key \
  -e POLY_KEY=your_key \
  -e BMPS_PORT=8081 \
  -e BMPS_READ_ONLY_MODE=false \
  --name bmps-core \
  bmps-core:latest
```

## REST API

The core application exposes a REST API on port 8081 (configurable via `BMPS_PORT`):

- **Health Check**: `GET /health`
- **Phase Control**: `POST /phase/start`, `POST /phase/stop`
- **Event Store**: `GET /events`, `GET /events/:date`
- **Reports**: `GET /reports/daily/:date`

Access the API at `http://localhost:8081` when running.

## Modules

- `core/`: fs2-based streaming library.
- `web/`: http4s-based web server.
- `console/`: Simple console client.

## Where to find most relevant contracts (most volume):
- https://www.cmegroup.com/markets/equities/sp/e-mini-sandp500.volume.html


