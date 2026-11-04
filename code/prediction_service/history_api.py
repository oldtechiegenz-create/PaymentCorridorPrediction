"""History endpoints: the data behind the Historical Cases tab and the similar-cases panel.

All read-only GETs under /v1/history. Unknown corridors return 404, bad parameters 422.
"""
from datetime import date
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request

from .features import TEMPLATE_IDS
from .history_repository import LOW_SAMPLE_THRESHOLD, CorridorNotFound, HistoryRepository
from .history_schemas import (CasePage, Category, CorridorList, FxResponse, IncidentPage, Outcome,
                              RoutePerformanceResponse, SimilarCasesResponse)

router = APIRouter(prefix="/v1/history", tags=["history"])
TemplateId = Literal[tuple(TEMPLATE_IDS)]


def repo(request: Request) -> HistoryRepository:
    return request.app.state.history


def corridor_or_404(corridor_id: str = Path(description="Corridor, e.g. USD-JPY", examples=["USD-JPY"]),
                    r: HistoryRepository = Depends(repo)) -> dict:
    try:
        return r.corridor(corridor_id)
    except CorridorNotFound:
        raise HTTPException(status_code=404, detail=f"Unknown corridor {corridor_id!r}; see /v1/history/corridors")


@router.get("/corridors", response_model=CorridorList, summary="Corridors with case counts and date range")
def list_corridors(r: HistoryRepository = Depends(repo)):
    return {"window": r.window(), "corridors": r.corridors()}


@router.get("/corridors/{corridor_id}/route-performance", response_model=RoutePerformanceResponse,
            summary="Per-route time to credit, cost and STP over the last 18 months of data")
def route_performance(c: dict = Depends(corridor_or_404), r: HistoryRepository = Depends(repo)):
    return {"corridor_id": c["corridor_id"], "window": r.window(), "low_sample_threshold": LOW_SAMPLE_THRESHOLD,
            "routes": r.route_performance(c["corridor_id"])}


@router.get("/corridors/{corridor_id}/fx", response_model=FxResponse,
            summary="Daily FX mid rate and annualised volatility")
def fx(days: int = Query(90, ge=7, le=365, description="Days of data, counted back from the newest rate"),
       c: dict = Depends(corridor_or_404), r: HistoryRepository = Depends(repo)):
    return {"corridor_id": c["corridor_id"], "currency_code": c["currency_code"],
            "pegged_to_usd": c["pegged_to_usd"], "days": days, **r.fx(c["corridor_id"], days)}


@router.get("/corridors/{corridor_id}/incidents", response_model=IncidentPage,
            summary="Ops-annotated incidents, newest first")
def incidents(category: Optional[Category] = Query(None, description="Cause category; omit for all"),
              limit: int = Query(20, ge=1, le=200), offset: int = Query(0, ge=0),
              c: dict = Depends(corridor_or_404), r: HistoryRepository = Depends(repo)):
    return {"corridor_id": c["corridor_id"], "category": category, "limit": limit, "offset": offset,
            **r.incidents(c["corridor_id"], category, limit, offset)}


@router.get("/corridors/{corridor_id}/cases", response_model=CasePage, summary="All cases, newest first")
def cases(route: Optional[TemplateId] = Query(None, description="Route template, e.g. EXP; omit for all"),
          outcome: Optional[Outcome] = Query(None),
          limit: int = Query(40, ge=1, le=200), offset: int = Query(0, ge=0),
          c: dict = Depends(corridor_or_404), r: HistoryRepository = Depends(repo)):
    return {"corridor_id": c["corridor_id"], "route": route, "outcome": outcome, "limit": limit, "offset": offset,
            **r.cases(c["corridor_id"], route, outcome, limit, offset)}


@router.get("/corridors/{corridor_id}/similar-cases", response_model=SimilarCasesResponse,
            summary="Past payments most like a new request (Route tab)")
def similar_cases(amount_usd: float = Query(gt=0), client_type: Literal["Retail", "FI"] = Query(),
                  value_date: date = Query(description="The new payment's value date; matches the release weekday"),
                  template_id: Optional[TemplateId] = Query(None, description="Recommended route, for its 18-month median"),
                  limit: int = Query(6, ge=1, le=20),
                  c: dict = Depends(corridor_or_404), r: HistoryRepository = Depends(repo)):
    return {"corridor_id": c["corridor_id"],
            **r.similar_cases(c["corridor_id"], amount_usd, client_type, value_date.isoformat(), template_id, limit)}
