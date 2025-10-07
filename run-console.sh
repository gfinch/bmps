#!/bin/bash
# Console client runner script for BMPS
# This script runs the console client with the provided date range

if [ $# -ne 2 ]; then
    echo "Usage: $0 <start-date> <end-date>"
    echo "Example: $0 2025-09-01 2025-09-20"
    exit 1
fi

START_DATE=$1
END_DATE=$2

echo "Running BMPS Console Client..."
echo "Date range: $START_DATE to $END_DATE"
echo ""

# Run using sbt
sbt "console/run $START_DATE $END_DATE"
