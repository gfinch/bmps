"""
BMPS Model API Server

FastAPI server for serving XGBoost model predictions.
Designed for internal communication with the BMPS Scala core.
"""

import logging
import time
from typing import List, Optional, Dict, Any
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn

from src.predictor import BMPSPredictor

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global predictor instance
predictor: Optional[BMPSPredictor] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Handle startup and shutdown events."""
    # Startup
    global predictor
    try:
        logger.info("Loading BMPS predictor...")
        predictor = BMPSPredictor()
        logger.info("Model loaded successfully!")
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
class PredictionRequest(BaseModel):
    """Request model for single prediction."""
    features: List[float] = Field(..., description="69 technical features", min_length=69, max_length=69)
    threshold: float = Field(0.7, description="Minimum probability threshold", ge=0.0, le=1.0)


class BatchPredictionRequest(BaseModel):
    """Request model for batch predictions."""
    features_batch: List[List[float]] = Field(..., description="Batch of feature arrays")
    threshold: float = Field(0.7, description="Minimum probability threshold", ge=0.0, le=1.0)
    top_k: int = Field(5, description="Number of top setups to return", ge=1, le=10)


class PredictionResponse(BaseModel):
    """Response model for single prediction."""
    best_setup: Optional[int] = Field(description="Best setup ID (0-193) or null if none above threshold")
    probability: Optional[float] = Field(description="Probability of success for best setup")
    setup_name: Optional[str] = Field(description="Human-readable setup name")
    confidence: str = Field(description="Confidence level: low/medium/high")
    inference_time_ms: float = Field(description="Time taken for inference in milliseconds")
    

class BatchPredictionResponse(BaseModel):
    """Response model for batch predictions."""
    predictions: List[PredictionResponse] = Field(description="List of predictions")
    total_inference_time_ms: float = Field(description="Total time for batch inference")


class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    model_loaded: bool
    inference_time_ms: Optional[float] = None


# Health check endpoint
@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    # Quick inference test
    try:
        start_time = time.time()
        # Test with dummy features
        test_features = [0.0] * 69
        predictor.predict_best_setup(test_features, threshold=0.9)  # High threshold so no result expected
        inference_time = (time.time() - start_time) * 1000
        
        return HealthResponse(
            status="healthy",
            model_loaded=True,
            inference_time_ms=inference_time
        )
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        return HealthResponse(
            status="unhealthy",
            model_loaded=True,
            inference_time_ms=None
        )


# Startup event
@app.on_event("startup")
async def startup_event():
    """Load the model on startup."""
    global predictor
    try:
        logger.info("Loading BMPS predictor...")
        predictor = BMPSPredictor()
        logger.info("Model loaded successfully!")
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
        raise


# Health check endpoint
@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    # Quick inference test
    try:
        start_time = time.time()
        # Test with dummy features
        test_features = [0.0] * 69
        predictor.predict_best_setup(test_features, threshold=0.9)  # High threshold so no result expected
        inference_time = (time.time() - start_time) * 1000
        
        return HealthResponse(
            status="healthy",
            model_loaded=True,
            inference_time_ms=inference_time
        )
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        return HealthResponse(
            status="unhealthy",
            model_loaded=True,
            inference_time_ms=None
        )


# Single prediction endpoint
@app.post("/predict", response_model=PredictionResponse)
async def predict_best_setup(request: PredictionRequest):
    """
    Predict the best trading setup for given features.
    
    Returns the setup with highest probability above the threshold.
    """
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        start_time = time.time()
        
        # Make prediction
        best_setup, probability = predictor.predict_best_setup(
            features=request.features,
            threshold=request.threshold
        )
        
        inference_time = (time.time() - start_time) * 1000
        
        # Determine confidence level
        if probability is None:
            confidence = "none"
        elif probability < 0.6:
            confidence = "low"
        elif probability < 0.8:
            confidence = "medium"
        else:
            confidence = "high"
        
        # Get setup name (if available)
        setup_name = None
        if best_setup is not None:
            # Convert setup index back to (order_type, stop_loss_ticks) format
            # stopLossTicks: (4 to 100) = 97 values
            # orderTypes: [Long, Short] = 2 values  
            # Total: 97 * 2 = 194 setups
            # Index mapping: Long_4=0, Short_4=1, Long_5=2, Short_5=3, ...
            stop_loss_ticks = list(range(4, 101))  # 4 to 100 inclusive
            order_types = ["Long", "Short"]
            
            tick_index = best_setup // 2  # Which stop loss tick (0-96)
            order_type_index = best_setup % 2  # Which order type (0=Long, 1=Short)
            
            if tick_index < len(stop_loss_ticks):
                tick = stop_loss_ticks[tick_index]
                order_type = order_types[order_type_index]
                setup_name = f"{order_type}_{tick}"
        
        return PredictionResponse(
            best_setup=best_setup,
            probability=probability,
            setup_name=setup_name,
            confidence=confidence,
            inference_time_ms=inference_time
        )
        
    except Exception as e:
        logger.error(f"Prediction failed: {e}")
        raise HTTPException(status_code=500, detail=f"Prediction failed: {str(e)}")


# Batch prediction endpoint
@app.post("/predict/batch", response_model=BatchPredictionResponse)
async def predict_batch(request: BatchPredictionRequest):
    """
    Predict best setups for a batch of feature arrays.
    
    More efficient than multiple single predictions.
    """
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        start_time = time.time()
        predictions = []
        
        for features in request.features_batch:
            if len(features) != 69:
                raise HTTPException(
                    status_code=400, 
                    detail=f"Each feature array must have exactly 69 elements, got {len(features)}"
                )
            
            pred_start = time.time()
            best_setup, probability = predictor.predict_best_setup(
                features=features,
                threshold=request.threshold
            )
            pred_time = (time.time() - pred_start) * 1000
            
            # Determine confidence
            if probability is None:
                confidence = "none"
            elif probability < 0.6:
                confidence = "low"
            elif probability < 0.8:
                confidence = "medium"
            else:
                confidence = "high"
            
            setup_name = None
            if best_setup is not None:
                # Convert setup index back to (order_type, stop_loss_ticks) format
                stop_loss_ticks = list(range(4, 101))  # 4 to 100 inclusive
                order_types = ["Long", "Short"]
                
                tick_index = best_setup // 2  # Which stop loss tick (0-96)
                order_type_index = best_setup % 2  # Which order type (0=Long, 1=Short)
                
                if tick_index < len(stop_loss_ticks):
                    tick = stop_loss_ticks[tick_index]
                    order_type = order_types[order_type_index]
                    setup_name = f"{order_type}_{tick}"
            
            predictions.append(PredictionResponse(
                best_setup=best_setup,
                probability=probability,
                setup_name=setup_name,
                confidence=confidence,
                inference_time_ms=pred_time
            ))
        
        total_time = (time.time() - start_time) * 1000
        
        return BatchPredictionResponse(
            predictions=predictions,
            total_inference_time_ms=total_time
        )
        
    except Exception as e:
        logger.error(f"Batch prediction failed: {e}")
        raise HTTPException(status_code=500, detail=f"Batch prediction failed: {str(e)}")


# Top setups endpoint
@app.post("/predict/top")
async def predict_top_setups(request: PredictionRequest):
    """
    Get top N setups above threshold with their probabilities.
    """
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        start_time = time.time()
        
        top_setups = predictor.predict_top_setups(
            features=request.features,
            threshold=request.threshold,
            top_k=5
        )
        
        inference_time = (time.time() - start_time) * 1000
        
        results = []
        for setup_id, prob in top_setups:
            # Convert setup index back to (order_type, stop_loss_ticks) format
            stop_loss_ticks = list(range(4, 101))  # 4 to 100 inclusive  
            order_types = ["Long", "Short"]
            
            tick_index = setup_id // 2  # Which stop loss tick (0-96)
            order_type_index = setup_id % 2  # Which order type (0=Long, 1=Short)
            
            setup_name = f"Setup_{setup_id:03d}"  # fallback
            if tick_index < len(stop_loss_ticks):
                tick = stop_loss_ticks[tick_index]
                order_type = order_types[order_type_index]
                setup_name = f"{order_type}_{tick}"
            
            results.append({
                "setup": setup_id,
                "probability": prob,
                "setup_name": setup_name
            })
        
        return {
            "top_setups": results,
            "count": len(results),
            "inference_time_ms": inference_time
        }
        
    except Exception as e:
        logger.error(f"Top setups prediction failed: {e}")
        raise HTTPException(status_code=500, detail=f"Prediction failed: {str(e)}")


# Model info endpoint
@app.get("/model/info")
async def model_info():
    """Get information about the loaded model."""
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        model_summary = predictor.get_model_summary()
        return {
            "model_type": "XGBoost Multi-Target",
            "n_features": 69,
            "n_targets": 194,
            "model_summary": model_summary,
            "api_version": "1.0.0"
        }
    except Exception as e:
        logger.error(f"Failed to get model info: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get model info: {str(e)}")


if __name__ == "__main__":
    # Run the API server
    uvicorn.run(
        "api:app",
        host="127.0.0.1",  # Only accessible locally
        port=8001,  # Different from your main app (probably 8000)
        reload=False,  # Set to True for development
        log_level="info"
    )