"""CorridorIQ prediction service: scores candidate routes with the time-to-credit and cost models.

This is the ML half of CorridorIQ. The Java backend (code/backend) serves the portal and the history API,
validates a payment, computes the features of each route, and calls this service for the scores; it then
ranks the routes and answers /v1/routes/rank. The portal never calls this service directly.

    cd code
    python3 -m prediction_service.train                          # once: writes prediction_service/artifacts/
    python3 -m uvicorn prediction_service.api:app --port 8001    # interactive docs at /docs

Environment override: CORRIDORIQ_MODEL_DIR.
"""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request

from .predictor import InvalidPayment, Predictor
from .schemas import HealthResponse, ScoreRequest, ScoreResponse
from .train import MODEL_DIR


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.predictor = Predictor(Path(os.environ.get("CORRIDORIQ_MODEL_DIR", MODEL_DIR)))
    yield


app = FastAPI(title="CorridorIQ prediction service", version="3.0.0", lifespan=lifespan,
              description="Scores the eligible routes of a cross-border payment: ranker score, predicted time to "
                          "credit and cost. Called by the CorridorIQ Java backend.")


@app.get("/health", response_model=HealthResponse)
def health(request: Request):
    p: Predictor = request.app.state.predictor
    return HealthResponse(status="ok", model_version=p.metadata["model_version"],
                          trained_until=p.metadata["train_until"])


@app.post("/v1/routes/score", response_model=ScoreResponse)
def score_routes(body: ScoreRequest, request: Request):
    p: Predictor = request.app.state.predictor
    try:
        scores = p.score(body.payment.model_dump(), [r.model_dump() for r in body.routes])
    except InvalidPayment as e:
        raise HTTPException(status_code=422, detail=str(e))
    return ScoreResponse(model_version=p.metadata["model_version"], scores=scores)
