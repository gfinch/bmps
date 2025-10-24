# BMPS Combined Container

This directory contains the setup for running both the BMPS Core (Scala) and py-bmps API (Python) in a single Docker container.

## Overview

The combined container runs both services together:
- **py-bmps API**: Python FastAPI service serving XGBoost models on port 8001 (internal)
- **BMPS Core**: Scala application providing the main REST API on port 8081 (external)

The Core application communicates with the py-bmps API internally via HTTP on localhost:8001.

## Files

- `Dockerfile.combined` - Multi-stage Dockerfile that builds both applications
- `docker-compose.combined.yml` - Docker Compose file for local development
- `push-combined-to-ecr.sh` - Script to build and push to AWS ECR
- `build-combined.sh` - Local build and run script
- `start.sh` - Container startup script that manages both services

## Quick Start

### Local Development

1. **Build and run locally:**
   ```bash
   ./build-combined.sh
   ```

2. **Or use docker-compose:**
   ```bash
   docker-compose -f docker-compose.combined.yml up --build
   ```

### Production Deployment

1. **Push to ECR:**
   ```bash
   ./push-combined-to-ecr.sh
   ```

2. **Update your ECS task definition** to use the new image and only expose port 8081.

## Environment Variables

Required:
- `DATABENTO_KEY` - API key for market data

Optional:
- `BMPS_PORT` - Port for Core API (default: 8081)
- `BMPS_READ_ONLY_MODE` - Run in read-only mode (default: false)
- `TRADOVATE_PASS` - Tradovate password (if using Tradovate broker)
- `TRADOVATE_KEY` - Tradovate API key
- `TRADOVATE_DEVICE` - Tradovate device ID

## Ports

- **8081**: BMPS Core REST API (expose this externally)
- **8001**: py-bmps API (internal only, do not expose)

## Health Checks

Both services provide health endpoints:
- Core: `http://localhost:8081/health`
- Python API: `http://localhost:8001/health`

The combined container health check validates both services.

## Architecture Changes

### What Changed
- Single container now runs both applications
- py-bmps API starts first, then Core waits for it to be ready
- Internal communication via localhost:8001
- Only Core API (port 8081) needs external exposure

### Benefits
- Simplified deployment (single container)
- No network configuration between services
- Faster startup and communication
- Single point of management

### Considerations
- Both services scale together (can't scale independently)
- Resource allocation is shared
- If either service fails, the whole container restarts

## Migration from Separate Containers

1. **Build the new combined image**
2. **Update your ECS task definition:**
   - Replace the separate bmps-core and py-bmps containers with the combined one
   - Only expose port 8081
   - Use the same environment variables
3. **Deploy the updated task definition**

The API contract remains exactly the same, so no application code changes are needed.

## Troubleshooting

### Check container logs:
```bash
docker-compose -f docker-compose.combined.yml logs -f
```

### Access container shell:
```bash
docker exec -it bmps-combined bash
```

### Verify services are running:
```bash
# Inside container
curl http://localhost:8001/health  # Python API
curl http://localhost:8081/health  # Core API
```

### Build issues:
- Ensure you have enough Docker memory allocated (recommend 4GB+)
- Check that all required files are present (especially model files in py-bmps/models)

## Development Notes

For development, you can still run the services separately if needed:
- Core: `sbt core/run`
- Python API: `cd py-bmps && python api.py`

The combined container is optimized for production deployment where both services need to run together.