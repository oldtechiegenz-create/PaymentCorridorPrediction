"""Prediction service: health, ranking, feature parity with stored history, and errors.

    cd code && python3 -m pytest tests -q
"""
import json
import sqlite3
from contextlib import closing

import pytest

from apply_views import view_statements
from prediction_service import features as F
from prediction_service.train import MODEL_DIR

PAYMENT = {"corridor_id": "USD-INR", "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580,
           "priority": 15, "released_at": "2026-04-01T13:01:00Z", "has_deadline": False}
PARITY_COLS = ["est_hours", "naive_hours", "est_cost_usd", "fee_total_usd", "est_nonbusiness_days",
               "calendar_conflict", "cutoff_slack_hours"]


def test_health_reports_the_saved_model(client):
    meta = json.loads((MODEL_DIR / "metadata.json").read_text())
    d = client.get("/health").json()
    assert d["status"] == "ok"
    assert d["model_version"] == meta["model_version"] and d["trained_until"] == meta["train_until"]
    assert d["calendar_years"][0] <= 2026 <= d["calendar_years"][1]


def test_rank_orders_eligible_routes_then_ineligible(client):
    d = client.post("/v1/routes/rank", json=PAYMENT).json()
    routes = d["routes"]
    assert {r["template_id"] for r in routes} == set(F.R.CORRIDORS["USD-INR"][6])
    eligible = [r for r in routes if r["eligible"]]
    assert [r["rank"] for r in eligible] == list(range(1, len(eligible) + 1))
    assert routes[:len(eligible)] == eligible                       # eligible first
    assert d["recommended_template_id"] == eligible[0]["template_id"]
    scores = [r["model_score"] for r in eligible]
    assert scores == sorted(scores, reverse=True)
    for r in eligible:
        assert r["predicted_hours"] > 0 and r["predicted_cost_usd"] > 0 and not r["ineligible_reasons"]
    for r in routes[len(eligible):]:
        assert r["rank"] is None and r["model_score"] is None and r["ineligible_reasons"]


def test_large_fi_payment_excludes_capped_and_retail_routes(client):
    d = client.post("/v1/routes/rank", json={**PAYMENT, "client_type": "FI", "amount_usd": 2_000_000}).json()
    excluded = {r["template_id"]: r["ineligible_reasons"] for r in d["routes"] if not r["eligible"]}
    assert excluded, "a $2m FI payment should rule out capped or retail-only routes"
    assert all(any("cap" in x or "retail" in x for x in reasons) for reasons in excluded.values())


def test_features_match_stored_history(client):
    """Training/serving parity: the service rebuilds the features the models were trained on."""
    with closing(sqlite3.connect(F.DB_PATH.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        pays = db.execute("SELECT payment_id, corridor_id, bene_bank_id, client_type, amount_usd, priority, "
                          "released_at_utc, deadline_date IS NOT NULL AS has_deadline FROM payment "
                          "WHERE payment_id % 997 = 0 ORDER BY payment_id LIMIT 30").fetchall()
        assert len(pays) == 30
        for p in pays:
            stored = {r["template_id"]: r for r in db.execute(
                f"SELECT template_id, eligible, {', '.join(PARITY_COLS)} FROM route_candidate WHERE payment_id = ?",
                (p["payment_id"],))}
            d = client.post("/v1/routes/rank", json={
                "corridor_id": p["corridor_id"], "bene_bank_id": p["bene_bank_id"], "client_type": p["client_type"],
                "amount_usd": p["amount_usd"], "priority": p["priority"], "released_at": p["released_at_utc"],
                "has_deadline": bool(p["has_deadline"])}).json()
            assert {r["template_id"] for r in d["routes"]} == set(stored)
            for r in d["routes"]:
                s = stored[r["template_id"]]
                assert r["eligible"] == bool(s["eligible"]), (p["payment_id"], r["template_id"])
                for c in PARITY_COLS:
                    assert float(r[c]) == pytest.approx(float(s[c]), abs=0.011), (p["payment_id"], r["template_id"], c)


@pytest.mark.parametrize("change,detail", [
    ({"corridor_id": "USD-XXX"}, None),
    ({"bene_bank_id": "JP_B1"}, "is not a beneficiary bank on USD-INR"),
    ({"released_at": "2026-04-01T13:01:00"}, None),                  # no time zone
    ({"released_at": "2031-04-01T13:01:00Z"}, "Banking calendar only covers"),
    ({"priority": 101}, None),
    ({"amount_usd": 0}, None),
    ({"client_type": "Bank"}, None),
])
def test_invalid_payments(client, change, detail):
    r = client.post("/v1/routes/rank", json={**PAYMENT, **change})
    assert r.status_code == 422
    if detail:
        assert detail in r.json()["detail"]


def test_history_views_are_listed_in_dependency_order():
    views = [n for n, _ in view_statements((F.DB_PATH.parent / "schema.sql").read_text())]
    assert {"v_history_window", "v_corridor_overview", "v_route_performance_18m", "v_fx_corridor",
            "v_incident", "v_case", "v_ranking_training"} <= set(views)
    assert views.index("v_history_window") < views.index("v_route_performance_18m")
