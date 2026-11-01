"""Request and response models for the prediction API."""
from typing import List, Literal, Optional

from pydantic import AwareDatetime, BaseModel, Field

from .features import CORRIDOR_IDS


class PaymentRequest(BaseModel):
    corridor_id: Literal[tuple(CORRIDOR_IDS)]
    bene_bank_id: str = Field(description="Beneficiary bank, e.g. IN_B2; must belong to the corridor")
    client_type: Literal["Retail", "FI"]
    amount_usd: float = Field(gt=0)
    priority: int = Field(ge=0, le=100, description="0 = cheapest route preferred, 100 = fastest")
    released_at: AwareDatetime = Field(description="Release time with time zone, e.g. 2026-04-01T13:01:00Z")
    has_deadline: bool = False

    model_config = {"json_schema_extra": {"examples": [{
        "corridor_id": "USD-INR", "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580,
        "priority": 15, "released_at": "2026-04-01T13:01:00Z", "has_deadline": False}]}}


class RouteResult(BaseModel):
    rank: Optional[int] = Field(description="1 = recommended; null when the route is not eligible")
    template_id: str
    name: str
    via: str
    eligible: bool
    ineligible_reasons: List[str]
    est_hours: float = Field(description="Simulator estimate, calendar-aware")
    naive_hours: float
    est_cost_usd: float = Field(description="Quoted fees + FX margin")
    fee_total_usd: float
    fx_bps: float
    hop_count: int
    intermediaries: int
    est_nonbusiness_days: int
    calendar_conflict: bool
    cutoff_slack_hours: float
    max_risk: int
    stp_rate_nominal: float
    predicted_hours: Optional[float] = Field(description="Model's time to credit: simulator estimate x learned correction")
    predicted_cost_usd: Optional[float]
    model_score: Optional[float] = Field(description="Ranker score; only comparable within one payment")
    heuristic_score: Optional[float] = Field(description="MVP 1 v1 formula score, for comparison")


class RankResponse(BaseModel):
    model_version: str
    recommended_template_id: str
    routes: List[RouteResult]


class HealthResponse(BaseModel):
    status: str
    model_version: str
    trained_until: Optional[str] = Field(description="Models saw value dates before this; null = all history")
    calendar_years: List[int]
