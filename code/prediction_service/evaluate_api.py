"""Send the hold-out test payments to the running API and score its answers against what really happened.

    cd code
    python3 -m uvicorn prediction_service.api:app --port 8000     # in another terminal
    python3 -m prediction_service.evaluate_api                     # all test payments
    python3 -m prediction_service.evaluate_api --limit 500         # quicker

It also checks that the features the API computes match the stored history (training/serving parity).
"""
import argparse
import json
import sqlite3
import time
from contextlib import closing
from pathlib import Path

import numpy as np
import pandas as pd

from .client import PredictionClient
from .features import DB_PATH
from .metrics import ndcg_at_k, top1

PARITY_COLS = ["est_hours", "naive_hours", "est_cost_usd", "fee_total_usd", "est_nonbusiness_days",
               "calendar_conflict", "cutoff_slack_hours"]


def load_test_set(db: Path, test_from: str, limit: int):
    with closing(sqlite3.connect(db)) as conn:
        pays = pd.read_sql(
            "SELECT payment_id, corridor_id, bene_bank_id, client_type, amount_usd, priority, released_at_utc, "
            "deadline_date IS NOT NULL AS has_deadline FROM payment WHERE value_date >= ? ORDER BY payment_id"
            + (" LIMIT ?" if limit else ""), conn, params=[test_from, limit] if limit else [test_from])
        ids = ",".join(map(str, pays.payment_id))
        cands = pd.read_sql(
            f"SELECT payment_id, template_id, eligible, relevance, was_chosen, realized_hours, realized_cost_usd, "
            f"realized_outcome, {', '.join(PARITY_COLS)} FROM route_candidate WHERE payment_id IN ({ids})", conn)
    return pays, cands


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    ap.add_argument("--db", type=Path, default=DB_PATH)
    ap.add_argument("--test-from", help="first value date to test; default: the model's train_until")
    ap.add_argument("--limit", type=int, default=0, help="number of test payments, 0 = all")
    a = ap.parse_args()

    client = PredictionClient(a.url)
    health = client.health()
    test_from = a.test_from or health["trained_until"]
    if not test_from:
        raise SystemExit("The model was trained on all history, so there is no hold-out; pass --test-from.")
    print(f"API {a.url} · model {health['model_version']} · trained before {health['trained_until']}")

    pays, cands = load_test_set(a.db, test_from, a.limit)
    print(f"Calling the API for {len(pays):,} payments from {test_from} ...")
    rows, latencies, example = [], [], None
    for p in pays.itertuples(index=False):
        t = time.perf_counter()
        resp = client.rank_routes(p.corridor_id, p.bene_bank_id, p.client_type, p.amount_usd, int(p.priority),
                                  p.released_at_utc, bool(p.has_deadline))
        latencies.append((time.perf_counter() - t) * 1000)
        example = example or resp
        for r in resp["routes"]:
            rows.append({"payment_id": p.payment_id, **{f"api_{k}": v for k, v in r.items()
                                                       if k not in ("name", "via", "ineligible_reasons")}})

    df = cands.merge(pd.DataFrame(rows), left_on=["payment_id", "template_id"],
                     right_on=["payment_id", "api_template_id"], how="outer", indicator=True)

    # --- training/serving parity: the API must rebuild the same features the model was trained on
    print("\nFeature parity with stored history")
    print(f"  routes missing on either side : {(df._merge != 'both').sum()}")
    df = df[df._merge == "both"]
    print(f"  eligibility mismatches        : {(df.eligible.astype(bool) != df.api_eligible).sum()}")
    for c in PARITY_COLS:
        bad = (np.abs(df[c].astype(float) - df[f"api_{c}"].astype(float)) > 0.011).sum()
        print(f"  {c:30}: {bad} mismatches")

    el = df[df.eligible == 1]
    print(f"\n{'':28}{'NDCG@3':>8}{'Picks best':>12}")
    for name, col in [("Ops choice (history)", "was_chosen"), ("Simulator heuristic", "api_heuristic_score"),
                      ("LightGBM via API", "api_model_score")]:
        g = [(x.relevance.values, x[col].values.astype(float)) for _, x in el.groupby("payment_id") if len(x) > 1]
        print(f"{name:28}{ndcg_at_k(g):8.3f}{top1(g) * 100:11.1f}%")

    ok = el[(el.realized_outcome != "Returned") & (el.realized_hours < 72)]
    print(f"\nTime to credit MAE (routes under 72 h): simulator {np.mean(np.abs(ok.est_hours - ok.realized_hours)):.1f} h"
          f" · API {np.mean(np.abs(ok.api_predicted_hours - ok.realized_hours)):.1f} h")
    okc = el[el.realized_outcome != "Returned"]
    print(f"All-in cost MAPE: API {np.mean(np.abs(okc.api_predicted_cost_usd - okc.realized_cost_usd) / okc.realized_cost_usd) * 100:.1f}%")
    print(f"Latency per request: p50 {np.percentile(latencies, 50):.1f} ms · p95 {np.percentile(latencies, 95):.1f} ms")

    print("\nExample response (first test payment):")
    print(json.dumps(example, indent=2)[:2500])


if __name__ == "__main__":
    main()
