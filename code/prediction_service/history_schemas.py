"""Response models for the history API (Historical Cases tab and similar cases)."""
from typing import List, Literal, Optional

from pydantic import BaseModel, Field

Category = Literal["calendar", "cutoff", "batch", "repair", "return"]
Outcome = Literal["Credited", "Repaired", "Returned"]


class HistoryWindow(BaseModel):
    as_of: str = Field(description="Newest value date in the history")
    window_start: str = Field(description="First value date of the 18-month reporting window")


class CorridorSummary(BaseModel):
    corridor_id: str
    dest_country: str
    country_name: str
    currency_code: str
    currency_name: str
    pegged_to_usd: bool
    local_rail: str
    cases: int
    first_value_date: Optional[str]
    last_value_date: Optional[str]
    incidents: int


class CorridorList(BaseModel):
    window: HistoryWindow
    corridors: List[CorridorSummary]


class RoutePerformance(BaseModel):
    template_id: str
    route_name: str
    via: str
    cases: int = Field(description="Payments on this route in the window")
    median_hours: Optional[float] = Field(description="Median time to credit; null with no credited cases")
    p90_hours: Optional[float]
    avg_cost_usd: Optional[float]
    cost_bps: Optional[float] = Field(description="All-in cost as a share of value sent (value-weighted)")
    stp_pct: Optional[float] = Field(description="Share with no manual intervention anywhere on the route")
    returned_pct: Optional[float]
    low_sample: bool = Field(description="Fewer cases than low_sample_threshold: treat as indicative")


class RoutePerformanceResponse(BaseModel):
    corridor_id: str
    window: HistoryWindow
    low_sample_threshold: int
    routes: List[RoutePerformance]


class FxPoint(BaseModel):
    rate_date: str
    mid_rate: float


class FxResponse(BaseModel):
    corridor_id: str
    currency_code: str
    pegged_to_usd: bool
    days: int
    annualised_volatility_pct: Optional[float] = Field(description="Stdev of daily log returns x sqrt(365)")
    points: List[FxPoint]


class CategoryCount(BaseModel):
    category: Category
    incidents: int


class Incident(BaseModel):
    incident_id: int
    payment_id: int
    value_date: str
    released_at_utc: str
    template_id: str
    route_name: str
    amount_usd: float
    client_type: str
    hours_to_credit: Optional[float] = Field(description="Null when the payment was returned")
    outcome: Outcome
    cause_code: str
    category: Category
    cause: str
    note: str


class IncidentPage(BaseModel):
    corridor_id: str
    category: Optional[Category]
    total: int = Field(description="Incidents matching the filter")
    limit: int
    offset: int
    categories: List[CategoryCount] = Field(description="Counts for the whole corridor, for filter chips")
    items: List[Incident]


class Case(BaseModel):
    payment_id: int
    uetr: str
    value_date: str
    released_at_utc: str
    template_id: str
    route_name: str
    amount_usd: float
    client_type: str
    charges: str
    purpose_code: str
    hours_to_credit: Optional[float] = Field(description="Null when the payment was returned")
    all_in_cost_usd: float
    cost_bps: float
    nonbusiness_days: int
    outcome: Outcome
    stp: bool
    ops_note: Optional[str]


class CasePage(BaseModel):
    corridor_id: str
    route: Optional[str]
    outcome: Optional[Outcome]
    total: int = Field(description="Cases matching the filters")
    limit: int
    offset: int
    items: List[Case]


class SimilarCase(BaseModel):
    payment_id: int
    value_date: str
    released_at_utc: str
    template_id: str
    route_name: str
    amount_usd: float
    client_type: str
    hours_to_credit: Optional[float]
    all_in_cost_usd: float
    outcome: Outcome
    ops_note: Optional[str]


class RouteSummary(BaseModel):
    template_id: str
    route_name: str
    cases: int
    median_hours: Optional[float]
    p90_hours: Optional[float]
    low_sample: bool


class SimilarCasesResponse(BaseModel):
    corridor_id: str
    route_summary: Optional[RouteSummary] = Field(description="18-month stats for template_id, when given")
    cases: List[SimilarCase]
