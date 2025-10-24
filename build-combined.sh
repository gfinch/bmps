#!/bin/bash

# Build and run the combined BMPS container
# This script builds both the Scala core and Python API into a single container

set -e

echo "Building BMPS Combined Container..."

# Check if required environment variables are set
if [ -z "$DATABENTO_KEY" ]; then
    echo "Warning: DATABENTO_KEY environment variable is not set"
    echo "You'll need to set this for the application to work properly"
fi

# Build the combined image
echo "Building Docker image..."
docker build -f Dockerfile.combined -t bmps-combined:latest .

echo "Build completed successfully!"

# Ask if user wants to run the container
read -p "Do you want to start the combined container now? (y/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Starting container with docker-compose..."
    docker-compose -f docker-compose.combined.yml up -d
    
    echo "Container started! Checking status..."
    sleep 5
    
    # Check container status
    docker-compose -f docker-compose.combined.yml ps
    
    echo ""
    echo "Services are starting up. You can check the logs with:"
    echo "  docker-compose -f docker-compose.combined.yml logs -f"
    echo ""
    echo "Health checks:"
    echo "  Python API: curl http://localhost:8001/health"
    echo "  Core API:   curl http://localhost:8081/health"
    echo ""
    echo "To stop the container:"
    echo "  docker-compose -f docker-compose.combined.yml down"
fi

echo "Done!"