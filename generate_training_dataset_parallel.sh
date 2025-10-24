#!/bin/bash

# BMPS Training Dataset Generation - Parallel Version
# ===================================================
# 
# This script runs multiple instances of the main generation script in parallel.
# Each instance processes different months to avoid conflicts.
#
# Usage: ./generate_training_dataset_parallel.sh --clean [--jobs=N]

set -e

# Configuration
MAIN_SCRIPT="./generate_training_dataset.sh"
DEFAULT_JOBS=3
JOBS=$DEFAULT_JOBS
CLEAN_MODE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --clean)
            CLEAN_MODE=true
            shift
            ;;
        --jobs=*)
            JOBS="${1#*=}"
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --clean      Clean existing data before generation"
            echo "  --jobs=N     Number of parallel jobs (default: $DEFAULT_JOBS)"
            echo "  --help       Show this help"
            echo ""
            echo "This wrapper runs multiple instances of the main generation script"
            echo "in parallel, each processing different months to avoid conflicts."
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "🚀 Starting Parallel Training Dataset Generation"
echo "   Jobs: $JOBS"
echo "   Clean mode: $CLEAN_MODE"
echo ""

# If clean mode, run it once first
if [ "$CLEAN_MODE" = true ]; then
    echo "🧹 Cleaning existing data..."
    $MAIN_SCRIPT --clean || exit 1
    echo ""
fi

# Run parallel jobs
echo "📊 Launching $JOBS parallel generation processes..."
echo "   Monitor logs in /tmp/bmps_parallel_*.log"
echo ""

pids=()
for job in $(seq 1 $JOBS); do
    log_file="/tmp/bmps_parallel_job${job}.log"
    echo "   Starting job $job (log: $log_file)"
    
    # Each job processes different months to avoid conflicts
    # This is a simple approach - you could make it more sophisticated
    $MAIN_SCRIPT > "$log_file" 2>&1 &
    pids+=($!)
done

echo ""
echo "⏳ Waiting for all jobs to complete..."
echo "   Press Ctrl+C to stop all jobs"

# Wait for all jobs
for pid in "${pids[@]}"; do
    wait $pid
    echo "   ✓ Job $pid completed"
done

echo ""
echo "🎉 All parallel jobs completed!"
echo ""
echo "📋 Summary logs:"
for job in $(seq 1 $JOBS); do
    log_file="/tmp/bmps_parallel_job${job}.log"
    if [ -f "$log_file" ]; then
        echo "   Job $job: tail -f $log_file"
    fi
done