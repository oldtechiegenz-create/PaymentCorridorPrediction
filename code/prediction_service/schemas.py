"""Request and response models for the scoring API (the Java backend is the only caller).

The Java copy is PredictionModels.java in code/backend; change both together.
"""
from typing import List, Literal, Optional

from pydantic import BaseModel, Field


class ScorePayment(BaseModel):
    """The payment-level inputs, as the backend computes them (named like v_ranking_training)."""
    corridor_id: str
    bene_bank_id: str
    client_type: Literal["Retail", "FI"]
    amount_usd: float = Field(gt=0)
    priority: int = Field(ge=0, le=100, description="0 = cheapest route preferred, 100 = fastest")
    release_hour_et: float = Field(ge=0, lt=24, description="Hour of release in New York, e.g. 13.02")
    release_weekday: int = Field(ge=0, le=6, description="0 = Sunday ... 6 = Saturday, on the US value date")
    day_of_month: int = Field(ge=1, le=31)
    has_deadline: int = Field(ge=0, le=1)


class RouteFeatures(BaseModel):
    """One eligible route: the simulator's estimate at release. Same columns as route_candidate."""
    template_id: str
    est_hours: float
    naive_hours: float
    est_cost_usd: float = Field(gt=0)
    fee_total_usd: float
    fx_bps: float
    hop_count: int
    intermediaries: int
    est_nonbusiness_days: int
    calendar_conflict: int = Field(ge=0, le=1)
    cutoff_slack_hours: float
    max_risk: int
    stp_rate_nominal: float


class ScoreRequest(BaseModel):
    payment: ScorePayment
    routes: List[RouteFeatures] = Field(min_length=1, description="The eligible routes of the corridor")

    model_config = {"json_schema_extra": {"examples": [{
        "payment": {"corridor_id": "USD-INR", "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580,
                    "priority": 15, "release_hour_et": 9.02, "release_weekday": 3, "day_of_month": 1, "has_deadline": 0},
        "routes": [{"template_id": "EXP", "est_hours": 20.5, "naive_hours": 5.5, "est_cost_usd": 49.0,
                    "fee_total_usd": 60.0, "fx_bps": 38.0, "hop_count": 4, "intermediaries": 2,
                    "est_nonbusiness_days": 0, "calendar_conflict": 0, "cutoff_slack_hours": 6.98, "max_risk": 2,
                    "stp_rate_nominal": 0.972}]}]}}


class RouteScore(BaseModel):
    template_id: str
    model_score: float = Field(description="Ranker score; higher is better, only comparable within one payment")
    predicted_hours: float = Field(description="Time to credit: simulator estimate x learned correction")
    predicted_cost_usd: float
    heuristic_score: float = Field(description="MVP 1 v1 formula score, for comparison; higher is better")


class ScoreResponse(BaseModel):
    model_version: str
    scores: List[RouteScore] = Field(description="One per requested route, in the order requested")


class HealthResponse(BaseModel):
    status: str
    model_version: str
    trained_until: Optional[str] = Field(description="Models saw value dates before this; null = all history")
