# Docker Deployment Guide for BMPS Core

This guide explains how to build and deploy the BMPS Core application using Docker.

## Overview

The BMPS Core application is packaged as a Docker container with:
- **Inbound Port**: REST API server (default: 8081)
- **Outbound Access**: 
  - Databento API for market data
  - Polygon.io API for market data
  - Tradovate broker API for live trading (optional)

## Quick Start

```bash
# 1. Copy environment file and configure
cp .env.example .env
nano .env  # Add your API keys

# 2. Build and start
./docker-run.sh

# 3. Check logs
./docker-run.sh logs
```

## Architecture

### Multi-Stage Build
The Dockerfile uses a multi-stage build:
1. **Builder Stage**: Compiles Scala code with sbt and creates a fat JAR
2. **Runtime Stage**: Minimal JRE image that runs the application

### Network Configuration
- **Inbound**: REST API on port 8081 (configurable)
- **Outbound**: HTTPS connections to:
  - Databento API (databento.com)
  - Polygon.io API (polygon.io)
  - Tradovate API (tradovateapi.com) - optional

## Configuration

### Environment Variables

Required:
- `DATABENTO_KEY` - API key for Databento market data

Optional:
- `BMPS_PORT` - REST API port (default: 8081)
- `BMPS_READ_ONLY_MODE` - Run mode (default: false)
  - `true`: Automatically process today's trading then exit
  - `false`: Run REST API server indefinitely
- `TRADOVATE_PASS` - Tradovate account password (only if using TradovateBroker)
- `TRADOVATE_KEY` - Tradovate API secret key (only if using TradovateBroker)
- `TRADOVATE_DEVICE` - Tradovate device ID (only if using TradovateBroker)

### Application Configuration

Edit `core/src/main/resources/application.conf` to configure:
- Broker accounts (Simulated or Tradovate)
  - Tradovate username, client-id, account-id, account-name, base-url
- Risk parameters per trade
- Data source selection (Databento or Parquet)

**Important**: All configuration except passwords/secrets goes in `application.conf`.
Only sensitive credentials (TRADOVATE_PASS, TRADOVATE_KEY, TRADOVATE_DEVICE) are passed as environment variables.

Changes to `application.conf` require rebuilding the image.

## Building

### Using Docker Compose (Recommended)
```bash
# Build the image
docker-compose build

# Or use the helper script
./docker-run.sh build
```

### Using Docker Directly
```bash
docker build -t bmps-core:latest .
```

## Running

### Using Docker Compose (Recommended)
```bash
# Start the application
docker-compose up -d

# View logs
docker-compose logs -f bmps-core

# Stop the application
docker-compose down
```

### Using the Helper Script
```bash
./docker-run.sh start    # Start the application
./docker-run.sh logs     # View logs
./docker-run.sh restart  # Restart the application
./docker-run.sh stop     # Stop the application
./docker-run.sh rebuild  # Rebuild and restart
./docker-run.sh clean    # Remove all Docker resources
```

### Using Docker Directly
```bash
docker run -d \
  --name bmps-core \
  -p 8081:8081 \
  -e DATABENTO_KEY=your_databento_key \
  -e BMPS_PORT=8081 \
  -e BMPS_READ_ONLY_MODE=false \
  -e TRADOVATE_PASS=your_tradovate_password \
  -e TRADOVATE_KEY=your_tradovate_key \
  -e TRADOVATE_DEVICE=your_device_id \
  -v $(pwd)/core/src/main/resources/application.conf:/app/application.conf:ro \
  bmps-core:latest
```

## Port Mapping

The application exposes a REST API on port 8081 by default. You can map this to a different host port:

```bash
# Map to port 9000 on host
docker run -p 9000:8081 ...

# Or in docker-compose.yml:
ports:
  - "9000:8081"
```

## Volume Mounts

### Configuration File
Mount a custom `application.conf`:
```yaml
volumes:
  - ./my-config.conf:/app/application.conf:ro
```

### Data Directory (Optional)
If using ParquetSource for backtesting, mount your data directory:
```yaml
volumes:
  - ./data:/app/data:ro
```

## Health Checks

The container includes a health check that pings the REST API:
```bash
# Check health status
docker inspect --format='{{json .State.Health}}' bmps-core | jq

# Health check endpoint
curl http://localhost:8081/health
```

## Troubleshooting

### View Logs
```bash
# Docker Compose
docker-compose logs -f bmps-core

# Docker direct
docker logs -f bmps-core
```

### Enter Container
```bash
# Docker Compose
docker-compose exec bmps-core sh

# Docker direct
docker exec -it bmps-core sh
```

### Check Environment Variables
```bash
docker exec bmps-core env | grep BMPS
```

### Rebuild After Config Changes
```bash
# Application.conf changes require rebuild
./docker-run.sh rebuild
```

## Security Considerations

1. **Never commit `.env` files** - they contain secrets
2. **Use read-only volume mounts** where possible
3. **Run as non-root user** - the container uses a dedicated `bmps` user
4. **Limit memory** - use `JAVA_OPTS` to set appropriate heap sizes
5. **Network isolation** - use Docker networks to isolate containers
6. **API key security** - store keys in `.env` and use Docker secrets in production

## Production Deployment

For production deployments:

1. **Use Docker secrets** instead of environment variables:
   ```yaml
   secrets:
     - databento_key
     - tradovate_pass
     - tradovate_key
     - tradovate_device
   ```

2. **Set resource limits**:
   ```yaml
   deploy:
     resources:
       limits:
         cpus: '2'
         memory: 4G
   ```

3. **Configure logging**:
   ```yaml
   logging:
     driver: "json-file"
     options:
       max-size: "10m"
       max-file: "5"
   ```

4. **Use reverse proxy** (nginx, traefik) for TLS termination

5. **Monitor with** Docker stats, Prometheus, or similar tools

## REST API Endpoints

Once running, the following endpoints are available:

- `GET /health` - Health check
- `POST /phase/start` - Start a trading phase
- `POST /phase/stop` - Stop current phase
- `GET /events` - Get all events
- `GET /events/:date` - Get events for a specific date
- `GET /reports/daily/:date` - Get daily trading report

Example:
```bash
curl http://localhost:8081/health
curl http://localhost:8081/events/2024-10-12
```

## Networking

### Firewall Rules

**Inbound**:
- Allow TCP port 8081 (or your configured port) for REST API

**Outbound**:
- Allow HTTPS (443) to:
  - `*.databento.com`
  - `*.polygon.io`
  - `*.tradovateapi.com`

### Docker Network

The docker-compose configuration creates a bridge network `bmps-network`. To connect other services:

```yaml
services:
  my-service:
    networks:
      - bmps-network

networks:
  bmps-network:
    external: true
```

## Development vs Production

### Development
- Use demo Tradovate account
- Mount local config files for quick changes
- Use `BMPS_READ_ONLY_MODE=false` for manual control
- View logs in real-time

### Production
- Use production Tradovate account (with caution!)
- Bake configuration into image
- Use `BMPS_READ_ONLY_MODE=true` for automated runs
- Ship logs to centralized logging system

## Further Reading

- [Docker Documentation](https://docs.docker.com/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [BMPS LEGAL_DISCLAIMER.md](./LEGAL_DISCLAIMER.md)
