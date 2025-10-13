#!/bin/bash
# Build and run the BMPS Core application using Docker

set -e

echo "=========================================="
echo "BMPS Core - Docker Build & Run Script"
echo "=========================================="
echo ""

# Check if .env file exists
if [ ! -f .env ]; then
    echo "⚠️  Warning: .env file not found!"
    echo "Creating .env from .env.example..."
    cp .env.example .env
    echo ""
    echo "✅ Created .env file"
    echo "⚠️  Please edit .env and add your API keys before running the application!"
    echo ""
    read -p "Press Enter to continue or Ctrl+C to exit..."
fi

# Parse command line arguments
ACTION=${1:-up}

case $ACTION in
    build)
        echo "🔨 Building Docker image..."
        docker-compose build
        echo "✅ Build complete!"
        ;;
    up|start)
        echo "🚀 Starting BMPS Core..."
        docker-compose up -d
        echo ""
        echo "✅ BMPS Core is running!"
        echo "📊 REST API: http://localhost:${BMPS_PORT:-8081}"
        echo ""
        echo "View logs with: docker-compose logs -f bmps-core"
        echo "Stop with: ./docker-run.sh stop"
        ;;
    down|stop)
        echo "🛑 Stopping BMPS Core..."
        docker-compose down
        echo "✅ Stopped!"
        ;;
    restart)
        echo "🔄 Restarting BMPS Core..."
        docker-compose restart
        echo "✅ Restarted!"
        ;;
    logs)
        echo "📋 Showing logs (Ctrl+C to exit)..."
        docker-compose logs -f bmps-core
        ;;
    rebuild)
        echo "🔨 Rebuilding and starting..."
        docker-compose down
        docker-compose build
        docker-compose up -d
        echo "✅ BMPS Core rebuilt and started!"
        ;;
    clean)
        echo "🧹 Cleaning up Docker resources..."
        docker-compose down -v
        docker rmi bmps-core:latest 2>/dev/null || true
        echo "✅ Cleanup complete!"
        ;;
    *)
        echo "Usage: ./docker-run.sh [command]"
        echo ""
        echo "Commands:"
        echo "  build     - Build the Docker image"
        echo "  up/start  - Start the application (default)"
        echo "  down/stop - Stop the application"
        echo "  restart   - Restart the application"
        echo "  logs      - View application logs"
        echo "  rebuild   - Rebuild and restart"
        echo "  clean     - Stop and remove all Docker resources"
        echo ""
        exit 1
        ;;
esac
