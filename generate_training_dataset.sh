#!/bin/bash

# BMPS Training Dataset Generation Script
# =====================================
# 
# Generates training datasets from January 1, 2020 to October 1, 2025
# with strategic validation holdouts for model testing.
#
# VALIDATION HOLDOUT STRATEGY:
# ---------------------------
# 2020-2022: All months included (full training data)
# 2023: SKIP even months (Feb, Apr, Jun, Aug, Oct, Dec) - RESERVED FOR VALIDATION
# 2024: SKIP odd months (Jan, Mar, May, Jul, Sep, Nov) - RESERVED FOR VALIDATION  
# 2025: SKIP even months (Feb, Apr, Jun, Aug, Oct) - RESERVED FOR VALIDATION
#
# This creates a temporal split where:
# - Training: ~70% of recent data (2023-2025 alternating months + all 2020-2022)
# - Validation: ~30% of recent data (held-out months from 2023-2025)
# - Ensures validation data spans multiple market regimes and seasons

set -e  # Exit on any error

# Signal handling for graceful shutdown
cleanup() {
    log_warning "Received interrupt signal. Stopping after current month completes..."
    STOP_PROCESSING=true
    
    # If we get a second interrupt, exit immediately
    trap 'log_error "Force quit! Exiting immediately..."; exit 130' SIGINT SIGTERM
}

# Trap signals (Ctrl+C = SIGINT, kill = SIGTERM)
trap cleanup SIGINT SIGTERM

# Configuration
OUTPUT_DIR="/tmp/training_datasets"
CONSOLE_PROJECT="console"
LAG_MINUTES=20

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging function
log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')] ✓${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')] ⚠${NC} $1"
}

log_error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')] ✗${NC} $1"
}

# Create output directory and progress tracking
mkdir -p "$OUTPUT_DIR"
PROGRESS_FILE="$OUTPUT_DIR/.generation_progress"

log "Starting BMPS Training Dataset Generation"
log "Output directory: $OUTPUT_DIR"
log "Lag minutes: $LAG_MINUTES"
echo ""
log_warning "To gracefully stop this script:"
log_warning "  1st Ctrl+C: Stops after current month completes"
log_warning "  2nd Ctrl+C: Force quit immediately"
log_warning "  Or create file: touch /tmp/stop_bmps_generation"
echo ""

# Function to check if a month should be skipped for validation
should_skip_month() {
    local year=$1
    local month=$2
    
    case $year in
        2020|2021|2022)
            # Include all months for 2020-2022 (full training data)
            return 1  # Don't skip
            ;;
        2023)
            # Skip even months in 2023 (Feb=2, Apr=4, Jun=6, Aug=8, Oct=10, Dec=12)
            if [ $((month % 2)) -eq 0 ]; then
                return 0  # Skip (validation holdout)
            else
                return 1  # Don't skip (training data)
            fi
            ;;
        2024)
            # Skip odd months in 2024 (Jan=1, Mar=3, May=5, Jul=7, Sep=9, Nov=11)
            if [ $((month % 2)) -eq 1 ]; then
                return 0  # Skip (validation holdout)
            else
                return 1  # Don't skip (training data)
            fi
            ;;
        2025)
            # Skip even months in 2025 (Feb=2, Apr=4, Jun=6, Aug=8, Oct=10)
            if [ $((month % 2)) -eq 0 ]; then
                return 0  # Skip (validation holdout)
            else
                return 1  # Don't skip (training data)
            fi
            ;;
        *)
            log_error "Unexpected year: $year"
            return 1
            ;;
    esac
}

# Function to get month name
get_month_name() {
    case $1 in
        1) echo "January" ;;
        2) echo "February" ;;
        3) echo "March" ;;
        4) echo "April" ;;
        5) echo "May" ;;
        6) echo "June" ;;
        7) echo "July" ;;
        8) echo "August" ;;
        9) echo "September" ;;
        10) echo "October" ;;
        11) echo "November" ;;
        12) echo "December" ;;
    esac
}

