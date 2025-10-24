"""
BMPS Model API Server (Regression Version)

FastAPI server for serving XGBoost regression model predictions.
Designed for internal communication with the BMPS Scala core.

New model predicts continuous price movements (in ATR units) for multiple time horizons:
- 1 minute, 2 minutes, 5 minutes, 10 minutes, 20 minutes
"""

import logging
import time
from typing import List, Optional, Dict, Any
from pathlib import Path
from contextlib import asynccontextmanager

import numpy as np
import yaml
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn

from src.model import BMPSXGBoostModel

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Load configuration
with open("config/model_config.yaml", 'r') as f:
    config = yaml.safe_load(f)

# Get model dimensions from config
n_features = config['data']['feature_dim']  # 22 features
n_targets = config['data']['label_dim']     # 5 time horizons

# Time horizon labels
TIME_HORIZONS = ["1min", "2min", "5min", "10min", "20min"]

# Global model instance
model: Optional[BMPSXGBoostModel] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Handle startup and shutdown events."""
    # Startup
    global model
    try:
        logger.info("Loading BMPS regression model...")
        model = BMPSXGBoostModel()
        model.load_model()
        logger.info(f"Model loaded successfully! Features: {n_features}, Targets: {n_targets}")
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
        raise
    
    yield
    
    # Shutdown (cleanup if needed)
    logger.info("Shutting down API...")


# FastAPI app with lifespan
app = FastAPI(
    title="BMPS Model API",
    description="XGBoost model API for trading setup predictions",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware for local development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, specify your Scala app's origin
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Request/Response models
class PointPredictionRequest(BaseModel):
    """Request model for predictions with ATR conversion to points."""
    features: List[float] = Field(..., description="22 ATR-normalized technical features", min_length=22, max_length=22)
    atr_value: float = Field(..., description="Current ATR value in points for conversion", gt=0.0)

class PointPredictionResponse(BaseModel):
    """Response model for point-based predictions."""
    predictions_atr: List[float] = Field(description=f"Predicted price movements for {TIME_HORIZONS} (ATR units)")
    predictions_points: List[float] = Field(description=f"Predicted price movements for {TIME_HORIZONS} (price points)")
    time_horizons: List[str] = Field(default=TIME_HORIZONS, description="Time horizon labels")
    inference_time_ms: float = Field(description="Time taken for inference in milliseconds")
    

class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    model_loaded: bool
    inference_time_ms: Optional[float] = None


# Health check endpoint
@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    # Quick inference test
    try:
        start_time = time.time()
        # Test with dummy features (22 features for new model)
        test_features = np.random.randn(1, 22)
        predictions = model.predict(test_features)
        inference_time = (time.time() - start_time) * 1000
        
        # Verify we get the expected output shape
        if predictions.shape == (1, 5):
            return HealthResponse(
                status="healthy",
                model_loaded=True,
                inference_time_ms=inference_time
            )
        else:
            raise ValueError(f"Unexpected prediction shape: {predictions.shape}")
            
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        return HealthResponse(
            status="unhealthy",
            model_loaded=True,
            inference_time_ms=None
        )

# Point-based predictions endpoint
@app.post("/predictedPointMoves", response_model=PointPredictionResponse)
async def predict_point_moves(request: PointPredictionRequest):
    """
    Predict price movements converted from ATR units to actual price points.
    
    Takes ATR-normalized features and an ATR value, returns predictions in both 
    ATR units and converted price points for all time horizons.
    """
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        start_time = time.time()
        
        # Convert features list to numpy array (add batch dimension)
        features_array = np.array(request.features).reshape(1, -1)
        
        # Make prediction in ATR units
        predictions = model.predict(features_array)
        pred_atr = predictions[0].tolist()  # ATR units
        
        # Convert to price points
        pred_points = [atr_val * request.atr_value for atr_val in pred_atr]
        
        inference_time = (time.time() - start_time) * 1000

        # Log prediction results
        logger.info(f"ATR Predictions: {pred_atr}")
        logger.info(f"Point Predictions: {pred_points}")
        
        return PointPredictionResponse(
            predictions_atr=[round(p, 4) for p in pred_atr],
            predictions_points=[round(p, 4) for p in pred_points],
            time_horizons=TIME_HORIZONS,
            inference_time_ms=round(inference_time, 2)
        )
        
    except Exception as e:
        logger.error(f"Point prediction failed: {e}")
        raise HTTPException(status_code=500, detail=f"Point prediction failed: {str(e)}")

if __name__ == "__main__":
    # Run the API server
    uvicorn.run(
        "api:app",
        host="0.0.0.0",  # Accessible from anywhere in container
        port=8001,  # Different from your main app (probably 8000)
        reload=False,  # Set to True for development
        log_level="info"
    )