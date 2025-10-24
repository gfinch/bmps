# BMPS XGBoost Regression Model

A machine learning pipeline for predicting price movements across multiple time horizons using XGBoost regression.

## Project Structure

```
py-bmps/
├── src/                    # Source code (deployed to container)
│   ├── data_loader.py     # Data processing and loading (22 features, 5 labels)
│   └── model.py           # XGBoost regression model (training + inference)
├── config/                 # Configuration files
│   └── model_config.yaml  # Model hyperparameters and settings
├── models/                 # Trained model artifacts (deployed)
├── data/                   # Training data (NOT deployed)
├── api.py                  # FastAPI server for model serving
├── requirements.txt        # Python dependencies
└── README.md              # This file
```

## Model Overview

- **Input**: 22 ATR-normalized technical features per time step
- **Output**: 5 regression predictions for future price movements (ATR units)
- **Time Horizons**: 1, 2, 5, 10, 20 minutes
- **Algorithm**: XGBoost regression (separate model per time horizon)

## Usage

### Training
```bash
# Use the training script (recommended)
./train_model.sh

# Or directly with Python
cd py-bmps
python src/model.py
```

### Inference
```python
from src.model import BMPSXGBoostModel
model = BMPSXGBoostModel()
model.load_model()
predictions = model.predict(features)  # Returns (samples, 5) array
```

### API Server
```bash
cd py-bmps
python api.py
# Server runs on http://localhost:8001
```

## Deployment

Only the following are needed in the Docker container:
- `src/` directory
- `config/` directory  
- `models/` directory (trained artifacts)
- `requirements.txt`

The `data/` directory contains training data and is not needed for deployment.