"""
BMPS Model Evaluator

Evaluation framework for multi-target XGBoost model including:
- Performance metrics calculation
- Probability calibration assessment
- Threshold analysis
- Model validation
"""

import logging
from typing import Dict, List, Tuple, Optional
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score, precision_recall_curve, average_precision_score
from sklearn.calibration import calibration_curve
import matplotlib.pyplot as plt
import seaborn as sns
import yaml
from pathlib import Path

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BMPSEvaluator:
    """Evaluation framework for BMPS XGBoost model."""
    
    def __init__(self, config_path: str = "config/model_config.yaml"):
        """Initialize evaluator with configuration."""
        with open(config_path, 'r') as f:
            self.config = yaml.safe_load(f)
        
        self.prediction_config = self.config['prediction']
        self.default_threshold = self.prediction_config['default_threshold']
        
    def evaluate_model_performance(self, model, X_val: np.ndarray, y_val: np.ndarray) -> Dict:
        """
        Evaluate model performance across all targets.
        
        Args:
            model: Trained BMPSXGBoostModel
            X_val: Validation features
            y_val: Validation labels
            
        Returns:
            Dictionary containing evaluation metrics
        """
        logger.info("Evaluating model performance...")
        
        # Get predictions
        probas = model.predict_probabilities(X_val, use_calibrated=False)
        probas_calibrated = model.predict_probabilities(X_val, use_calibrated=True)
        
        # Convert labels to binary
        y_val_binary = (y_val > 0).astype(int)
        
        # Calculate metrics for each target
        target_metrics = []
        for target_idx in range(y_val_binary.shape[1]):
            y_true = y_val_binary[:, target_idx]
            y_pred = probas[:, target_idx]
            y_pred_cal = probas_calibrated[:, target_idx]
            
            # Skip targets with no positive examples
            if np.sum(y_true) == 0:
                continue
                
            # Calculate metrics
            metrics = {
                'target_idx': target_idx,
                'n_positive': int(np.sum(y_true)),
                'positive_rate': float(np.mean(y_true))
            }
            
            # ROC AUC (only if we have both classes)
            if len(np.unique(y_true)) > 1:
                try:
                    metrics['auc'] = float(roc_auc_score(y_true, y_pred))
                    metrics['auc_calibrated'] = float(roc_auc_score(y_true, y_pred_cal))
                    metrics['avg_precision'] = float(average_precision_score(y_true, y_pred))
                    metrics['avg_precision_calibrated'] = float(average_precision_score(y_true, y_pred_cal))
                except Exception as e:
                    logger.warning(f"Could not calculate AUC for target {target_idx}: {e}")
                    metrics['auc'] = None
                    metrics['auc_calibrated'] = None
                    metrics['avg_precision'] = None
                    metrics['avg_precision_calibrated'] = None
            
            target_metrics.append(metrics)
        
        # Overall metrics
        overall_metrics = {
            'n_evaluated_targets': len(target_metrics),
            'total_targets': y_val_binary.shape[1],
            'avg_auc': np.mean([m['auc'] for m in target_metrics if m['auc'] is not None]),
            'avg_auc_calibrated': np.mean([m['auc_calibrated'] for m in target_metrics if m['auc_calibrated'] is not None]),
            'avg_precision_score': np.mean([m['avg_precision'] for m in target_metrics if m['avg_precision'] is not None]),
            'avg_precision_score_calibrated': np.mean([m['avg_precision_calibrated'] for m in target_metrics if m['avg_precision_calibrated'] is not None])
        }
        
        return {
            'overall_metrics': overall_metrics,
            'target_metrics': target_metrics
        }
    
    def analyze_threshold_performance(self, model, X_val: np.ndarray, y_val: np.ndarray, 
                                    thresholds: List[float] = None) -> Dict:
        """
        Analyze performance at different probability thresholds.
        
        Args:
            model: Trained model
            X_val: Validation features  
            y_val: Validation labels
            thresholds: List of thresholds to evaluate
            
        Returns:
            Threshold analysis results
        """
        if thresholds is None:
            thresholds = np.arange(0.1, 1.0, 0.1)
        
        logger.info(f"Analyzing threshold performance for {len(thresholds)} thresholds...")
        
        probas = model.predict_probabilities(X_val, use_calibrated=True)
        y_val_binary = (y_val > 0).astype(int)
        
        threshold_results = []
        
        for threshold in thresholds:
            # For each sample, find if any setup meets the threshold
            above_threshold = probas > threshold
            sample_has_viable_setup = np.any(above_threshold, axis=1)
            sample_has_true_positive = np.any(y_val_binary > 0, axis=1)
            
            # Calculate sample-level metrics
            n_samples_with_prediction = np.sum(sample_has_viable_setup)
            n_samples_with_true_positive = np.sum(sample_has_true_positive)
            
            # True positives: samples where we predict viable setup AND there is a true positive
            true_positives = np.sum(sample_has_viable_setup & sample_has_true_positive)
            false_positives = np.sum(sample_has_viable_setup & ~sample_has_true_positive)
            false_negatives = np.sum(~sample_has_viable_setup & sample_has_true_positive)
            
            # Calculate metrics
            precision = true_positives / max(1, n_samples_with_prediction)
            recall = true_positives / max(1, n_samples_with_true_positive)
            f1 = 2 * precision * recall / max(1e-8, precision + recall)
            
            # Average number of setups above threshold per sample
            avg_setups_per_sample = np.mean(np.sum(above_threshold, axis=1))
            
            threshold_results.append({
                'threshold': float(threshold),
                'n_samples_with_prediction': int(n_samples_with_prediction),
                'n_samples_with_true_positive': int(n_samples_with_true_positive),
                'precision': float(precision),
                'recall': float(recall),
                'f1_score': float(f1),
                'avg_setups_per_sample': float(avg_setups_per_sample)
            })
        
        return {
            'threshold_analysis': threshold_results,
            'recommended_threshold': self._find_optimal_threshold(threshold_results)
        }
    
    def _find_optimal_threshold(self, threshold_results: List[Dict]) -> float:
        """Find optimal threshold based on F1 score."""
        best_f1 = 0
        best_threshold = self.default_threshold
        
        for result in threshold_results:
            if result['f1_score'] > best_f1:
                best_f1 = result['f1_score']
                best_threshold = result['threshold']
        
        return best_threshold
    
    def evaluate_calibration(self, model, X_val: np.ndarray, y_val: np.ndarray) -> Dict:
        """
        Evaluate probability calibration quality.
        
        Args:
            model: Trained model
            X_val: Validation features
            y_val: Validation labels
            
        Returns:
            Calibration evaluation results
        """
        logger.info("Evaluating probability calibration...")
        
        probas = model.predict_probabilities(X_val, use_calibrated=False)
        probas_calibrated = model.predict_probabilities(X_val, use_calibrated=True)
        y_val_binary = (y_val > 0).astype(int)
        
        calibration_results = []
        
        # Evaluate calibration for targets with sufficient data
        for target_idx in range(min(10, y_val_binary.shape[1])):  # Evaluate first 10 targets
            y_true = y_val_binary[:, target_idx]
            
            if np.sum(y_true) < 5:  # Skip targets with too few positives
                continue
            
            try:
                # Calibration curve for uncalibrated predictions
                fraction_pos, mean_pred = calibration_curve(
                    y_true, probas[:, target_idx], n_bins=10
                )
                
                # Calibration curve for calibrated predictions  
                fraction_pos_cal, mean_pred_cal = calibration_curve(
                    y_true, probas_calibrated[:, target_idx], n_bins=10
                )
                
                # Brier score (lower is better)
                brier_score = np.mean((probas[:, target_idx] - y_true) ** 2)
                brier_score_cal = np.mean((probas_calibrated[:, target_idx] - y_true) ** 2)
                
                calibration_results.append({
                    'target_idx': target_idx,
                    'brier_score': float(brier_score),
                    'brier_score_calibrated': float(brier_score_cal),
                    'calibration_improved': bool(brier_score_cal < brier_score)
                })
                
            except Exception as e:
                logger.warning(f"Could not evaluate calibration for target {target_idx}: {e}")
                continue
        
        return {
            'calibration_results': calibration_results,
            'avg_brier_score': np.mean([r['brier_score'] for r in calibration_results]),
            'avg_brier_score_calibrated': np.mean([r['brier_score_calibrated'] for r in calibration_results]),
            'calibration_improvement_rate': np.mean([r['calibration_improved'] for r in calibration_results])
        }
    
    def generate_evaluation_report(self, model, X_val: np.ndarray, y_val: np.ndarray) -> Dict:
        """
        Generate comprehensive evaluation report.
        
        Args:
            model: Trained model
            X_val: Validation features
            y_val: Validation labels
            
        Returns:
            Complete evaluation report
        """
        logger.info("Generating comprehensive evaluation report...")
        
        report = {
            'model_summary': model.get_model_summary(),
            'performance_metrics': self.evaluate_model_performance(model, X_val, y_val),
            'threshold_analysis': self.analyze_threshold_performance(model, X_val, y_val),
            'calibration_evaluation': self.evaluate_calibration(model, X_val, y_val)
        }
        
        return report
    
    def save_evaluation_report(self, report: Dict, filepath: str = "models/evaluation_report.json"):
        """Save evaluation report to file."""
        import json
        
        with open(filepath, 'w') as f:
            json.dump(report, f, indent=2)
        
        logger.info(f"Evaluation report saved to {filepath}")


if __name__ == "__main__":
    # Test evaluation with sample data
    import sys
    sys.path.append('.')
    from src.data_loader import BMPSDataLoader
    from src.model import BMPSXGBoostModel
    
    # Load sample data
    loader = BMPSDataLoader()
    X_train, X_val, y_train, y_val, stats = loader.load_processed_data(max_files=5)
    
    # Create and train a simple model for testing
    model = BMPSXGBoostModel()
    model.train(X_train, y_train, X_val, y_val)
    
    # Evaluate
    evaluator = BMPSEvaluator()
    
    if model.get_model_summary()['n_targets_trained'] > 0:
        report = evaluator.generate_evaluation_report(model, X_val, y_val)
        print("Evaluation completed!")
        print(f"Model performance: {report['performance_metrics']['overall_metrics']}")
        print(f"Threshold analysis: {report['threshold_analysis']['recommended_threshold']}")
    else:
        print("No trained models to evaluate")