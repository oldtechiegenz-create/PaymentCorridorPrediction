"""CorridorIQ API: route ranking and predictions, plus the history behind the Historical Cases tab.

    cd code
    python3 -m prediction_service.train                          # once: writes prediction_service/artifacts/
    python3 -m uvicorn prediction_service.api:app --port 8000    # portal at /, interactive docs at /docs

Environment overrides: CORRIDORIQ_MODEL_DIR, CORRIDORIQ_DB, CORRIDORIQ_PORTAL.
"""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response

from . import features as F
from .history_api import router as history_router
from .history_repository import HistoryRepository
from .predictor import InvalidPayment, Predictor
from .schemas import HealthResponse, PaymentRequest, RankResponse
from .train import MODEL_DIR

PORTAL_PATH = Path(os.environ.get("CORRIDORIQ_PORTAL", Path(__file__).resolve().parents[2] / "corridoriq.html"))


@asynccontextmanager
async def lifespan(app: FastAPI):
    db = Path(os.environ.get("CORRIDORIQ_DB", F.DB_PATH))
    app.state.predictor = Predictor(Path(os.environ.get("CORRIDORIQ_MODEL_DIR", MODEL_DIR)), db)
    app.state.history = HistoryRepository(db)
    yield


app = FastAPI(title="CorridorIQ API", version="1.1.0", lifespan=lifespan,
              description="Ranks candidate routes for a cross-border payment, predicts time to credit and cost, "
                          "and serves the payment history behind the portal's Historical Cases tab.")

# The portal may be opened from disk (Origin: null) or from a local dev server; this is a local MVP service.
app.add_middleware(CORSMiddleware, allow_origin_regex=r"^(null|https?://(localhost|127\.0\.0\.1)(:\d+)?)$",
                   allow_methods=["GET", "POST"], allow_headers=["Content-Type"])
app.include_router(history_router)


@app.get("/", include_in_schema=False)
def portal():
    if not PORTAL_PATH.is_file():
        raise HTTPException(status_code=404, detail=f"Portal not found at {PORTAL_PATH}")
    return FileResponse(PORTAL_PATH, media_type="text/html")


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    return Response(status_code=204)


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
