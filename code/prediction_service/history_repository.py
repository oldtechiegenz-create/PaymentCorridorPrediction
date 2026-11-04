"""Read access to the history database for the Historical Cases tab and the similar-cases panel.

The SQL lives in Data/corridoriq_history/historical_cases.sql as named queries over the views in
schema.sql; this module only loads them, runs them on a read-only connection and shapes the rows.
"""
import math
import re
import sqlite3
import statistics
from contextlib import closing
from pathlib import Path
from typing import Dict, List, Optional

from .features import DB_PATH

SQL_PATH = DB_PATH.parent / "historical_cases.sql"
LOW_SAMPLE_THRESHOLD = 50          # mirrors v_route_performance_18m
REQUIRED_VIEWS = ("v_history_window", "v_corridor_overview", "v_route_performance_18m", "v_fx_corridor",
                  "v_incident", "v_case")
_NAME = re.compile(r"^--\s*name:\s*(\w+)\s*$", re.M)


class CorridorNotFound(LookupError):
    pass


def load_queries(sql_path: Path) -> Dict[str, str]:
    """{name: sql} from a file of '-- name: <name>' sections."""
    text = sql_path.read_text()
    marks = list(_NAME.finditer(text))
    queries = {}
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        queries[m.group(1)] = text[m.end():end].strip().rstrip(";")
    return queries


def annualised_volatility_pct(rates: List[float], periods_per_year: int = 365) -> Optional[float]:
    """Sample stdev of daily log returns, annualised; None when there are too few points."""
    if len(rates) < 3:
        return None
    rets = [math.log(b / a) for a, b in zip(rates, rates[1:])]
    return round(statistics.stdev(rets) * math.sqrt(periods_per_year) * 100, 2)


class HistoryRepository:
    def __init__(self, db_path: Path = DB_PATH, sql_path: Path = SQL_PATH):
        self.db_path = Path(db_path)
        self.queries = load_queries(Path(sql_path))
        with closing(self._connect()) as conn:
            have = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type = 'view'")}
        missing = [v for v in REQUIRED_VIEWS if v not in have]
        if missing:
            raise RuntimeError(f"{self.db_path} lacks views {', '.join(missing)}; "
                               "run python3 code/scripts/apply_views.py")
        # The service only reads the database, so the corridor list and window are loaded once:
        # every corridor endpoint checks the corridor exists, and v_corridor_overview counts all payments.
        self._window = self._one("history_window")
        self._corridors = {c["corridor_id"]: c for c in self._rows("corridors")}

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path.resolve().as_uri() + "?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        return conn

    def _rows(self, name: str, **params) -> List[dict]:
        with closing(self._connect()) as conn:
            return [dict(r) for r in conn.execute(self.queries[name], params)]

    def _one(self, name: str, **params) -> Optional[dict]:
        rows = self._rows(name, **params)
        return rows[0] if rows else None

    # ------------------------------------------------------------------ tab panels
    def window(self) -> dict:
        return dict(self._window)

    def corridors(self) -> List[dict]:
        return [dict(c) for c in self._corridors.values()]

    def corridor(self, corridor_id: str) -> dict:
        if corridor_id not in self._corridors:
            raise CorridorNotFound(corridor_id)
        return dict(self._corridors[corridor_id])

    def route_performance(self, corridor_id: str) -> List[dict]:
        return self._rows("route_performance", corridor=corridor_id)

    def fx(self, corridor_id: str, days: int) -> dict:
        points = self._rows("fx_series", corridor=corridor_id, days=days)
        return {"points": points, "annualised_volatility_pct": annualised_volatility_pct([p["mid_rate"] for p in points])}

    def incidents(self, corridor_id: str, category: Optional[str], limit: int, offset: int) -> dict:
        p = dict(corridor=corridor_id, category=category)
        return {"total": self._one("incident_count", **p)["total"],
                "categories": self._rows("incident_categories", corridor=corridor_id),
                "items": self._rows("incidents", **p, limit=limit, offset=offset)}

    def cases(self, corridor_id: str, route: Optional[str], outcome: Optional[str], limit: int, offset: int) -> dict:
        p = dict(corridor=corridor_id, route=route, outcome=outcome)
        return {"total": self._one("case_count", **p)["total"],
                "items": self._rows("cases", **p, limit=limit, offset=offset)}

    def similar_cases(self, corridor_id: str, amount_usd: float, client_type: str, value_date: str,
                      template_id: Optional[str], limit: int) -> dict:
        summary = self._one("route_summary", corridor=corridor_id, template=template_id) if template_id else None
        cases = self._rows("similar_cases", corridor=corridor_id, amount=amount_usd, client_type=client_type,
                           value_date=value_date, limit=limit)
        return {"route_summary": summary, "cases": cases}
