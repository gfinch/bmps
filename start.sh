#!/bin/bash
set -e

echo "Starting BMPS Combined Container..."

# Start py-bmps API first
echo "Starting py-bmps API..."
cd /app/py-bmps
/app/venv/bin/python api.py &
PY_PID=$!

# Wait for py-bmps API to be ready
echo "Waiting for py-bmps API to be ready..."
for i in {1..30}; do
    if curl -f http://localhost:8001/health 2>/dev/null; then
        echo "py-bmps API is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "Timeout waiting for py-bmps API"
        exit 1
    fi
    echo "Attempt $i/30: Waiting for py-bmps API..."
    sleep 2
done

# Now start bmps-core
echo "Starting bmps-core..."
cd /app
java -Dconfig.file=/app/application.conf -jar /app/bmps-core.jar &
JAVA_PID=$!

# Function to handle shutdown
shutdown() {
    echo "Shutting down..."
    kill $JAVA_PID 2>/dev/null || true
    kill $PY_PID 2>/dev/null || true
    wait $JAVA_PID 2>/dev/null || true
    wait $PY_PID 2>/dev/null || true
}

# Trap signals to ensure clean shutdown
trap shutdown SIGTERM SIGINT

# Wait for both processes
wait $PY_PID $JAVA_PID