# Function to get the last day of a month
get_last_day_of_month() {
    local year=$1
    local month=$2
    
    # Use date command to get last day of month
    date -d "$year-$month-01 +1 month -1 day" +%d 2>/dev/null || {
        # Fallback for macOS
        case $month in
            1|3|5|7|8|10|12) echo "31" ;;
            4|6|9|11) echo "30" ;;
            2) 
                # Check for leap year
                if [ $((year % 4)) -eq 0 ] && ([ $((year % 100)) -ne 0 ] || [ $((year % 400)) -eq 0 ]); then
                    echo "29"
                else
                    echo "28"
                fi
                ;;
        esac
    }
}

# Global flag to stop processing
STOP_PROCESSING=false

# Function to check if a specific day is already processed
is_day_completed() {
    local date=$1  # Format: YYYY-MM-DD
    local year_month=$(echo $date | cut -d'-' -f1,2)
    local output_subdir="$OUTPUT_DIR/${year_month}"
    local expected_file="$output_subdir/training_data_${date}.parquet"
    
    [ -f "$expected_file" ] && return 0 || return 1
}

# Function to check if a month has any completed days (for progress reporting)
has_completed_days() {
    local year=$1
    local month=$2
    local output_subdir="$OUTPUT_DIR/$year-$(printf '%02d' $month)"
    
    if [ -d "$output_subdir" ]; then
        local parquet_count=$(find "$output_subdir" -name "*.parquet" -type f | wc -l)
        [ $parquet_count -gt 0 ] && return 0
    fi
    return 1
}

# Function to process a single month
process_month() {
    local year=$1
    local month=$2
    local month_name=$(get_month_name $month)
    
    # Check if we should stop processing (signal or stop file)
    if [ "$STOP_PROCESSING" = true ] || [ -f "/tmp/stop_bmps_generation" ]; then
        log_warning "Stopping processing due to interrupt signal or stop file"
        STOP_PROCESSING=true
        return 1
    fi
    
    # Check if month should be skipped
    if should_skip_month $year $month; then
        log_warning "SKIPPING $month_name $year (reserved for validation testing)"
        return 0
    fi
    
    # Check if month has any existing days (show resume info)
    if has_completed_days $year $month; then
        local output_subdir="$OUTPUT_DIR/$year-$(printf '%02d' $month)"
        local file_count=$(find "$output_subdir" -name "*.parquet" -type f | wc -l)
        local total_size=$(du -sh "$output_subdir" 2>/dev/null | cut -f1 || echo "Unknown")
        log "RESUMING: $month_name $year has $file_count completed days ($total_size) - will skip existing days"
    fi
    
    log "Processing $month_name $year..."
    
    # Process each day in the month individually for better crash resilience
    local output_subdir="$OUTPUT_DIR/$year-$(printf '%02d' $month)"
    local month_failed=0
    local month_processed=0
    local month_skipped=0
    
    # Create month directory
    mkdir -p "$output_subdir"
    
    # Iterate through each day in the month
    local last_day=$(get_last_day_of_month $year $month)
    for day in $(seq 1 $last_day); do
        local current_date=$(printf "%04d-%02d-%02d" $year $month $day)
        
        # Check if we should stop processing
        if [ "$STOP_PROCESSING" = true ] || [ -f "/tmp/stop_bmps_generation" ]; then
            log_warning "Stopping day-level processing due to interrupt"
            break
        fi
        
        # Check if this day is already completed (RESUME LOGIC)
        if is_day_completed "$current_date"; then
            log "    ✓ $current_date already completed - skipping"
            month_skipped=$((month_skipped + 1))
            continue
        fi
        
        # Process single day
        log "    Processing $current_date..."
        local log_file="/tmp/bmps_generation_${current_date}.log"
        
        if sbt "project $CONSOLE_PROJECT" "run --start=$current_date --end=$current_date --out=$output_subdir --lag=$LAG_MINUTES" > "$log_file" 2>&1; then
            log "    ✓ Completed $current_date"
            month_processed=$((month_processed + 1))
        else
            log_error "    ✗ Failed $current_date (check $log_file)"
            month_failed=$((month_failed + 1))
        fi
    done
    
    # Month summary
    echo "────────────────────────────────────────────────────────────────────────────────"
    log_success "Month Summary: $month_name $year"
    log "  Processed: $month_processed days"
    log "  Skipped (already done): $month_skipped days"
    if [ $month_failed -gt 0 ]; then
        log_error "  Failed: $month_failed days"
    fi
    
    # Show final file count and size
    local file_count=$(find "$output_subdir" -name "*.parquet" 2>/dev/null | wc -l)
    local total_size=$(du -sh "$output_subdir" 2>/dev/null | cut -f1 || echo "Unknown")
    log "  Total files: $file_count parquet files ($total_size)"
    
    # Return success if we processed at least some days (even if some failed)
    [ $month_processed -gt 0 ] || [ $month_skipped -gt 0 ]
}

