"""Scoring service: health, scores, agreement with the offline model, and bad input.

The service holds no banking logic: the Java backend sends it the features of each eligible route. These tests
send it stored candidate rows from the training view, so they need no backend.

    cd code && python3 -m pytest tests -q
"""
import json
import sqlite3
from contextlib import closing

import lightgbm as lgb
import pandas as pd
import pytest

from prediction_service import features as F
from prediction_service.metrics import top1
from prediction_service.train import MODEL_DIR

PAYMENT_COLUMNS = F.PAYMENT_COLUMNS
ROUTE_COLUMNS = F.ROUTE_COLUMNS


def stored_payments(where: str, limit: int):
    """(payment, routes, frame) for payments in the training view: the payload the backend would send."""
    with closing(sqlite3.connect(F.DB_PATH.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        df = pd.read_sql(f"SELECT * FROM v_ranking_training WHERE eligible = 1 AND {where} "
                         f"AND payment_id IN (SELECT payment_id FROM v_ranking_training WHERE {where} "
                         f"GROUP BY payment_id ORDER BY payment_id LIMIT {limit}) ORDER BY payment_id, template_id", db)
    out = []
    for pid, g in df.groupby("payment_id"):
        first = g.iloc[0]
        payment = {c: first[c].item() if hasattr(first[c], "item") else first[c] for c in PAYMENT_COLUMNS}
        payment["has_deadline"] = int(payment["has_deadline"])
        routes = [{c: (r[c].item() if hasattr(r[c], "item") else r[c]) for c in ROUTE_COLUMNS} for _, r in g.iterrows()]
        out.append((pid, payment, routes, g))
    return out


SAMPLE = {
    "payment": {"corridor_id": "USD-INR", "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580,
                "priority": 15, "release_hour_et": 9.02, "release_weekday": 3, "day_of_month": 1, "has_deadline": 0},
    "routes": [
        {"template_id": "EXP", "est_hours": 20.5, "naive_hours": 5.5, "est_cost_usd": 49.0, "fee_total_usd": 60.0,
         "fx_bps": 38.0, "hop_count": 4, "intermediaries": 2, "est_nonbusiness_days": 0, "calendar_conflict": 0,
         "cutoff_slack_hours": 6.98, "max_risk": 2, "stp_rate_nominal": 0.972},
        {"template_id": "ECO", "est_hours": 120.0, "naive_hours": 90.5, "est_cost_usd": 10.0, "fee_total_usd": 4.0,
         "fx_bps": 10.0, "hop_count": 5, "intermediaries": 3, "est_nonbusiness_days": 2, "calendar_conflict": 1,
         "cutoff_slack_hours": 6.98, "max_risk": 2, "stp_rate_nominal": 0.962},
    ],
}


def post(client, payload):
    return client.post("/v1/routes/score", json=payload)


def test_health_reports_the_saved_model(client):
    meta = json.loads((MODEL_DIR / "metadata.json").read_text())
    d = client.get("/health").json()
    assert d["status"] == "ok"
    assert d["model_version"] == meta["model_version"] and d["trained_until"] == meta["train_until"]


def test_scores_come_back_one_per_route_in_the_order_sent(client):
    d = post(client, SAMPLE).json()
    assert [s["template_id"] for s in d["scores"]] == ["EXP", "ECO"]
    assert d["model_version"] == json.loads((MODEL_DIR / "metadata.json").read_text())["model_version"]
    for s in d["scores"]:
        assert s["predicted_hours"] > 0 and s["predicted_cost_usd"] > 0
        assert set(s) == {"template_id", "model_score", "predicted_hours", "predicted_cost_usd", "heuristic_score"}
    reversed_ = post(client, {**SAMPLE, "routes": SAMPLE["routes"][::-1]}).json()["scores"]
    by_template = {s["template_id"]: s for s in d["scores"]}
    for s in reversed_:   # a route's score does not depend on where it sits in the request
        assert s == by_template[s["template_id"]]


def test_the_heuristic_prefers_the_cheap_route_for_a_cost_minded_payment_and_the_fast_one_for_a_rushed_one(client):
    cheap = {s["template_id"]: s for s in post(client, {**SAMPLE, "payment": {**SAMPLE["payment"], "priority": 0}}).json()["scores"]}
    rushed = {s["template_id"]: s for s in post(client, {**SAMPLE, "payment": {**SAMPLE["payment"], "priority": 100}}).json()["scores"]}
    assert cheap["ECO"]["heuristic_score"] > cheap["EXP"]["heuristic_score"]
    assert rushed["EXP"]["heuristic_score"] > rushed["ECO"]["heuristic_score"]


def test_scores_match_the_offline_models_on_stored_candidates(client):
    """Training/serving agreement: the HTTP path gives the same numbers as the model files on the training frame."""
    meta = json.loads((MODEL_DIR / "metadata.json").read_text())
    ranker = lgb.Booster(model_file=str(MODEL_DIR / "ranker.txt"))
    payments = stored_payments("1 = 1", 40)
    assert len(payments) == 40
    for pid, payment, routes, frame in payments:
        offline = ranker.predict(F.add_model_features(frame, meta["categories"])[F.FEATURES])
        scores = post(client, {"payment": payment, "routes": routes}).json()["scores"]
        assert [s["template_id"] for s in scores] == list(frame.template_id), pid
        for s, expected in zip(scores, offline):
            assert s["model_score"] == pytest.approx(expected, abs=1e-4), (pid, s["template_id"])


def test_the_ranking_on_held_out_payments_is_good(client):
    """Not a benchmark (train.py reports those): a guard against a broken model or a broken feature wiring."""
    meta = json.loads((MODEL_DIR / "metadata.json").read_text())
    if not meta["train_until"]:
        pytest.skip("trained on all history, so there is no hold-out")
    groups = []
    for pid, payment, routes, frame in stored_payments(f"value_date >= '{meta['train_until']}'", 300):
        if len(routes) < 2:
            continue
        scores = {s["template_id"]: s["model_score"] for s in post(client, {"payment": payment, "routes": routes}).json()["scores"]}
        groups.append((frame.relevance.values, frame.template_id.map(scores).values.astype(float)))
    assert len(groups) > 200
    assert top1(groups) > 0.85


@pytest.mark.parametrize("change,detail", [
    ({"payment": {"corridor_id": "USD-XXX"}}, "Unknown corridor_id USD-XXX"),
    ({"payment": {"bene_bank_id": "XX_B9"}}, "Unknown bene_bank_id XX_B9"),
    ({"routes": [{"template_id": "ZZZ"}]}, "Unknown template_id ZZZ"),
    ({"routes": "duplicate"}, "appears twice"),
    ({"payment": {"client_type": "Bank"}}, None),
    ({"payment": {"priority": 101}}, None),
    ({"payment": {"amount_usd": 0}}, None),
    ({"payment": {"release_weekday": 7}}, None),
    ({"payment": {"has_deadline": 2}}, None),
    ({"routes": []}, None),
])
def test_invalid_requests_are_rejected_with_422(client, change, detail):
    payload = json.loads(json.dumps(SAMPLE))
    for key, value in change.items():
        if key == "payment":
            payload["payment"].update(value)
        elif value == "duplicate":
            payload["routes"] = [payload["routes"][0], payload["routes"][0]]
        elif isinstance(value, list) and value and isinstance(value[0], dict):
            payload["routes"][0].update(value[0])
        else:
            payload[key] = value
    r = post(client, payload)
    assert r.status_code == 422
    if detail:
        assert detail in r.json()["detail"]


def test_a_missing_route_field_is_rejected(client):
    payload = json.loads(json.dumps(SAMPLE))
    del payload["routes"][0]["est_hours"]
    assert post(client, payload).status_code == 422


def test_the_old_ranking_endpoint_is_gone_from_the_python_service(client):
    # Ranking now happens in the Java backend; the Python service only scores.
    assert client.post("/v1/routes/rank", json=SAMPLE["payment"]).status_code == 404


def test_saved_categories_match_the_database():
    meta = json.loads((MODEL_DIR / "metadata.json").read_text())
    with closing(sqlite3.connect(F.DB_PATH.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        assert meta["categories"] == F.load_categories(db)
    assert meta["categories"]["template_id"] == sorted(meta["categories"]["template_id"])

