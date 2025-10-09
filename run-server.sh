#!/bin/bash
# Server launcher script for BMPS
# This script runs the AppLauncher server with access to the DATABENTO_KEY environment variable

# Check if DATABENTO_KEY is set
if [ -z "$DATABENTO_KEY" ]; then
    echo "Error: DATABENTO_KEY environment variable is not set"
    echo ""
    echo "To set it for this session, run:"
    echo "  export DATABENTO_KEY=your_api_key"
    echo ""
    echo "Or run this script with the key as an argument:"
    echo "  ./run-server.sh your_api_key"
    echo ""
    
    # Check if key was provided as argument
    if [ $# -eq 1 ]; then
        export DATABENTO_KEY=$1
        echo "Using DATABENTO_KEY from command line argument"
    else
        exit 1
    fi
fi

echo "Starting BMPS Server (AppLauncher)..."
echo "DATABENTO_KEY is set: ${DATABENTO_KEY:0:8}..." # Show first 8 chars for verification
echo ""

# Run using sbt with environment variable explicitly set
DATABENTO_KEY="$DATABENTO_KEY" sbt "core/run"
