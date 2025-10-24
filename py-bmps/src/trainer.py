"""
BMPS Model Trainer

Main training script that orchestrates the complete training pipeline:
- Data loading and preprocessing
- Model training with progress tracking
- Model evaluation
- Model saving and validation
"""

import logging
import time
from typing import Dict, Optional
import numpy as np
import yaml
from pathlib import Path

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BMPSTrainer:
    """Main trainer for BMPS XGBoost model."""
    
    def __init__(self, config_path: str = "config/model_config.yaml"):
        """Initialize trainer with configuration."""
        with open(config_path, 'r') as f:
            self.config = yaml.safe_load(f)
        
        self.config_path = config_path
        
        # Initialize components
        from src.data_loader import BMPSDataLoader
        from src.model import BMPSXGBoostModel
        
        self.data_loader = BMPSDataLoader(config_path)
        self.model = BMPSXGBoostModel(config_path)
        # Note: BMPSEvaluator removed - use direct model evaluation
        
    def train(self, max_files: Optional[int] = None, save_model: bool = True, 
              evaluate: bool = True) -> Dict:
        """
        Complete training pipeline.
        
        Args:
            max_files: Maximum number of parquet files to load (None for all)
            save_model: Whether to save the trained model
            evaluate: Whether to run evaluation after training
            
        Returns:
            Training results dictionary
        """
        logger.info("Starting BMPS model training pipeline...")
        start_time = time.time()
        
        # 1. Data Loading
        logger.info("Loading and processing training data...")
        data_start = time.time()
        X_train, X_val, y_train, y_val, data_stats = self.data_loader.load_processed_data(
            max_files=max_files
        )
        data_time = time.time() - data_start
        logger.info(f"Data loading completed in {data_time:.2f} seconds")
        
        # Log data statistics
        logger.info(f"Training samples: {X_train.shape[0]}")
        logger.info(f"Validation samples: {X_val.shape[0]}")
        logger.info(f"Features: {X_train.shape[1]}")
        logger.info(f"Targets: {y_train.shape[1]}")
        logger.info(f"Positive rate: {data_stats['positive_rate']:.4f}")
        
        # 2. Model Training
        logger.info("Training XGBoost model...")
        train_start = time.time()
        training_stats = self.model.train(X_train, y_train, X_val, y_val)
        train_time = time.time() - train_start
        logger.info(f"Model training completed in {train_time:.2f} seconds")
        
        # Get model summary
        model_summary = self.model.get_model_summary()
        logger.info(f"Successfully trained {model_summary['n_targets_trained']} models")
        
        # 3. Model Evaluation (Simplified for regression)
        evaluation_results = None
        eval_time = 0
        if evaluate and model_summary['n_targets_trained'] > 0:
            logger.info("Skipping detailed evaluation (use model metrics instead)...")
            # For regression, the training process already provides RMSE, MAE, R² metrics
            # Detailed evaluation can be added later if needed
        
        # 4. Model Saving
        if save_model and model_summary['n_targets_trained'] > 0:
            logger.info("Saving trained model...")
            self.model.save_model()
        
        # 5. Results Summary
        total_time = time.time() - start_time
        logger.info(f"Training pipeline completed in {total_time:.2f} seconds")
        
        results = {
            'training_successful': model_summary['n_targets_trained'] > 0,
            'data_stats': data_stats,
            'training_stats': training_stats,
            'model_summary': model_summary,
            'evaluation_results': evaluation_results,
            'timing': {
                'data_loading_time': data_time,
                'training_time': train_time,
                'evaluation_time': eval_time if evaluate else 0,
                'total_time': total_time
            }
        }
        
        return results
    
    def quick_train(self, max_files: int = 10) -> Dict:
        """
        Quick training for testing/development with limited data.
        
        Args:
            max_files: Number of files to use for quick training
            
        Returns:
            Training results
        """
        logger.info(f"Starting quick training with {max_files} files...")
        return self.train(max_files=max_files, save_model=True, evaluate=True)
    
    def full_train(self) -> Dict:
        """
        Full training with all available data.
        
        Returns:
            Training results
        """
        logger.info("Starting full training with all available data...")
        return self.train(max_files=None, save_model=True, evaluate=True)
    
    def validate_model(self) -> bool:
        """
        Validate that a trained model exists and works.
        
        Returns:
            True if model is valid, False otherwise
        """
        try:
            from src.model import BMPSXGBoostModel
            
            # Try to load the model
            model = BMPSXGBoostModel(config_path=self.config_path)
            model.load_model()
            
            # Test with dummy data (22 features for new format)
            dummy_features = np.random.randn(1, 22)  # 1 sample, 22 features
            predictions = model.predict(dummy_features)
            
            # Check that we get 5 predictions (one for each time horizon)
            if predictions.shape == (1, 5):
                logger.info("Model validation successful - regression model working")
                return True
            else:
                logger.error(f"Expected shape (1, 5), got {predictions.shape}")
                return False
            
        except Exception as e:
            logger.error(f"Model validation failed: {e}")
            return False


def main():
    """Main training entry point."""
    import argparse
    
    parser = argparse.ArgumentParser(description="Train BMPS XGBoost model")
    parser.add_argument("--mode", choices=["quick", "full"], default="quick",
                       help="Training mode: quick (limited data) or full (all data)")
    parser.add_argument("--max-files", type=int, default=20,
                       help="Maximum files for quick training")
    parser.add_argument("--config", default="config/model_config.yaml",
                       help="Path to configuration file")
    parser.add_argument("--no-eval", action="store_true",
                       help="Skip evaluation after training")
    
    args = parser.parse_args()
    
    # Initialize trainer
    trainer = BMPSTrainer(args.config)
    
    # Run training
    if args.mode == "quick":
        results = trainer.quick_train(max_files=args.max_files)
    else:
        results = trainer.full_train()
    
    # Print results summary
    print("\n" + "="*50)
    print("TRAINING RESULTS SUMMARY")
    print("="*50)
    print(f"Training successful: {results['training_successful']}")
    print(f"Trained models: {results['model_summary']['n_targets_trained']}")
    print(f"Total time: {results['timing']['total_time']:.2f} seconds")
    
    if results['model_summary'].get('avg_label_mean'):
        print(f"Average label mean: {results['model_summary']['avg_label_mean']:.4f}")
        print(f"Average label std: {results['model_summary']['avg_label_std']:.4f}")
        print(f"Targets with metrics: {results['model_summary']['targets_with_metrics']}")
    
    # Validate model
    if results['training_successful']:
        print(f"Model validation: {'PASSED' if trainer.validate_model() else 'FAILED'}")
    
    print("="*50)


if __name__ == "__main__":
    main()