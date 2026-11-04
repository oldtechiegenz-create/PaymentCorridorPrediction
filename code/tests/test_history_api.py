"""History API: shapes, totals checked against the raw tables, paging, filters and errors.

    cd code && python3 -m pytest tests -q
"""
import pytest

from apply_views import view_statements
from prediction_service.history_repository import SQL_PATH, load_queries

BASE = "/v1/history/corridors"


def scalar(db, sql, *args):
    return db.execute(sql, args).fetchone()[0]


# ------------------------------------------------------------------ SQL sources
def test_named_queries_and_views_parse():
    names = set(load_queries(SQL_PATH))
    assert {"history_window", "corridors", "route_performance", "fx_series", "incident_categories", "incident_count",
            "incidents", "case_count", "cases", "similar_cases", "route_summary"} <= names
    views = [n for n, _ in view_statements((SQL_PATH.parent / "schema.sql").read_text())]
    assert {"v_history_window", "v_corridor_overview", "v_route_performance_18m", "v_fx_corridor"} <= set(views)
    assert views.index("v_history_window") < views.index("v_route_performance_18m")   # dependency order


# ------------------------------------------------------------------ corridors
def test_corridors(client, db):
    d = client.get(BASE).json()
    assert d["window"]["as_of"] == scalar(db, "SELECT MAX(value_date) FROM payment")
    assert d["window"]["window_start"] == "2025-04-01"
    assert [c["corridor_id"] for c in d["corridors"]] == ["USD-JPY", "USD-INR", "USD-AED", "USD-SAR", "USD-EUR"]
    assert sum(c["cases"] for c in d["corridors"]) == scalar(db, "SELECT COUNT(*) FROM payment")
    assert sum(c["incidents"] for c in d["corridors"]) == scalar(db, "SELECT COUNT(*) FROM incident")
    pegged = {c["currency_code"]: c["pegged_to_usd"] for c in d["corridors"]}
    assert pegged == {"JPY": False, "INR": False, "AED": True, "SAR": True, "EUR": False}


