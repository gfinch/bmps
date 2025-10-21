"""
BMPS Predictor

Production inference pipeline for finding the best trading setup
with highest probability above threshold.
"""

import logging
from typing import Dict, List, Tuple, Optional, Union
import numpy as np
import yaml
from pathlib import Path

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BMPSPredictor:
    """Production inference pipeline for BMPS trading model."""
    
    def __init__(self, model_path: str = None, config_path: str = "config/model_config.yaml"):
        """
        Initialize predictor with trained model.
        
        Args:
            model_path: Path to saved model file
            config_path: Path to configuration file
        """
        with open(config_path, 'r') as f:
            self.config = yaml.safe_load(f)
        
        self.prediction_config = self.config['prediction']
        self.default_threshold = self.prediction_config['default_threshold']
        self.top_n_setups = self.prediction_config['top_n_setups']
        
        # Load model
        from src.model import BMPSXGBoostModel
        self.model = BMPSXGBoostModel(config_path)
        
        if model_path:
            self.model.load_model(model_path)
            logger.info(f"Model loaded from {model_path}")
        else:
            # Try default path
            default_path = Path(self.config['paths']['model_dir']) / "bmps_xgboost_model.pkl"
            if default_path.exists():
                self.model.load_model(str(default_path))
                logger.info(f"Model loaded from default path: {default_path}")
            else:
                raise ValueError(f"No model found at {default_path}. Please provide model_path or train a model first.")
    
    def predict_best_setup(self, features: np.ndarray, threshold: float = None) -> Tuple[Optional[int], float]:
        """
        Find the trading setup with highest probability above threshold.
        
        Args:
            features: Feature vector of shape (n_features,) or (1, n_features)
            threshold: Minimum probability threshold (uses default if None)
            
        Returns:
            Tuple of (best_setup_index, probability) or (None, 0.0) if no setup meets threshold
        """
        if threshold is None:
            threshold = self.default_threshold
        
        # Ensure features are 2D
        if features.ndim == 1:
            features = features.reshape(1, -1)
        
        # Get predictions
        probabilities = self.model.predict_probabilities(features, use_calibrated=True)
        
        # For single sample prediction
        if features.shape[0] == 1:
            probs = probabilities[0]
            
            # Find setups above threshold
            above_threshold = probs > threshold
            
            if not np.any(above_threshold):
                return None, 0.0
            
            # Find best setup among those above threshold
            best_idx = np.argmax(probs)
            best_prob = probs[best_idx]
            
            if best_prob > threshold:
                return int(best_idx), float(best_prob)
            else:
                return None, 0.0
        else:
            raise ValueError("This method is designed for single sample prediction. Use predict_batch for multiple samples.")
    
    def predict_top_setups(self, features: np.ndarray, threshold: float = None, 
                          top_n: int = None) -> List[Tuple[int, float]]:
        """
        Get top N trading setups above threshold, ranked by probability.
        
        Args:
            features: Feature vector of shape (n_features,) or (1, n_features)
            threshold: Minimum probability threshold
            top_n: Number of top setups to return
            
        Returns:
            List of (setup_index, probability) tuples, sorted by probability (descending)
        """
        if threshold is None:
            threshold = self.default_threshold
        if top_n is None:
            top_n = self.top_n_setups
        
        # Ensure features are 2D
        if features.ndim == 1:
            features = features.reshape(1, -1)
        
        # Get predictions
        probabilities = self.model.predict_probabilities(features, use_calibrated=True)
        
        # For single sample prediction
        if features.shape[0] == 1:
            probs = probabilities[0]
            
            # Find setups above threshold
            above_threshold = probs > threshold
            
            if not np.any(above_threshold):
                return []
            
            # Get indices of setups above threshold
            viable_indices = np.where(above_threshold)[0]
            viable_probs = probs[viable_indices]
            
            # Sort by probability (descending)
            sorted_indices = np.argsort(viable_probs)[::-1]
            
            # Return top N
            results = []
            for i in range(min(top_n, len(sorted_indices))):
                idx = viable_indices[sorted_indices[i]]
                prob = viable_probs[sorted_indices[i]]
                results.append((int(idx), float(prob)))
            
            return results
        else:
            raise ValueError("This method is designed for single sample prediction. Use predict_batch for multiple samples.")
    
    def predict_batch(self, features: np.ndarray, threshold: float = None) -> List[Dict]:
        """
        Predict best setups for a batch of feature vectors.
        
        Args:
            features: Feature array of shape (n_samples, n_features)
            threshold: Minimum probability threshold
            
        Returns:
            List of prediction dictionaries, one per sample
        """
        if threshold is None:
            threshold = self.default_threshold
        
        # Get predictions for all samples
        probabilities = self.model.predict_probabilities(features, use_calibrated=True)
        
        results = []
        for i in range(features.shape[0]):
            probs = probabilities[i]
            
            # Find setups above threshold
            above_threshold = probs > threshold
            viable_count = np.sum(above_threshold)
            
            if viable_count > 0:
                # Best setup
                best_idx = np.argmax(probs)
                best_prob = probs[best_idx]
                
                # Top setups above threshold
                viable_indices = np.where(above_threshold)[0]
                viable_probs = probs[viable_indices]
                sorted_indices = np.argsort(viable_probs)[::-1]
                
                top_setups = []
                for j in range(min(self.top_n_setups, len(sorted_indices))):
                    idx = viable_indices[sorted_indices[j]]
                    prob = viable_probs[sorted_indices[j]]
                    top_setups.append((int(idx), float(prob)))
                
                result = {
                    'sample_index': i,
                    'has_viable_setup': True,
                    'best_setup': int(best_idx) if best_prob > threshold else None,
                    'best_probability': float(best_prob) if best_prob > threshold else 0.0,
                    'viable_setups_count': int(viable_count),
                    'top_setups': top_setups
                }
            else:
                result = {
                    'sample_index': i,
                    'has_viable_setup': False,
                    'best_setup': None,
                    'best_probability': 0.0,
                    'viable_setups_count': 0,
                    'top_setups': []
                }
            
            results.append(result)
        
        return results
    
    def get_prediction_confidence(self, features: np.ndarray) -> Dict:
        """
        Get prediction confidence metrics for a sample.
        
        Args:
            features: Feature vector of shape (n_features,) or (1, n_features)
            
        Returns:
            Dictionary with confidence metrics
        """
        # Ensure features are 2D
        if features.ndim == 1:
            features = features.reshape(1, -1)
        
        # Get predictions
        probabilities = self.model.predict_probabilities(features, use_calibrated=True)
        probs = probabilities[0]
        
        # Calculate confidence metrics
        max_prob = np.max(probs)
        second_max_prob = np.partition(probs, -2)[-2]
        prob_spread = max_prob - second_max_prob
        
        # Entropy (uncertainty measure)
        # Add small epsilon to avoid log(0)
        eps = 1e-8
        probs_safe = np.clip(probs, eps, 1 - eps)
        entropy = -np.sum(probs_safe * np.log(probs_safe))
        
        # Number of confident predictions (above various thresholds)
        confidence_levels = {
            'very_high': np.sum(probs > 0.8),
            'high': np.sum(probs > 0.7),
            'medium': np.sum(probs > 0.5),
            'low': np.sum(probs > 0.3)
        }
        
        return {
            'max_probability': float(max_prob),
            'second_max_probability': float(second_max_prob),
            'probability_spread': float(prob_spread),
            'entropy': float(entropy),
            'confidence_levels': confidence_levels,
            'total_predictions': len(probs)
        }
    
    def explain_prediction(self, features: np.ndarray, setup_index: int) -> Dict:
        """
        Provide explanation for a specific setup prediction.
        
        Args:
            features: Feature vector
            setup_index: Index of setup to explain
            
        Returns:
            Dictionary with prediction explanation
        """
        # Ensure features are 2D
        if features.ndim == 1:
            features = features.reshape(1, -1)
        
        # Get prediction for this setup
        probabilities = self.model.predict_probabilities(features, use_calibrated=True)
        prob = probabilities[0, setup_index]
        
        # Get feature importance if available
        feature_importance = None
        if hasattr(self.model, 'feature_importance') and self.model.feature_importance is not None:
            feature_importance = self.model.feature_importance.tolist()
        
        # Check if this setup was trained
        setup_trained = setup_index in self.model.models
        
        return {
            'setup_index': setup_index,
            'probability': float(prob),
            'setup_trained': setup_trained,
            'feature_importance': feature_importance,
            'model_summary': self.model.get_model_summary()
        }


