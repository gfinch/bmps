#!/bin/bash

# BMPS Training Dataset Generation - Stop Script
# ==============================================
# 
# This script gracefully stops the training dataset generation by creating a stop file.
# The main generation script will detect this file and stop after completing the current month.

STOP_FILE="/tmp/stop_bmps_generation"

echo "Creating stop signal for BMPS training dataset generation..."
touch "$STOP_FILE"

echo "✓ Stop signal created at: $STOP_FILE"
echo ""
echo "The dataset generation will stop after the current month completes."
echo "To resume generation later, delete this file and restart the script:"
echo "  rm $STOP_FILE"
echo "  ./generate_training_dataset.sh"