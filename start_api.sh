#!/bin/bash

# Simple script to start the BMPS Model API

cd py-bmps

# Check if virtual environment is activated
if [[ -z "$VIRTUAL_ENV" ]]; then
    echo "Activating virtual environment..."
    source ../venv/bin/activate
fi

# Install API dependencies if needed
echo "Installing API dependencies..."
pip install fastapi uvicorn pydantic

# Start the API server
echo "Starting BMPS Model API on http://localhost:8001"
echo "Press Ctrl+C to stop"
python api.py