if __name__ == "__main__":
    # Test predictor with sample data
    import sys
    sys.path.append('.')
    from src.data_loader import BMPSDataLoader
    
    try:
        # Load sample data
        loader = BMPSDataLoader()
        X_train, X_val, y_train, y_val, stats = loader.load_processed_data(max_files=3)
        
        # Initialize predictor (will try to load existing model)
        predictor = BMPSPredictor()
        
        # Test single prediction
        sample_features = X_val[0]
        best_setup, probability = predictor.predict_best_setup(sample_features, threshold=0.5)
        
        if best_setup is not None:
            print(f"Best setup: {best_setup} with probability: {probability:.6f}")
            
            # Get top setups
            top_setups = predictor.predict_top_setups(sample_features, threshold=0.5)
            print(f"Top setups: {top_setups}")
            
            # Get confidence metrics
            confidence = predictor.get_prediction_confidence(sample_features)
            print(f"Prediction confidence: {confidence}")
        else:
            print("No viable setups found above threshold")
        
        # Test batch prediction
        batch_results = predictor.predict_batch(X_val[:3], threshold=0.5)
        print(f"Batch predictions: {len(batch_results)} samples processed")
        for result in batch_results:
            if result['has_viable_setup']:
                print(f"  Sample {result['sample_index']}: {result['viable_setups_count']} viable setups")
            else:
                print(f"  Sample {result['sample_index']}: No viable setups")
                
    except Exception as e:
        print(f"Predictor test failed: {e}")
        print("Make sure to train a model first!")