# ------------------------------------------------------------------ route performance
@pytest.mark.parametrize("corridor", ["USD-JPY", "USD-AED"])
def test_route_performance_matches_raw_rows(client, db, corridor):
    d = client.get(f"{BASE}/{corridor}/route-performance").json()
    since = d["window"]["window_start"]
    offered = {r[0] for r in db.execute("SELECT template_id FROM corridor_route WHERE corridor_id = ?", (corridor,))}
    assert {r["template_id"] for r in d["routes"]} == offered
    assert sum(r["cases"] for r in d["routes"]) == scalar(
        db, "SELECT COUNT(*) FROM payment WHERE corridor_id = ? AND value_date >= ?", corridor, since)
    for r in d["routes"]:
        assert r["low_sample"] == (r["cases"] < d["low_sample_threshold"])
        hours = sorted(h for (h,) in db.execute(
            "SELECT hours_to_credit FROM payment WHERE corridor_id = ? AND template_id = ? AND value_date >= ? "
            "AND hours_to_credit IS NOT NULL", (corridor, r["template_id"], since)))
        # SQLite rounds halves away from zero, Python's round() to even: compare unrounded within 0.05 h
        assert r["median_hours"] == pytest.approx(hours[(len(hours) + 1) // 2 - 1], abs=0.0501)
    medians = [r["median_hours"] for r in d["routes"] if r["cases"]]
    assert medians == sorted(medians)


def test_low_sample_route_flagged(client):
    routes = {r["template_id"]: r for r in client.get(f"{BASE}/USD-AED/route-performance").json()["routes"]}
    assert routes["LDN"]["low_sample"] and routes["LDN"]["cases"] < 50
    assert not routes["PAY"]["low_sample"]


# ------------------------------------------------------------------ FX
def test_fx(client, db):
    jpy = client.get(f"{BASE}/USD-JPY/fx").json()
    dates = [p["rate_date"] for p in jpy["points"]]
    assert len(dates) == 90 and dates == sorted(dates)
    assert dates[-1] == scalar(db, "SELECT MAX(rate_date) FROM fx_rate_daily WHERE currency_code = 'JPY'")
    aed = client.get(f"{BASE}/USD-AED/fx?days=30").json()
    assert aed["pegged_to_usd"] and len(aed["points"]) == 30
    assert aed["annualised_volatility_pct"] < 1 < jpy["annualised_volatility_pct"]


# ------------------------------------------------------------------ incidents
def test_incidents_totals_and_paging(client, db):
    raw = scalar(db, "SELECT COUNT(*) FROM incident i JOIN payment p USING (payment_id) WHERE p.corridor_id = 'USD-INR'")
    p1 = client.get(f"{BASE}/USD-INR/incidents?limit=20").json()
    p2 = client.get(f"{BASE}/USD-INR/incidents?limit=20&offset=20").json()
    assert p1["total"] == raw == sum(c["incidents"] for c in p1["categories"])
    assert len(p1["items"]) == 20
    assert not {x["incident_id"] for x in p1["items"]} & {x["incident_id"] for x in p2["items"]}
    stamps = [x["released_at_utc"] for x in p1["items"] + p2["items"]]
    assert stamps == sorted(stamps, reverse=True)


def test_incidents_category_filter(client):
    d = client.get(f"{BASE}/USD-JPY/incidents?category=batch&limit=200").json()
    counts = {c["category"]: c["incidents"] for c in d["categories"]}
    assert d["total"] == counts["batch"] == len(d["items"])
    assert {x["category"] for x in d["items"]} == {"batch"}


# ------------------------------------------------------------------ cases
def test_cases_totals_filters_and_order(client, db):
    d = client.get(f"{BASE}/USD-EUR/cases?limit=40").json()
    assert d["total"] == scalar(db, "SELECT COUNT(*) FROM payment WHERE corridor_id = 'USD-EUR'")
    assert len(d["items"]) == 40
    stamps = [x["released_at_utc"] for x in d["items"]]
    assert stamps == sorted(stamps, reverse=True)
    f = client.get(f"{BASE}/USD-EUR/cases?route=LDN&outcome=Returned&limit=200").json()
    assert f["total"] == scalar(db, "SELECT COUNT(*) FROM payment WHERE corridor_id = 'USD-EUR' "
                                    "AND template_id = 'LDN' AND outcome = 'Returned'")
    assert all(x["template_id"] == "LDN" and x["hours_to_credit"] is None for x in f["items"])


# ------------------------------------------------------------------ similar cases
def test_similar_cases_finds_seeded_incident(client):
    # The India 2nd-Saturday seed: Express, $7,000, retail, released Friday 11 Sep 2026.
    d = client.get(f"{BASE}/USD-INR/similar-cases",
                   params={"amount_usd": 7000, "client_type": "Retail", "value_date": "2026-09-11",
                           "template_id": "EXP"}).json()
    assert len(d["cases"]) == 6
    top = d["cases"][0]
    assert (top["value_date"], top["template_id"], top["amount_usd"]) == ("2026-09-11", "EXP", 7000.0)
    assert "2nd Saturday" in top["ops_note"]
    assert d["route_summary"]["template_id"] == "EXP" and d["route_summary"]["cases"] > 50


def test_similar_cases_without_route(client):
    d = client.get(f"{BASE}/USD-SAR/similar-cases",
                   params={"amount_usd": 30000, "client_type": "FI", "value_date": "2026-10-05"}).json()
    assert d["route_summary"] is None and d["cases"]


# ------------------------------------------------------------------ errors, CORS, portal
@pytest.mark.parametrize("url,status", [
    (f"{BASE}/USD-XXX/cases", 404),
    (f"{BASE}/USD-JPY/cases?route=NOPE", 422),
    (f"{BASE}/USD-JPY/cases?limit=500", 422),
    (f"{BASE}/USD-JPY/incidents?category=weather", 422),
    (f"{BASE}/USD-JPY/fx?days=3", 422),
    (f"{BASE}/USD-JPY/similar-cases?amount_usd=0&client_type=FI&value_date=2026-10-05", 422),
])
def test_errors(client, url, status):
    assert client.get(url).status_code == status


def test_cors_allows_local_pages_only(client):
    for origin in ("null", "http://localhost:5500", "http://127.0.0.1:8000"):
        assert client.get(BASE, headers={"Origin": origin}).headers.get("access-control-allow-origin") == origin
    assert "access-control-allow-origin" not in client.get(BASE, headers={"Origin": "https://example.com"}).headers


def test_portal_served(client):
    r = client.get("/")
    assert r.status_code == 200 and "<title>CorridorIQ" in r.text and "/v1/history/corridors" in r.text
