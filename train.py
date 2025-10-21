#!/usr/bin/env python3
"""
BMPS Training Script

Easy-to-use script for training the XGBoost model.
Just run: python train.py
"""

import os
import sys
from pathlib import Path

def main():
    """Main training script with simple interface."""
    
    print("🚀 BMPS XGBoost Training")
    print("=" * 40)
    
    # Change to the py-bmps directory
    script_dir = Path(__file__).parent
    py_bmps_dir = script_dir / "py-bmps"
    
    if not py_bmps_dir.exists():
        print("❌ Error: py-bmps directory not found!")
        print(f"   Expected: {py_bmps_dir}")
        return 1
    
    os.chdir(py_bmps_dir)
    print(f"📁 Working directory: {py_bmps_dir}")
    
    # Check if we have training data
    data_dir = py_bmps_dir / "data"
    if not data_dir.exists() or not any(data_dir.glob("**/*.parquet")):
        print("❌ Error: No training data found!")
        print(f"   Expected parquet files in: {data_dir}")
        print("   Make sure you've run the training dataset generation script first.")
        return 1
    
    # Count parquet files
    parquet_files = list(data_dir.glob("**/*.parquet"))
    print(f"📊 Found {len(parquet_files)} training files")
    
    # Import and run trainer
    try:
        sys.path.insert(0, str(py_bmps_dir))
        from src.trainer import BMPSTrainer
        
        print("\n🔧 Initializing trainer...")
        trainer = BMPSTrainer()
        
        # Ask user for training mode
        print("\n🎯 Choose training mode:")
        print("  1. Quick training (25 files, ~5-10 minutes)")
        print("  2. Medium training (50 files, ~10-20 minutes)")  
        print("  3. Full training (all files, ~30+ minutes)")
        
        while True:
            choice = input("\nEnter choice (1-3) or 'q' to quit: ").strip()
            
            if choice.lower() == 'q':
                print("👋 Training cancelled")
                return 0
            elif choice == '1':
                max_files = 25
                break
            elif choice == '2':
                max_files = 50
                break
            elif choice == '3':
                max_files = None
                break
            else:
                print("❌ Please enter 1, 2, 3, or 'q'")
        
        print(f"\n🏋️  Starting training with {max_files or 'ALL'} files...")
        print("   This may take several minutes...")
        
        # Run training
        results = trainer.train(
            max_files=max_files,
            save_model=True,
            evaluate=True
        )
        
        # Print results
        print("\n" + "=" * 50)
        print("🎉 TRAINING COMPLETE!")
        print("=" * 50)
        
        if results['training_successful']:
            print(f"✅ Successfully trained {results['model_summary']['n_targets_trained']} target models")
            print(f"⏱️  Total time: {results['timing']['total_time']:.1f} seconds")
            
            if results['evaluation_results']:
                overall = results['evaluation_results']['performance_metrics']['overall_metrics']
                if overall.get('avg_auc'):
                    print(f"📈 Average AUC: {overall['avg_auc']:.4f}")
                    print(f"📈 Average AUC (calibrated): {overall['avg_auc_calibrated']:.4f}")
                
                threshold = results['evaluation_results']['threshold_analysis']['recommended_threshold']
                print(f"🎯 Recommended threshold: {threshold:.3f}")
            
            print(f"\n💾 Model saved to: models/bmps_xgboost_model.pkl")
            
            # Test the model
            print("\n🧪 Testing model...")
            if trainer.validate_model():
                print("✅ Model validation PASSED - ready for inference!")
                
                # Show quick usage example
                print("\n📖 Quick usage example:")
                print("```python")
                print("from src.predictor import BMPSPredictor")
                print("predictor = BMPSPredictor()")
                print("best_setup, prob = predictor.predict_best_setup(features, threshold=0.7)")
                print("```")
            else:
                print("❌ Model validation FAILED")
                
        else:
            print("❌ Training failed - no models were trained successfully")
            print("   This might be due to insufficient positive examples in the data")
            print("   Try using more files or check the data quality")
            
        return 0 if results['training_successful'] else 1
        
    except ImportError as e:
        print(f"❌ Import error: {e}")
        print("   Make sure you're in the correct virtual environment")
        print("   and all dependencies are installed:")
        print("   pip install -r requirements.txt")
        return 1
    except Exception as e:
        print(f"❌ Training failed with error: {e}")
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    sys.exit(main())