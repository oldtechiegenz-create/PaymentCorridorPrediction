"""CorridorIQ prediction API.

    cd code
    python3 -m prediction_service.train                          # once: writes prediction_service/artifacts/
    python3 -m uvicorn prediction_service.api:app --port 8000    # interactive docs at /docs

Environment overrides: CORRIDORIQ_MODEL_DIR, CORRIDORIQ_DB.
"""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request

from . import features as F
from .predictor import InvalidPayment, Predictor
from .schemas import HealthResponse, PaymentRequest, RankResponse
from .train import MODEL_DIR


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.predictor = Predictor(Path(os.environ.get("CORRIDORIQ_MODEL_DIR", MODEL_DIR)),
                                    Path(os.environ.get("CORRIDORIQ_DB", F.DB_PATH)))
    yield


app = FastAPI(title="CorridorIQ Prediction API", version="1.0.0", lifespan=lifespan,
              description="Ranks candidate routes for a cross-border payment and predicts time to credit and cost.")


@app.get("/health", response_model=HealthResponse)
def health(request: Request):
    p: Predictor = request.app.state.predictor
    return HealthResponse(status="ok", model_version=p.metadata["model_version"],
                          trained_until=p.metadata["train_until"], calendar_years=list(p.calendar_years))


@app.post("/v1/routes/rank", response_model=RankResponse)
def rank_routes(payment: PaymentRequest, request: Request):
    try:
        return request.app.state.predictor.rank(**payment.model_dump())
    except InvalidPayment as e:
        raise HTTPException(status_code=422, detail=str(e))
