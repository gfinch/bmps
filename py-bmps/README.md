# BMPS XGBoost Trading Model

A machine learning pipeline for predicting trading setup success probabilities using XGBoost.

## Project Structure

```
py-bmps/
├── src/                    # Source code (deployed to container)
│   ├── data_loader.py     # Data processing and loading
│   ├── model.py           # XGBoost model implementation  
│   ├── trainer.py         # Training pipeline
│   ├── evaluator.py       # Model evaluation and metrics
│   └── predictor.py       # Inference pipeline
├── config/                 # Configuration files
│   └── model_config.yaml  # Model hyperparameters and settings
├── models/                 # Trained model artifacts (deployed)
├── data/                   # Training data (NOT deployed)
├── requirements.txt        # Python dependencies
└── README.md              # This file
```

## Model Overview

- **Input**: 69 technical features per time step
- **Output**: 194 probability scores for different trading setups
- **Objective**: Find the setup with highest probability of success above threshold
- **Algorithm**: XGBoost with probability calibration

## Usage

### Training
```python
from src.trainer import BMPSTrainer
trainer = BMPSTrainer()
trainer.train()
```

### Inference
```python
from src.predictor import BMPSPredictor
predictor = BMPSPredictor()
best_setup, probability = predictor.predict_best_setup(features, threshold=0.7)
```

## Deployment

Only the following are needed in the Docker container:
- `src/` directory
- `config/` directory  
- `models/` directory (trained artifacts)
- `requirements.txt`

The `data/` directory contains training data and is not needed for deployment.