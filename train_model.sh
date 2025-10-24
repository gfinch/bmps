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
    # Direct model training without the complex trainer pipeline
    from src.data_loader import BMPSDataLoader
    from src.model import BMPSXGBoostModel
    
    # Get max_files from command line argument
    max_files = None
    if len(sys.argv) > 1 and sys.argv[1] != "None":
        max_files = int(sys.argv[1])
    
    print(f"Loading training data...")
    start_time = time.time()
    
    # Load data
    data_loader = BMPSDataLoader()
    X_train, X_val, y_train, y_val, data_stats = data_loader.load_processed_data(max_files=max_files)
    
    load_time = time.time() - start_time
    print(f"Data loaded in {load_time:.1f} seconds")
    
    # Initialize and train model
    print(f"Training regression model...")
    train_start = time.time()
    
    model = BMPSXGBoostModel()
    training_stats = model.train(X_train, y_train, X_val, y_val)
    
    train_time = time.time() - train_start
    
    # Get model summary
    model_summary = model.get_model_summary()
    
    # Print results
    print("\n" + "="*50)
    print("TRAINING RESULTS")
    print("="*50)
    
    if model_summary['n_targets_trained'] > 0:
        print(f"✅ Successfully trained {model_summary['n_targets_trained']} regression models")
        print(f"⏱️  Data loading: {load_time:.1f} seconds")
        print(f"⏱️  Model training: {train_time:.1f} seconds")
        print(f"⏱️  Total time: {time.time() - start_time:.1f} seconds")
        
        # Data stats
        print(f"\n📊 Dataset Statistics:")
        print(f"   Training samples: {model_summary['n_train_samples']:,}")
        print(f"   Validation samples: {model_summary['n_val_samples']:,}")
        print(f"   Features: {model_summary['n_features']}")
        print(f"   Targets: {model_summary['n_targets_trained']}")
        print(f"   Positive rate: {data_stats['positive_rate']:.4f}")
        print(f"   Label mean: {data_stats['label_mean']:.4f}")
        print(f"   Label std: {data_stats['label_std']:.4f}")
        
        # Regression metrics
        print(f"\n📈 Model Performance:")
        if model_summary.get('avg_label_mean'):
            print(f"   Average label mean: {model_summary['avg_label_mean']:.4f}")
            print(f"   Average label std: {model_summary['avg_label_std']:.4f}")
            print(f"   Targets with metrics: {model_summary['targets_with_metrics']}")
        
        # Save model
        print(f"\n💾 Saving model...")
        model.save_model()
        print(f"   Model saved to: models/bmps_xgboost_model.pkl")
        
        # Test predictions
        print(f"\n🧪 Testing model predictions...")
        test_predictions = model.predict(X_val[:5])
        print(f"   Prediction shape: {test_predictions.shape}")
        print(f"   Sample prediction: {test_predictions[0]}")
        print(f"   Sample actual: {y_val[0]}")
        
        print("✅ Training completed successfully!")
            
    else:
        print("❌ Training failed - no models were trained successfully")
        print("   This might be due to insufficient variance in the data")
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
    echo "   python -c \"from src.model import BMPSXGBoostModel; m=BMPSXGBoostModel(); m.load_model(); print('Model loaded successfully!')\""
    echo ""
    echo "2. Use in your code:"
    echo "   from src.model import BMPSXGBoostModel"
    echo "   model = BMPSXGBoostModel()"
    echo "   model.load_model()"
    echo "   predictions = model.predict(features)  # Returns (samples, 5) array for 5 time horizons"
    echo ""
    echo "3. Interpret predictions:"
    echo "   predictions[:, 0]  # 1-minute horizon predictions (ATR units)"
    echo "   predictions[:, 4]  # 20-minute horizon predictions (ATR units)"
    echo "   # Positive values indicate upward movement, negative indicate downward"
    echo ""
}

# Run main function
main "$@"