import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import xgboost as xgb
import pandas as pd
import json
import os

app = FastAPI()

# Load Model and Config
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_LONG_PATH = os.path.join(BASE_DIR, "xgboost_long_strategy.json")
MODEL_SHORT_PATH = os.path.join(BASE_DIR, "xgboost_short_strategy.json")
CONFIG_PATH = os.path.join(BASE_DIR, "model_config.json")

model_long = None
model_short = None
config = None

print(f"Loading models from {BASE_DIR}")

try:
    # Load Config
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r") as f:
            config = json.load(f)
        print("Config loaded.")
    else:
        print("Config file not found.")

    # Load Long Model
    if os.path.exists(MODEL_LONG_PATH):
        model_long = xgb.Booster()
        model_long.load_model(MODEL_LONG_PATH)
        print("Long model loaded.")
    else:
        print("Long model not found.")

    # Load Short Model
    if os.path.exists(MODEL_SHORT_PATH):
        model_short = xgb.Booster()
        model_short.load_model(MODEL_SHORT_PATH)
        print("Short model loaded.")
    else:
        print("Short model not found.")

except Exception as e:
    print(f"Error loading models: {e}")


class PredictionRequest(BaseModel):
    rsi: float
    adx: float
    trendStrength: float
    maSpread: float
    atr: float
    volume: float
    hour: int
    minute: int
    rule1: bool
    rule2: bool
    rule3: bool
    rule4: bool
    rule5: bool
    rule6: bool
    rule7: bool
    rule8: bool
    rule9: bool
    rule10: bool
    rule11: bool
    rule12: bool

    spreadChange1: float
    spreadChange2: float
    spreadChange3: float
    spreadChange5: float
    spreadChange10: float

    rsiChange1: float
    rsiChange2: float
    rsiChange3: float
    rsiChange5: float
    rsiChange10: float


@app.get("/health")
def health_check():
    if model_long is None or model_short is None:
        raise HTTPException(status_code=503, detail="Models not loaded")
    return {"status": "ok", "models_loaded": True}


@app.post("/predict")
def predict(req: PredictionRequest):
    if model_long is None or model_short is None:
        raise HTTPException(status_code=503, detail="Models not loaded")

    # Convert request to dict
    try:
        data = req.model_dump()
    except AttributeError:
        data = req.dict()

    # Convert booleans to ints (0/1)
    for k, v in data.items():
        if k.startswith("rule"):
            data[k] = int(v)

    # Create DataFrame
    df = pd.DataFrame([data])

    # Ensure features match config (using long config as reference, assuming same features)
    features = config["long"]["features"]
    try:
        df = df[features]
    except KeyError as e:
        raise HTTPException(status_code=400, detail=f"Missing features: {e}")

    # Create DMatrix
    dmatrix = xgb.DMatrix(df)

    # Predict Long
    prob_long = float(model_long.predict(dmatrix)[0])
    thresh_long = config["long"]["threshold"]
    is_long = prob_long >= thresh_long

    # Predict Short
    prob_short = float(model_short.predict(dmatrix)[0])
    thresh_short = config["short"]["threshold"]
    is_short = prob_short >= thresh_short

    action = "WAIT"
    confidence = 0.0
    threshold = 0.0

    if is_long and not is_short:
        action = "BUY"
        confidence = prob_long
        threshold = thresh_long
    elif is_short and not is_long:
        action = "SELL"
        confidence = prob_short
        threshold = thresh_short
    elif is_long and is_short:
        # Conflict: Both signals active.
        # Heuristic: Pick the one with higher relative confidence (prob / threshold)
        ratio_long = prob_long / thresh_long
        ratio_short = prob_short / thresh_short

        if ratio_long > ratio_short:
            action = "BUY"
            confidence = prob_long
            threshold = thresh_long
        else:
            action = "SELL"
            confidence = prob_short
            threshold = thresh_short
        # Alternatively, just WAIT on conflict.
        # action = "WAIT"

    return {
        "action": action,
        "confidence": confidence,
        "threshold": threshold
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)
