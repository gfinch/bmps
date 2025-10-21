#!/bin/bash

# BMPS XGBoost Training Script
# ============================
# Simple script to train the XGBoost model for trading setups

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
}

log_header() {
    echo -e "${BOLD}${BLUE}$1${NC}"
}

# Main function
main() {
    log_header "🚀 BMPS XGBoost Training"
    echo "========================================"
    
    # Check if we're in the right directory
    if [[ ! -d "py-bmps" ]]; then
        log_error "py-bmps directory not found!"
        log_info "Make sure you're running this script from the bmps project root"
        exit 1
    fi
    
    # Check for training data
    if [[ ! -d "py-bmps/data" ]] || [[ -z "$(find py-bmps/data -name "*.parquet" -type f)" ]]; then
        log_error "No training data found in py-bmps/data/"
        log_info "Make sure you've run the training dataset generation script first:"
        log_info "  ./generate_training_dataset.sh"
        exit 1
    fi
    
    # Count training files
    file_count=$(find py-bmps/data -name "*.parquet" -type f | wc -l)
    data_size=$(du -sh py-bmps/data 2>/dev/null | cut -f1)
    log_success "Found $file_count training files ($data_size total)"
    
    # Check virtual environment
    if [[ -z "$VIRTUAL_ENV" ]]; then
        log_warning "No virtual environment detected"
        if [[ -f "venv/bin/activate" ]]; then
            log_info "Activating virtual environment..."
            source venv/bin/activate
        else
            log_error "Virtual environment not found!"
            log_info "Please create and activate a virtual environment first:"
            log_info "  python -m venv venv"
            log_info "  source venv/bin/activate"
            log_info "  pip install -r py-bmps/requirements.txt"
            exit 1
        fi
    else
        log_success "Using virtual environment: $VIRTUAL_ENV"
    fi
    
    # Check dependencies
    log_info "Checking dependencies..."
    cd py-bmps
    python -c "import xgboost, sklearn, pandas, numpy, yaml" 2>/dev/null || {
        log_error "Missing dependencies!"
        log_info "Installing required packages..."
        pip install -r requirements.txt || {
            log_error "Failed to install dependencies"
            exit 1
        }
    }
    log_success "Dependencies OK"
    
    # Training mode selection
    echo ""
    log_header "🎯 Choose Training Mode:"
    echo "  1) Quick training    (25 files,  ~5-10 minutes)"
    echo "  2) Medium training   (50 files,  ~15-25 minutes)"
    echo "  3) Large training    (100 files, ~30-45 minutes)"
    echo "  4) Full training     (all files, ~60+ minutes)"
    echo ""
    
    while true; do
        read -p "Enter choice (1-4) or 'q' to quit: " choice
        case $choice in
            1)
                max_files=25
                mode_name="Quick"
                break
                ;;
            2)
                max_files=50
                mode_name="Medium"
                break
                ;;
            3)
                max_files=100
                mode_name="Large"
                break
                ;;
            4)
                max_files=""
                mode_name="Full"
                break
                ;;
            q|Q)
                log_info "Training cancelled"
                exit 0
                ;;
            *)
                log_warning "Please enter 1, 2, 3, 4, or 'q'"
                ;;
        esac
    done
    
    echo ""
    log_header "🏋️  Starting $mode_name Training"
    if [[ -n "$max_files" ]]; then
        log_info "Using $max_files files (out of $file_count available)"
    else
        log_info "Using all $file_count files"
    fi
    log_warning "This may take several minutes... Please wait!"
    echo ""
    
    # Create Python training script
    cat > run_training.py << 'EOF'
import sys
import os
import time
from pathlib import Path

try:
    from src.trainer import BMPSTrainer
    
    # Get max_files from command line argument
    max_files = None
    if len(sys.argv) > 1 and sys.argv[1] != "None":
        max_files = int(sys.argv[1])
    
    print(f"Initializing trainer...")
    trainer = BMPSTrainer()
    
    print(f"Starting training with {max_files or 'ALL'} files...")
    start_time = time.time()
    
    results = trainer.train(
        max_files=max_files,
        save_model=True,
        evaluate=True
    )
    
    # Print results
    print("\n" + "="*50)
    print("TRAINING RESULTS")
    print("="*50)
    
    if results['training_successful']:
        print(f"✅ Successfully trained {results['model_summary']['n_targets_trained']} models")
        print(f"⏱️  Total time: {results['timing']['total_time']:.1f} seconds")
        
        # Data stats
        data_stats = results['data_stats']
        print(f"📊 Training samples: {results['training_stats']['n_train_samples']:,}")
        print(f"📊 Validation samples: {results['training_stats']['n_val_samples']:,}")
        print(f"📊 Positive rate: {data_stats['positive_rate']:.4f}")
        
        # Evaluation results
        if results['evaluation_results']:
            eval_results = results['evaluation_results']
            overall = eval_results['performance_metrics']['overall_metrics']
            
            print(f"\n📈 Performance Metrics:")
            if overall.get('avg_auc'):
                print(f"   Average AUC: {overall['avg_auc']:.4f}")
                print(f"   Average AUC (calibrated): {overall['avg_auc_calibrated']:.4f}")
            
            threshold = eval_results['threshold_analysis']['recommended_threshold']
            print(f"   Recommended threshold: {threshold:.3f}")
        
        print(f"\n💾 Model saved to: models/bmps_xgboost_model.pkl")
        
        # Validate model
        print(f"\n🧪 Validating model...")
        if trainer.validate_model():
            print("✅ Model validation PASSED - ready for inference!")
        else:
            print("❌ Model validation FAILED")
            sys.exit(1)
            
    else:
        print("❌ Training failed - no models were trained successfully")
        print("   This might be due to insufficient positive examples")
        print("   Try using more training files")
        sys.exit(1)

except Exception as e:
    print(f"❌ Training failed: {e}")
    import traceback
    traceback.print_exc()
    sys.exit(1)
EOF
    
    # Run the training
    python run_training.py "${max_files:-None}" || {
        log_error "Training failed!"
        rm -f run_training.py
        exit 1
    }
    
    # Cleanup
    rm -f run_training.py
    
    echo ""
    log_success "Training completed successfully!"
    echo ""
    log_header "📖 Next Steps:"
    echo "1. Test predictions:"
    echo "   python -c \"from src.predictor import BMPSPredictor; p=BMPSPredictor(); print('Model loaded successfully!')\""
    echo ""
    echo "2. Use in your code:"
    echo "   from src.predictor import BMPSPredictor"
    echo "   predictor = BMPSPredictor()"
    echo "   best_setup, prob = predictor.predict_best_setup(features, threshold=0.7)"
    echo ""
    echo "3. Deploy to production:"
    echo "   Copy src/, config/, models/ directories to your deployment environment"
    echo ""
}

# Run main function
main "$@"