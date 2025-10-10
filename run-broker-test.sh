#!/bin/bash

# Run the Tradovate Broker Test console app
# Make sure environment variables are set:
# - TRADOVATE_USER
# - TRADOVATE_PASS
# - TRADOVATE_KEY
# - TRADOVATE_CID
# - TRADOVATE_DEVICE

cd "$(dirname "$0")"

echo "Starting Tradovate Broker Test..."
echo ""

# Check for required environment variables
if [ -z "$TRADOVATE_USER" ]; then
    echo "Error: TRADOVATE_USER environment variable not set"
    exit 1
fi

if [ -z "$TRADOVATE_PASS" ]; then
    echo "Error: TRADOVATE_PASS environment variable not set"
    exit 1
fi

if [ -z "$TRADOVATE_KEY" ]; then
    echo "Error: TRADOVATE_KEY environment variable not set"
    exit 1
fi

if [ -z "$TRADOVATE_CID" ]; then
    echo "Error: TRADOVATE_CID environment variable not set"
    exit 1
fi

if [ -z "$TRADOVATE_DEVICE" ]; then
    echo "Error: TRADOVATE_DEVICE environment variable not set"
    exit 1
fi

sbt "console/runMain bmps.console.TradovateBrokerTest"
