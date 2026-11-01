"""Train the route ranker and the time/cost regressors, and save them for the API.

    cd code
    python3 -m prediction_service.train                      # train before 2026-04-01, hold out the rest
    python3 -m prediction_service.train --train-until all    # production: train on all history
"""
import argparse
import json
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

from . import features as F
from .metrics import ndcg_at_k, top1

MODEL_DIR = Path(__file__).resolve().parent / "artifacts"


def fit_models(tr: pd.DataFrame):
    ranker = lgb.LGBMRanker(objective="lambdarank", n_estimators=400, learning_rate=0.05, num_leaves=31,
                            min_child_samples=40, verbose=-1)
    ranker.fit(tr[F.FEATURES], tr.relevance, group=tr.groupby("payment_id").size().values)

    # Returned payments never credited, so they say nothing about time to credit or the normal cost.
    ok = tr[tr.realized_outcome != "Returned"]
    # Learn the correction to the simulator (log ratio), not the hours from scratch.
    ratio = np.log(ok.realized_hours.clip(lower=0.25) / ok.est_hours.clip(lower=0.25))
    time_reg = lgb.LGBMRegressor(objective="l1", n_estimators=500, learning_rate=0.05, num_leaves=63,
                                 verbose=-1).fit(ok[F.FEATURES], ratio)
    cost_reg = lgb.LGBMRegressor(n_estimators=300, learning_rate=0.05, num_leaves=31,
                                 verbose=-1).fit(ok[F.FEATURES], ok.realized_cost_usd)
    return ranker, time_reg, cost_reg


def evaluate(te: pd.DataFrame, ranker, time_reg, cost_reg):
    te = te.assign(model=ranker.predict(te[F.FEATURES]))
    te = te.assign(heur=te.groupby("payment_id", group_keys=False).apply(F.heuristic_score))
    out = {}
    for name, col in [("ops", "was_chosen"), ("heuristic", "heur"), ("model", "model")]:
        g = [(x.relevance.values, x[col].values) for _, x in te.groupby("payment_id") if len(x) > 1]
        out[name] = {"ndcg_at_3": round(ndcg_at_k(g), 4), "top1": round(top1(g), 4)}
    ok = te[te.realized_outcome != "Returned"]
    under72 = ok.realized_hours < 72
    pred_h = ok.est_hours.clip(lower=0.25) * np.exp(time_reg.predict(ok[F.FEATURES]))
    pred_c = cost_reg.predict(ok[F.FEATURES])
    out["time_mae_hours_under_72h"] = {
        "simulator": round(float(np.mean(np.abs(ok.est_hours - ok.realized_hours)[under72])), 3),
        "model": round(float(np.mean(np.abs(pred_h - ok.realized_hours)[under72])), 3),
    }
    out["cost_mape_pct"] = round(float(np.mean(np.abs(pred_c - ok.realized_cost_usd) / ok.realized_cost_usd) * 100), 3)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", type=Path, default=F.DB_PATH)
    ap.add_argument("--out", type=Path, default=MODEL_DIR)
    ap.add_argument("--train-until", default="2026-04-01",
                    help="train on value dates before this date and hold out the rest; 'all' trains on everything")
    a = ap.parse_args()

    with closing(sqlite3.connect(a.db)) as conn:
        df = pd.read_sql("SELECT * FROM v_ranking_training WHERE eligible = 1", conn)
    df = F.add_model_features(df).sort_values(["payment_id", "template_id"])

    train_until = None if a.train_until == "all" else a.train_until
    tr = df if train_until is None else df[df.value_date < train_until]
    te = None if train_until is None else df[df.value_date >= train_until]

    ranker, time_reg, cost_reg = fit_models(tr)
    holdout = evaluate(te, ranker, time_reg, cost_reg) if te is not None and len(te) else None

    a.out.mkdir(parents=True, exist_ok=True)
    ranker.booster_.save_model(str(a.out / "ranker.txt"))
    time_reg.booster_.save_model(str(a.out / "time_regressor.txt"))
    cost_reg.booster_.save_model(str(a.out / "cost_regressor.txt"))
    trained_at = datetime.now(timezone.utc)
    meta = {
        "model_version": trained_at.strftime("lgbm-%Y%m%dT%H%M%SZ"),
        "trained_at_utc": trained_at.isoformat(timespec="seconds"),
        "train_until": train_until,
        "train_payments": int(tr.payment_id.nunique()),
        "train_rows": int(len(tr)),
        "features": F.FEATURES,
        "lightgbm_version": lgb.__version__,
        "holdout": holdout,
    }
    (a.out / "metadata.json").write_text(json.dumps(meta, indent=2))
    print(json.dumps(meta, indent=2))
    print(f"Saved models to {a.out}")


if __name__ == "__main__":
    main()
