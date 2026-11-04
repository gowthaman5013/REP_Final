"""
AquaFlow / AquaTrack - Machine Learning Microservice
Production Next-Month Water Bill & Consumption Prediction Engine
Algorithm: Random Forest Regressor (scikit-learn)
"""

import os
import sys
import logging
from contextlib import asynccontextmanager
from typing import Optional, Dict, Any

from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

# Ensure ml-service root is in sys.path
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from predict import predictor as consumption_predictor
from app.config import settings
from app.logging_config import setup_logging

setup_logging()
logger = logging.getLogger("ml_service")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("=" * 60)
    logger.info("STARTING AQUATRACK CONSUMPTION PREDICTION ML SERVICE")
    logger.info("=" * 60)

    # Verify Consumption Forecaster (Random Forest)
    if consumption_predictor.is_ready():
        logger.info("[ML Engine] Random Forest Consumption Forecaster loaded successfully.")
    else:
        logger.warning("[ML Engine] Random Forest model binary missing or failed to load.")
    logger.info("=" * 60)

    yield

    logger.info("Shutting down AquaTrack ML Microservice...")


app = FastAPI(
    title="AquaTrack Water Bill & Consumption Prediction Microservice",
    description="""
## Enterprise AI Core for Smart Water Billing
### 🔮 Consumption Forecasting Engine (`/predict`)
- **Algorithm**: Random Forest Regressor (`scikit-learn`)
- **Features**: Lagged consumption ($t-1, t-2, t-3$), 3-month rolling average, occupants count, calendar month, and trend slope.
- **Output**: Accurate expected household consumption in litres for next billing cycle.
- **Spring Boot Caller**: `PredictionMlClient`
    """,
    version="1.0.0",
    lifespan=lifespan
)

# Enable CORS for local and Docker integration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# =====================================================================
# PYDANTIC SCHEMAS FOR CONSUMPTION FORECASTING
# =====================================================================
class PredictionRequest(BaseModel):
    household_id: Optional[int] = Field(default=None, description="Unique identifier for the household / apartment")
    previous_month_consumption: float = Field(..., ge=0, description="Consumption in litres for previous month (t-1)")
    two_months_ago_consumption: float = Field(..., ge=0, description="Consumption in litres for 2 months ago (t-2)")
    three_months_ago_consumption: float = Field(..., ge=0, description="Consumption in litres for 3 months ago (t-3)")
    three_month_average: Optional[float] = Field(default=None, ge=0, description="Rolling 3-month average in litres")
    residents_count: int = Field(..., ge=1, le=20, description="Number of household occupants (1-20)")
    prediction_month: int = Field(..., ge=1, le=12, description="Target calendar month (1-12)")
    previous_consumption_trend: Optional[float] = Field(default=None, description="Trend difference: (t-1 minus t-3)")

    class Config:
        json_schema_extra = {
            "example": {
                "household_id": 101,
                "previous_month_consumption": 24000.0,
                "two_months_ago_consumption": 22000.0,
                "three_months_ago_consumption": 21000.0,
                "three_month_average": 22333.33,
                "residents_count": 4,
                "prediction_month": 11,
                "previous_consumption_trend": 3000.0
            }
        }


class PredictionResponse(BaseModel):
    household_id: Optional[int]
    prediction_month: int
    predicted_consumption_litres: float
    model_name: str
    model_version: str
    prediction_status: str


class HealthResponse(BaseModel):
    status: str
    model_name: str
    model_version: str
    model_loaded: bool
    engine: Dict[str, Any]


# =====================================================================
# ROOT & HEALTH CHECK ENDPOINTS
# =====================================================================
@app.get("/", tags=["System Information"])
def root_info():
    """Returns general microservice information and interactive documentation links."""
    return {
        "service": "AquaTrack ML Prediction Microservice",
        "status": "UP",
        "version": "1.0.0",
        "engine": {
            "endpoint": "POST /predict",
            "model": consumption_predictor.model_name,
            "version": consumption_predictor.model_version,
            "ready": consumption_predictor.is_ready()
        },
        "documentation": "/docs",
        "health": "/health"
    }


@app.get("/health", response_model=HealthResponse, tags=["Diagnostics"])
def health_check():
    """Health check compatible with Spring Boot's PredictionMlClient."""
    rf_ready = consumption_predictor.is_ready()
    return {
        "status": "UP" if rf_ready else "DOWN",
        "model_name": consumption_predictor.model_name,
        "model_version": consumption_predictor.model_version,
        "model_loaded": rf_ready,
        "engine": {
            "algorithm": "Random Forest Regressor",
            "version": consumption_predictor.model_version,
            "loaded": rf_ready,
            "status": "UP" if rf_ready else "DOWN"
        }
    }


# =====================================================================
# CONSUMPTION FORECASTING ENDPOINT (/predict)
# =====================================================================
@app.post("/predict", response_model=PredictionResponse, tags=["Consumption Forecasting"])
def predict_water_consumption(request: PredictionRequest):
    """
    Predicts expected next-month water consumption in litres using Random Forest Regressor.
    Used by Spring Boot's PredictionMlClient during billing preparations.
    """
    if not consumption_predictor.is_ready():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Predictive Random Forest model is not initialized or model file is missing."
        )

    try:
        data = request.model_dump()
        pred_litres, pred_status = consumption_predictor.predict(data)

        return {
            "household_id": request.household_id,
            "prediction_month": request.prediction_month,
            "predicted_consumption_litres": pred_litres,
            "model_name": consumption_predictor.model_name,
            "model_version": consumption_predictor.model_version,
            "prediction_status": pred_status
        }
    except ValueError as ve:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(ve))
    except Exception as e:
        logger.error(f"Prediction inference error: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Prediction inference error: {str(e)}"
        )


# =====================================================================
# EXCEPTION HANDLER
# =====================================================================
@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    logger.error(f"Unhandled error processing request {request.url}: {exc}", exc_info=True)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"detail": f"Internal server error in ML microservice: {str(exc)}"}
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
