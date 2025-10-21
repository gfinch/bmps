#!/bin/bash

# BMPS Training Dataset Generation - Progress Checker
# ==================================================
# 
# Shows current progress and estimates remaining work

OUTPUT_DIR="/tmp/training_datasets"
PROGRESS_FILE="$OUTPUT_DIR/.generation_progress"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}BMPS Training Dataset Generation - Progress Report${NC}"
echo "========================================================="

# Function to check if a month should be skipped for validation
should_skip_month() {
    local year=$1
    local month=$2
    
    case $year in
        2020|2021|2022) return 1 ;;
        2023) [ $((month % 2)) -eq 0 ] && return 0 || return 1 ;;
        2024) [ $((month % 2)) -eq 1 ] && return 0 || return 1 ;;
        2025) [ $((month % 2)) -eq 0 ] && return 0 || return 1 ;;
        *) return 1 ;;
    esac
}

# Function to check if a month is completed
is_month_completed() {
    local year=$1
    local month=$2
    local output_subdir="$OUTPUT_DIR/$year-$(printf '%02d' $month)"
    
    if [ -d "$output_subdir" ]; then
        local parquet_count=$(find "$output_subdir" -name "*.parquet" -type f 2>/dev/null | wc -l)
        [ $parquet_count -gt 0 ] && return 0
    fi
    return 1
}

# Count totals
total_months=0
completed_months=0
skipped_months=0
remaining_months=0

echo "Scanning months from 2020-01 to 2025-10..."
echo ""

for year in {2020..2025}; do
    if [ $year -eq 2025 ]; then
        max_month=10
    else
        max_month=12
    fi
    
    for month in $(seq 1 $max_month); do
        total_months=$((total_months + 1))
        
        if should_skip_month $year $month; then
            skipped_months=$((skipped_months + 1))
        elif is_month_completed $year $month; then
            completed_months=$((completed_months + 1))
        else
            remaining_months=$((remaining_months + 1))
        fi
    done
done

# Show progress
echo -e "${GREEN}✓ Completed months: $completed_months${NC}"
echo -e "${YELLOW}⚠ Validation holdouts: $skipped_months${NC}"
echo -e "${RED}⏳ Remaining to process: $remaining_months${NC}"
echo "─────────────────────────────────"
echo "📊 Total months in range: $total_months"

if [ $total_months -gt 0 ]; then
    training_months=$((completed_months + remaining_months))
    completed_pct=$(echo "scale=1; $completed_months * 100 / $training_months" | bc -l 2>/dev/null || echo "0")
    echo "🎯 Training progress: $completed_pct% complete"
fi

echo ""

# Show recent progress from log
if [ -f "$PROGRESS_FILE" ]; then
    echo -e "${BLUE}Recent Activity:${NC}"
    tail -5 "$PROGRESS_FILE" | while read line; do
        if [[ "$line" == *"FAILED"* ]]; then
            echo -e "  ${RED}$line${NC}"
        else
            echo -e "  ${GREEN}$line${NC}"
        fi
    done
else
    echo -e "${YELLOW}No progress file found - generation hasn't started yet${NC}"
fi

# Show dataset size
if [ -d "$OUTPUT_DIR" ] && command -v du >/dev/null 2>&1; then
    total_size=$(du -sh "$OUTPUT_DIR" 2>/dev/null | cut -f1 || echo "Unknown")
    file_count=$(find "$OUTPUT_DIR" -name "*.parquet" -type f 2>/dev/null | wc -l)
    echo ""
    echo "📁 Current dataset: $file_count parquet files ($total_size)"
fi

echo ""

# Show next steps
if [ $remaining_months -eq 0 ]; then
    echo -e "${GREEN}🎉 All training months completed!${NC}"
    echo "Ready for model training."
elif [ $completed_months -eq 0 ]; then
    echo -e "${BLUE}🚀 Ready to start generation:${NC}"
    echo "  ./generate_training_dataset.sh"
else
    echo -e "${BLUE}📋 To resume generation:${NC}"
    echo "  ./generate_training_dataset.sh"
    echo ""
    echo -e "${BLUE}🛑 To stop current generation:${NC}"
    echo "  ./stop_training_dataset.sh"
fi