# Main processing loop
total_months=0
processed_months=0
skipped_months=0
failed_months=0

# Check for existing data and show resume info
existing_days=0
existing_files=$(find "$OUTPUT_DIR" -name "*.parquet" -type f 2>/dev/null | wc -l)

log "Processing data from January 2020 to October 2025..."
if [ $existing_files -gt 0 ]; then
    log_success "RESUME MODE: Found $existing_files existing parquet files - will skip completed days"
fi
echo ""

# Process each year and month
for year in {2020..2025}; do
    # Determine month range
    if [ $year -eq 2025 ]; then
        # Only process through October 2025
        max_month=10
    else
        max_month=12
    fi
    
    for month in $(seq 1 $max_month); do
        # Check if we should stop processing
        if [ "$STOP_PROCESSING" = true ]; then
            log_warning "Stopping processing loop due to interrupt"
            break 2  # Break out of both loops (month and year)
        fi
        
        total_months=$((total_months + 1))
        
        if should_skip_month $year $month; then
            skipped_months=$((skipped_months + 1))
            month_name=$(get_month_name $month)
            log_warning "SKIPPING $month_name $year (reserved for validation testing)"
        else
            progress=$((processed_months + failed_months + 1))
            estimated_training_months=$((total_months - skipped_months))
            log "=========================================="
            log "PROCESSING MONTH $progress of ~$estimated_training_months"
            log "=========================================="
            
            if process_month $year $month; then
                processed_months=$((processed_months + 1))
                # Save progress
                echo "$(date): Completed $year-$(printf '%02d' $month)" >> "$PROGRESS_FILE"
                echo ""
                log "📊 PROGRESS SUMMARY: $processed_months completed, $failed_months failed, $skipped_months skipped"
                echo ""
            else
                failed_months=$((failed_months + 1))
                echo "$(date): FAILED $year-$(printf '%02d' $month)" >> "$PROGRESS_FILE"
                log_warning "Continuing to next month despite failure..."
                echo ""
                log "📊 PROGRESS SUMMARY: $processed_months completed, $failed_months failed, $skipped_months skipped"
                echo ""
                # If processing failed and we want to stop on failures, uncomment:
                # break 2
            fi
        fi
    done
done

# Summary
echo ""
log "=========================================="
log "TRAINING DATASET GENERATION COMPLETE"
log "=========================================="
log "Total months in range: $total_months"
log_success "Successfully processed: $processed_months"
log_warning "Skipped for validation: $skipped_months"
if [ $failed_months -gt 0 ]; then
    log_error "Failed: $failed_months"
fi

echo ""
log "VALIDATION HOLDOUT MONTHS:"
log "========================="
log "2023: Feb, Apr, Jun, Aug, Oct, Dec (even months)"
log "2024: Jan, Mar, May, Jul, Sep, Nov (odd months)"
log "2025: Feb, Apr, Jun, Aug, Oct (even months)"
echo ""

log "Training datasets saved to: $OUTPUT_DIR"
log "Use validation holdout months for model evaluation and hyperparameter tuning."

# Calculate total dataset size
if command -v du >/dev/null 2>&1; then
    total_size=$(du -sh "$OUTPUT_DIR" 2>/dev/null | cut -f1 || echo "Unknown")
    log "Total dataset size: $total_size"
fi

if [ $failed_months -eq 0 ]; then
    log_success "All dataset generation completed successfully!"
    exit 0
else
    log_error "Some months failed to process. Check logs in /tmp/bmps_generation_*.log"
    exit 1
fi