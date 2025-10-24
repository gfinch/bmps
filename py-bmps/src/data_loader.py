"""
BMPS Data Loader

Handles loading and preprocessing of training data from parquet files.
Converts string-encoded feature/label vectors to numpy arrays.
"""

import os
import ast
import logging
from pathlib import Path
from typing import Tuple, List, Optional
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
import yaml

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BMPSDataLoader:
    """Data loader for BMPS training data."""
    
    def __init__(self, config_path: str = "config/model_config.yaml"):
        """Initialize data loader with configuration."""
        with open(config_path, 'r') as f:
            self.config = yaml.safe_load(f)
        
        self.data_dir = Path(self.config['data']['data_dir'])
        self.feature_dim = self.config['data']['feature_dim']
        self.label_dim = self.config['data']['label_dim']
        self.validation_split = self.config['data']['validation_split']
        self.random_seed = self.config['data']['random_seed']
        
        # Cache for loaded data
        self._raw_data = None
        self._processed_data = None
        
    def get_parquet_files(self) -> List[Path]:
        """Get all parquet files in the data directory."""
        parquet_files = list(self.data_dir.rglob("*.parquet"))
        logger.info(f"Found {len(parquet_files)} parquet files")
        return sorted(parquet_files)
    
    def load_raw_data(self, max_files: Optional[int] = None) -> pd.DataFrame:
        """
        Load all parquet files into a single DataFrame.
        
        Args:
            max_files: Optional limit on number of files to load (for testing)
            
        Returns:
            Combined DataFrame with all training data
        """
        if self._raw_data is not None:
            return self._raw_data
            
        parquet_files = self.get_parquet_files()
        
        if max_files:
            parquet_files = parquet_files[:max_files]
            logger.info(f"Loading only first {max_files} files for testing")
        
        dfs = []
        for file_path in parquet_files:
            try:
                df = pd.read_parquet(file_path)
                dfs.append(df)
            except Exception as e:
                logger.warning(f"Failed to load {file_path}: {e}")
                continue
        
        if not dfs:
            raise ValueError("No valid parquet files found")
        
        combined_df = pd.concat(dfs, ignore_index=True)
        logger.info(f"Loaded {len(combined_df)} samples from {len(dfs)} files")
        
        # Cache the result
        self._raw_data = combined_df
        return combined_df
    
    def parse_vector_string(self, vector_str: str) -> np.ndarray:
        """Parse string representation of vector to numpy array."""
        try:
            vector_list = ast.literal_eval(vector_str)
            return np.array(vector_list, dtype=np.float32)
        except Exception as e:
            logger.error(f"Failed to parse vector: {vector_str[:100]}... Error: {e}")
            raise
    
    def process_features_and_labels(self, df: pd.DataFrame) -> Tuple[np.ndarray, np.ndarray]:
        """
        Process feature_vector and label_vector columns into numpy arrays.
        Uses only the first label_dim targets from the full label vector.
        
        Args:
            df: Raw DataFrame with string-encoded vectors
            
        Returns:
            Tuple of (features, labels) as numpy arrays
        """
        logger.info("Processing feature and label vectors...")
        logger.info(f"Using only first {self.label_dim} targets out of full label vector")
        
        n_samples = len(df)
        features = np.zeros((n_samples, self.feature_dim), dtype=np.float32)
        labels = np.zeros((n_samples, self.label_dim), dtype=np.float32)
        
        # Process in batches for memory efficiency
        batch_size = 1000
        for i in range(0, n_samples, batch_size):
            end_idx = min(i + batch_size, n_samples)
            
            # Process features
            for j in range(i, end_idx):
                feature_vec = self.parse_vector_string(df.iloc[j]['feature_vector'])
                if len(feature_vec) != self.feature_dim:
                    raise ValueError(f"Expected {self.feature_dim} features, got {len(feature_vec)}")
                features[j] = feature_vec
                
                # Parse full label vector but only use first label_dim targets
                full_label_vec = self.parse_vector_string(df.iloc[j]['label_vector'])
                if len(full_label_vec) < self.label_dim:
                    raise ValueError(f"Label vector has {len(full_label_vec)} targets, need at least {self.label_dim}")
                
                # Use only the first label_dim targets
                labels[j] = full_label_vec[:self.label_dim]
            
            if (i // batch_size + 1) % 10 == 0:
                logger.info(f"Processed {end_idx}/{n_samples} samples")
        
        logger.info(f"Processed features shape: {features.shape}")
        logger.info(f"Processed labels shape: {labels.shape}")
        
        return features, labels
    
    def create_train_val_split(self, features: np.ndarray, labels: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        """
        Create train/validation split.
        
        Args:
            features: Feature array
            labels: Label array
            
        Returns:
            Tuple of (X_train, X_val, y_train, y_val)
        """
        return train_test_split(
            features, labels,
            test_size=self.validation_split,
            random_state=self.random_seed,
            shuffle=True
        )
    
    def get_data_stats(self, features: np.ndarray, labels: np.ndarray) -> dict:
        """Get statistics about the dataset."""
        # Calculate statistics for regression labels
        positive_labels = (labels > 0).sum()
        negative_labels = (labels < 0).sum()
        zero_labels = (labels == 0).sum()
        
        stats = {
            'n_samples': len(features),
            'n_features': features.shape[1],
            'n_targets': labels.shape[1],
            'feature_mean': float(np.mean(features)),
            'feature_std': float(np.std(features)),
            'feature_min': float(np.min(features)),
            'feature_max': float(np.max(features)),
            'label_mean': float(np.mean(labels)),
            'label_std': float(np.std(labels)),
            'label_min': float(np.min(labels)),
            'label_max': float(np.max(labels)),
            'positive_labels': int(positive_labels),
            'negative_labels': int(negative_labels),
            'zero_labels': int(zero_labels),
            'positive_rate': float(positive_labels / labels.size),
            'negative_rate': float(negative_labels / labels.size),
            'zero_rate': float(zero_labels / labels.size)
        }
        
        return stats
    
    def load_processed_data(self, max_files: Optional[int] = None) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, dict]:
        """
        Load and process all data, return train/val splits and stats.
        
        Args:
            max_files: Optional limit on number of files to load
            
        Returns:
            Tuple of (X_train, X_val, y_train, y_val, stats)
        """
        # Load raw data
        df = self.load_raw_data(max_files=max_files)
        
        # Process vectors
        features, labels = self.process_features_and_labels(df)
        
        # Get statistics
        stats = self.get_data_stats(features, labels)
        logger.info(f"Dataset stats: {stats}")
        
        # Create splits
        X_train, X_val, y_train, y_val = self.create_train_val_split(features, labels)
        
        logger.info(f"Train set: {X_train.shape[0]} samples")
        logger.info(f"Validation set: {X_val.shape[0]} samples")
        
        return X_train, X_val, y_train, y_val, stats


if __name__ == "__main__":
    # Test the data loader
    loader = BMPSDataLoader()
    
    # Test with a small subset first
    X_train, X_val, y_train, y_val, stats = loader.load_processed_data(max_files=5)
    
    print(f"Loaded data successfully!")
    print(f"Training features: {X_train.shape}")
    print(f"Training labels: {y_train.shape}")
    print(f"Validation features: {X_val.shape}")
    print(f"Validation labels: {y_val.shape}")
    print(f"Stats: {stats}")