"""
BMPS XGBoost Model

Multi-target XGBoost model for predicting trading setup success probabilities.
"""

import logging
import pickle
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any
import numpy as np
import xgboost as xgb
from sklearn.calibration import CalibratedClassifierCV
from sklearn.multioutput import MultiOutputRegressor
from sklearn.metrics import log_loss, roc_auc_score
import yaml
import joblib

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BMPSXGBoostModel:
    """
    Multi-target XGBoost model for BMPS trading setup prediction.
    
    This model predicts probabilities for 194 different trading setups
    using 69 technical features.
    """
    
    def __init__(self, config_path: str = "config/model_config.yaml"):
        """Initialize model with configuration."""
        with open(config_path, 'r') as f:
            self.config = yaml.safe_load(f)
        
        self.model_config = self.config['model']
        self.training_config = self.config['training']
        self.model_dir = Path(self.config['paths']['model_dir'])
        self.model_dir.mkdir(exist_ok=True)
        
        # Model components
        self.models: Dict[int, Any] = {}  # One model per target
        self.calibrated_models: Dict[int, Any] = {}  # Calibrated versions
        self.feature_importance: Optional[np.ndarray] = None
        self.training_stats: Dict = {}
        
        # Initialize base XGBoost parameters
        self.xgb_params = {
            'n_estimators': self.model_config['n_estimators'],
            'max_depth': self.model_config['max_depth'],
            'learning_rate': self.model_config['learning_rate'],
            'subsample': self.model_config['subsample'],
            'colsample_bytree': self.model_config['colsample_bytree'],
            'reg_alpha': self.model_config['reg_alpha'],
            'reg_lambda': self.model_config['reg_lambda'],
            'random_state': self.model_config['random_state'],
            'n_jobs': self.model_config['n_jobs'],
            'objective': 'binary:logistic',  # For probability output
            'eval_metric': 'logloss'
        }
    
    def preprocess_labels(self, y: np.ndarray) -> np.ndarray:
        """
        Preprocess labels to binary classification format.
        
        Convert continuous labels to binary (0/1) based on whether
        they indicate a profitable setup.
        
        Args:
            y: Label array of shape (n_samples, n_targets)
            
        Returns:
            Binary label array
        """
        # For now, treat any positive value as success (1), others as failure (0)
        # This can be adjusted based on domain knowledge
        return (y > 0).astype(int)
    
    def train_single_target(self, X_train: np.ndarray, y_train: np.ndarray, 
                           X_val: np.ndarray, y_val: np.ndarray, 
                           target_idx: int) -> Tuple[xgb.XGBClassifier, Dict]:
        """
        Train XGBoost model for a single target.
        
        Args:
            X_train: Training features
            y_train: Training labels for this target
            X_val: Validation features  
            y_val: Validation labels for this target
            target_idx: Index of the target being trained
            
        Returns:
            Tuple of (trained_model, training_stats)
        """
        # Check for class imbalance
        pos_rate = np.mean(y_train)
        
        # Adjust parameters for highly imbalanced data
        params = self.xgb_params.copy()
        if pos_rate < 0.01:  # Less than 1% positive
            # Increase scale_pos_weight to handle imbalance
            params['scale_pos_weight'] = max(1, int(1.0 / pos_rate))
        
        # Create model
        model = xgb.XGBClassifier(**params)
        
        # Simple training without early stopping for now 
        # (will add it back with proper callback syntax later)
        model.fit(X_train, y_train, verbose=False)
        
        # Calculate validation metrics
        val_pred_proba = model.predict_proba(X_val)[:, 1] if len(np.unique(y_val)) > 1 else np.zeros_like(y_val)
        
        stats = {
            'target_idx': target_idx,
            'positive_rate': float(pos_rate),
            'n_samples': len(y_train),
            'n_positive': int(np.sum(y_train)),
        }
        
        # Add metrics if we have both classes in validation
        if len(np.unique(y_val)) > 1 and np.sum(y_val) > 0:
            try:
                stats['val_logloss'] = float(log_loss(y_val, val_pred_proba))
                stats['val_auc'] = float(roc_auc_score(y_val, val_pred_proba))
            except Exception as e:
                logger.warning(f"Could not calculate metrics for target {target_idx}: {e}")
                stats['val_logloss'] = None
                stats['val_auc'] = None
        else:
            stats['val_logloss'] = None
            stats['val_auc'] = None
            
        return model, stats
    
    def train(self, X_train: np.ndarray, y_train: np.ndarray, 
              X_val: np.ndarray, y_val: np.ndarray) -> Dict:
        """
        Train multi-target XGBoost model.
        
        Args:
            X_train: Training features of shape (n_samples, n_features)
            y_train: Training labels of shape (n_samples, n_targets)
            X_val: Validation features
            y_val: Validation labels
            
        Returns:
            Training statistics dictionary
        """
        logger.info("Starting multi-target XGBoost training...")
        
        # Preprocess labels
        y_train_binary = self.preprocess_labels(y_train)
        y_val_binary = self.preprocess_labels(y_val)
        
        n_targets = y_train_binary.shape[1]
        logger.info(f"Training {n_targets} individual models...")
        
        self.training_stats = {
            'n_targets': n_targets,
            'n_features': X_train.shape[1],
            'n_train_samples': X_train.shape[0],
            'n_val_samples': X_val.shape[0],
            'target_stats': []
        }
        
        # Train each target independently
        for target_idx in range(n_targets):
            if target_idx % 20 == 0:
                logger.info(f"Training target {target_idx + 1}/{n_targets}")
                
            y_target_train = y_train_binary[:, target_idx]
            y_target_val = y_val_binary[:, target_idx]
            
            # Skip targets with no positive examples
            if np.sum(y_target_train) == 0:
                logger.warning(f"Target {target_idx} has no positive examples, skipping")
                continue
            
            try:
                model, stats = self.train_single_target(
                    X_train, y_target_train, X_val, y_target_val, target_idx
                )
                self.models[target_idx] = model
                self.training_stats['target_stats'].append(stats)
                
            except Exception as e:
                logger.error(f"Failed to train target {target_idx}: {e}")
                continue
        
        logger.info(f"Successfully trained {len(self.models)} models")
        
        # Calculate feature importance (average across all models)
        self._calculate_feature_importance()
        
        # Apply calibration if requested
        if self.model_config.get('calibration', False):
            self._calibrate_models(X_val, y_val_binary)
        
        return self.training_stats
    
    def _calculate_feature_importance(self):
        """Calculate average feature importance across all models."""
        if not self.models:
            return
            
        importance_arrays = []
        for model in self.models.values():
            importance_arrays.append(model.feature_importances_)
        
        self.feature_importance = np.mean(importance_arrays, axis=0)
        logger.info("Calculated average feature importance")
    
    def _calibrate_models(self, X_val: np.ndarray, y_val: np.ndarray):
        """Apply probability calibration to models."""
        logger.info("Applying probability calibration...")
        
        method = self.model_config.get('calibration_method', 'isotonic')
        
        for target_idx, model in self.models.items():
            try:
                y_target_val = y_val[:, target_idx]
                if np.sum(y_target_val) > 0:  # Only calibrate if we have positive examples
                    calibrated = CalibratedClassifierCV(
                        model, method=method, cv=3
                    )
                    calibrated.fit(X_val, y_target_val)
                    self.calibrated_models[target_idx] = calibrated
            except Exception as e:
                logger.warning(f"Failed to calibrate model for target {target_idx}: {e}")
                continue
        
        logger.info(f"Calibrated {len(self.calibrated_models)} models")
    
    def predict_probabilities(self, X: np.ndarray, use_calibrated: bool = True) -> np.ndarray:
        """
        Predict probabilities for all targets.
        
        Args:
            X: Features of shape (n_samples, n_features)
            use_calibrated: Whether to use calibrated models
            
        Returns:
            Probability array of shape (n_samples, n_targets)
        """
        n_samples = X.shape[0]
        n_targets = len(self.models)
        probabilities = np.zeros((n_samples, 194))  # Full 194 targets
        
        models_to_use = self.calibrated_models if use_calibrated and self.calibrated_models else self.models
        
        for target_idx, model in models_to_use.items():
            try:
                proba = model.predict_proba(X)
                if proba.shape[1] > 1:  # Binary classification with 2 classes
                    probabilities[:, target_idx] = proba[:, 1]  # Probability of positive class
                else:  # Only one class present during training
                    probabilities[:, target_idx] = 0.0
            except Exception as e:
                logger.warning(f"Failed to predict for target {target_idx}: {e}")
                probabilities[:, target_idx] = 0.0
        
        return probabilities
    
    def save_model(self, model_path: Optional[str] = None):
        """Save the trained model and metadata."""
        if model_path is None:
            model_path = self.model_dir / "bmps_xgboost_model.pkl"
        
        model_data = {
            'models': self.models,
            'calibrated_models': self.calibrated_models,
            'feature_importance': self.feature_importance,
            'training_stats': self.training_stats,
            'config': self.config
        }
        
        with open(model_path, 'wb') as f:
            pickle.dump(model_data, f)
        
        logger.info(f"Model saved to {model_path}")
    
    def load_model(self, model_path: Optional[str] = None):
        """Load a saved model."""
        if model_path is None:
            model_path = self.model_dir / "bmps_xgboost_model.pkl"
        
        with open(model_path, 'rb') as f:
            model_data = pickle.load(f)
        
        self.models = model_data['models']
        self.calibrated_models = model_data['calibrated_models']
        self.feature_importance = model_data['feature_importance']
        self.training_stats = model_data['training_stats']
        
        logger.info(f"Model loaded from {model_path}")
    
    def get_model_summary(self) -> Dict:
        """Get a summary of the trained model."""
        if not self.training_stats:
            return {"error": "Model not trained yet"}
        
        summary = {
            'n_targets_trained': len(self.models),
            'n_targets_calibrated': len(self.calibrated_models),
            'n_features': self.training_stats['n_features'],
            'n_train_samples': self.training_stats['n_train_samples'],
            'n_val_samples': self.training_stats['n_val_samples'],
        }
        
        # Average statistics across targets
        if self.training_stats['target_stats']:
            target_stats = self.training_stats['target_stats']
            summary.update({
                'avg_positive_rate': np.mean([s['positive_rate'] for s in target_stats]),
                'targets_with_metrics': len([s for s in target_stats if s['val_auc'] is not None])
            })
        
        return summary


if __name__ == "__main__":
    # Test the model with sample data
    import sys
    sys.path.append('.')
    from src.data_loader import BMPSDataLoader
    
    # Load a small dataset for testing
    loader = BMPSDataLoader()
    X_train, X_val, y_train, y_val, stats = loader.load_processed_data(max_files=2)
    
    # Initialize and train model
    model = BMPSXGBoostModel()
    training_stats = model.train(X_train, y_train, X_val, y_val)
    
    print("Training completed!")
    print(f"Model summary: {model.get_model_summary()}")
    
    # Test prediction
    probas = model.predict_probabilities(X_val[:5])
    print(f"Sample predictions shape: {probas.shape}")
    print(f"Sample prediction (first 5 targets): {probas[0, :5]}")