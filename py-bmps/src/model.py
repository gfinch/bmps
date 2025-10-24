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
from sklearn.preprocessing import RobustScaler
from sklearn.multioutput import MultiOutputRegressor
from sklearn.metrics import log_loss, roc_auc_score
import yaml
import joblib

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BMPSXGBoostModel:
    """
    Multi-target XGBoost model for BMPS trading setup prediction.
    
    This model predicts probabilities for multiple trading setups
    using 69 technical features. The number of targets is configurable.
    """
    
    def __init__(self, config_path: str = "config/model_config.yaml"):
        """Initialize model with configuration."""
        with open(config_path, 'r') as f:
            self.config = yaml.safe_load(f)
        
        self.model_config = self.config['model']
        self.training_config = self.config['training']
        self.data_config = self.config['data']
        self.model_dir = Path(self.config['paths']['model_dir'])
        self.model_dir.mkdir(exist_ok=True)
        
        # Get number of targets from config
        self.n_targets = self.data_config['label_dim']
        
        # Model components
        self.models: Dict[int, Any] = {}  # One model per target
        self.calibrated_models: Dict[int, Any] = {}  # Calibrated versions
        self.feature_scaler: Optional[RobustScaler] = None  # Feature scaler
        self.feature_importance: Optional[np.ndarray] = None
        self.training_stats: Dict = {}
        
        # Initialize base XGBoost parameters for regression
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
            'objective': 'reg:squarederror',  # For regression
            'eval_metric': 'rmse'
        }
    
    def preprocess_features(self, X: np.ndarray, fit_scaler: bool = True) -> np.ndarray:
        """
        Preprocess features with scaling and cleaning.
        
        Args:
            X: Raw features
            fit_scaler: Whether to fit the scaler (True for training, False for inference)
            
        Returns:
            Scaled and cleaned features
        """
        # Handle missing values and infinities
        X_clean = np.nan_to_num(X, nan=0.0, posinf=0.0, neginf=0.0)
        
        # Initialize scaler if needed (RobustScaler is better for outliers than StandardScaler)
        if self.feature_scaler is None:
            self.feature_scaler = RobustScaler()
        
        # Fit and transform (training) or just transform (inference)
        if fit_scaler:
            X_scaled = self.feature_scaler.fit_transform(X_clean)
            logger.info(f"Fitted feature scaler on {X.shape[1]} features")
        else:
            X_scaled = self.feature_scaler.transform(X_clean)
        
        return X_scaled

    def preprocess_labels(self, y: np.ndarray) -> np.ndarray:
        """
        Preprocess labels for regression.
        
        The new labels are already continuous regression targets
        representing future price movements in ATR units.
        
        Args:
            y: Label array of shape (n_samples, n_targets)
            
        Returns:
            Cleaned label array (handle NaNs and infinities)
        """
        # Handle missing values and infinities
        y_clean = np.nan_to_num(y, nan=0.0, posinf=10.0, neginf=-10.0)
        
        # Optional: clip extreme values to reasonable range (e.g., ±5 ATR units)
        y_clipped = np.clip(y_clean, -5.0, 5.0)
        
        return y_clipped
    
    def train_single_target(self, X_train: np.ndarray, y_train: np.ndarray, 
                           X_val: np.ndarray, y_val: np.ndarray, 
                           target_idx: int) -> Tuple[xgb.XGBRegressor, Dict]:
        """
        Train XGBoost regression model for a single target.
        
        Args:
            X_train: Training features
            y_train: Training labels for this target
            X_val: Validation features  
            y_val: Validation labels for this target
            target_idx: Index of the target being trained
            
        Returns:
            Tuple of (trained_model, training_stats)
        """
        from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
        
        # Use base regression parameters
        params = self.xgb_params.copy()
        
        # Create regression model
        model = xgb.XGBRegressor(**params)
        
        # Simple training without early stopping for now 
        model.fit(X_train, y_train, verbose=False)
        
        # Calculate validation predictions and metrics
        val_pred = model.predict(X_val)
        
        stats = {
            'target_idx': target_idx,
            'n_samples': len(y_train),
            'label_mean': float(np.mean(y_train)),
            'label_std': float(np.std(y_train)),
            'label_min': float(np.min(y_train)),
            'label_max': float(np.max(y_train))
        }
        
        # Add regression metrics
        try:
            stats['val_rmse'] = float(np.sqrt(mean_squared_error(y_val, val_pred)))
            stats['val_mae'] = float(mean_absolute_error(y_val, val_pred))
            stats['val_r2'] = float(r2_score(y_val, val_pred))
        except Exception as e:
            logger.warning(f"Could not calculate metrics for target {target_idx}: {e}")
            stats['val_rmse'] = None
            stats['val_mae'] = None
            stats['val_r2'] = None
            
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
        
        # Preprocess features (scale and clean)
        X_train_processed = self.preprocess_features(X_train, fit_scaler=True)
        X_val_processed = self.preprocess_features(X_val, fit_scaler=False)
        
        # Preprocess labels (for regression)
        y_train_processed = self.preprocess_labels(y_train)
        y_val_processed = self.preprocess_labels(y_val)
        
        n_targets = y_train_processed.shape[1]
        logger.info(f"Training {n_targets} individual regression models...")
        
        self.training_stats = {
            'n_targets': n_targets,
            'n_features': X_train_processed.shape[1],
            'n_train_samples': X_train_processed.shape[0],
            'n_val_samples': X_val_processed.shape[0],
            'target_stats': []
        }
        
        # Train each target independently
        for target_idx in range(n_targets):
            logger.info(f"Training target {target_idx + 1}/{n_targets}")
                
            y_target_train = y_train_processed[:, target_idx]
            y_target_val = y_val_processed[:, target_idx]
            
            # Skip targets with insufficient variance (constant values)
            if np.std(y_target_train) < 1e-6:
                logger.warning(f"Target {target_idx} has no variance (std={np.std(y_target_train)}), skipping")
                continue
            
            try:
                model, stats = self.train_single_target(
                    X_train_processed, y_target_train, X_val_processed, y_target_val, target_idx
                )
                self.models[target_idx] = model
                self.training_stats['target_stats'].append(stats)
                
            except Exception as e:
                logger.error(f"Failed to train target {target_idx}: {e}")
                continue
        
        logger.info(f"Successfully trained {len(self.models)} models")
        
        # Calculate feature importance (average across all models)
        self._calculate_feature_importance()
        
        # Note: Calibration not applicable for regression models
        # Skip calibration for regression targets
        
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
    
    def predict(self, X: np.ndarray) -> np.ndarray:
        """
        Predict regression values for all targets.
        
        Args:
            X: Features of shape (n_samples, n_features)
            
        Returns:
            Prediction array of shape (n_samples, n_targets)
        """
        # Preprocess features using the same scaler as training
        X_processed = self.preprocess_features(X, fit_scaler=False)
        
        n_samples = X_processed.shape[0]
        predictions = np.zeros((n_samples, self.n_targets))
        
        for target_idx, model in self.models.items():
            if target_idx >= self.n_targets:  # Skip any targets beyond our configured limit
                continue
                
            try:
                pred = model.predict(X_processed)
                predictions[:, target_idx] = pred
            except Exception as e:
                logger.warning(f"Failed to predict for target {target_idx}: {e}")
                predictions[:, target_idx] = 0.0
        
        return predictions
    
    def save_model(self, model_path: Optional[str] = None):
        """Save the trained model and metadata."""
        if model_path is None:
            model_path = self.model_dir / "bmps_xgboost_model.pkl"
        
        model_data = {
            'models': self.models,
            'calibrated_models': self.calibrated_models,
            'feature_scaler': self.feature_scaler,
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
        self.feature_scaler = model_data.get('feature_scaler')  # Handle backward compatibility
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
                'avg_label_mean': np.mean([s['label_mean'] for s in target_stats]),
                'avg_label_std': np.mean([s['label_std'] for s in target_stats]),
                'targets_with_metrics': len([s for s in target_stats if s['val_rmse'] is not None])
            })
        
        return summary


if __name__ == "__main__":
    # Test the model with sample data
    import sys
    sys.path.append('.')
    from src.data_loader import BMPSDataLoader
    
    # Load a larger dataset for better testing
    loader = BMPSDataLoader()
    X_train, X_val, y_train, y_val, stats = loader.load_processed_data(max_files=20)  # Use 20 files instead of 2
    
    # Initialize and train model
    model = BMPSXGBoostModel()
    training_stats = model.train(X_train, y_train, X_val, y_val)
    
    print("Training completed!")
    print(f"Model summary: {model.get_model_summary()}")
    
    # Test prediction
    predictions = model.predict(X_val[:5])
    print(f"Sample predictions shape: {predictions.shape}")
    print(f"Sample prediction (all targets): {predictions[0]}")
    print(f"Sample actual labels: {y_val[0]}")