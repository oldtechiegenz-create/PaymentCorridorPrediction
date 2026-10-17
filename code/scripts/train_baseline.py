#!/usr/bin/env python3
"""Baseline check: can models learn from the history?

Trains on payments before --split and tests on the rest:
  * LightGBM LambdaRank ranker over candidate routes (label: route_candidate.relevance)
  * LightGBM regressors for realised time to credit and realised cost
and compares the ranker with (a) the route ops actually chose and (b) a transparent
heuristic that uses only the simulator estimate (what MVP 1 shows before ML).

    pip install lightgbm pandas numpy
    python3 code/scripts/train_baseline.py --split 2026-04-01
"""
import argparse
import sqlite3
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

FEATURES = ["est_hours", "naive_hours", "est_cost_usd", "fee_total_usd", "fx_bps", "hop_count", "intermediaries",
            "est_nonbusiness_days", "calendar_conflict", "cutoff_slack_hours", "max_risk", "stp_rate_nominal",
            "priority", "log_amount", "is_fi", "release_hour_et", "release_weekday", "day_of_month", "has_deadline",
            "tpl", "corr", "bene"]


def ndcg_at_k(groups, k=3):
    out = []
    for rel, score in groups:
        order = np.argsort(-score)[:k]
        dcg = sum((2 ** rel[i] - 1) / np.log2(r + 2) for r, i in enumerate(order))
        ideal = sorted(rel, reverse=True)[:k]
        idcg = sum((2 ** g - 1) / np.log2(r + 2) for r, g in enumerate(ideal))
        out.append(dcg / idcg if idcg > 0 else 1.0)
    return float(np.mean(out))


def top1(groups):
    return float(np.mean([rel[np.argmax(score)] == rel.max() for rel, score in groups]))

DATA_DIR = Path(__file__).resolve().parents[2] / "Data" / "corridoriq_history"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=str(DATA_DIR / "corridoriq_history.db"))
    ap.add_argument("--split", default="2026-04-01")
    a = ap.parse_args()

    df = pd.read_sql("SELECT * FROM v_ranking_training WHERE eligible = 1", sqlite3.connect(a.db))
    df["log_amount"] = np.log(df.amount_usd)
    df["is_fi"] = (df.client_type == "FI").astype(int)
    df["tpl"] = df.template_id.astype("category")
    df["corr"] = df.corridor_id.astype("category")
    df["bene"] = df.bene_bank_id.astype("category")
    df = df.sort_values(["payment_id", "template_id"])
    tr, te = df[df.value_date < a.split], df[df.value_date >= a.split]

    ranker = lgb.LGBMRanker(objective="lambdarank", n_estimators=400, learning_rate=0.05, num_leaves=31,
                            min_child_samples=40, verbose=-1)
    ranker.fit(tr[FEATURES], tr.relevance, group=tr.groupby("payment_id").size().values)
    te = te.assign(model=ranker.predict(te[FEATURES]))

    # transparent heuristic: priority-weighted normalised log time and cost from the simulator estimate
    def heur(g):
        lt, lc = np.log(g.est_hours.clip(lower=0.5)), np.log(g.est_cost_usd)
        tn = (lt - lt.min()) / (lt.max() - lt.min()) if lt.max() > lt.min() else lt * 0
        cn = (lc - lc.min()) / (lc.max() - lc.min()) if lc.max() > lc.min() else lc * 0
        w = g.priority.iloc[0] / 100
        return -(w * tn + (1 - w) * cn)
    te = te.assign(heur=te.groupby("payment_id", group_keys=False).apply(heur))

    gs = lambda col: [(g.relevance.values, g[col].values.astype(float)) for _, g in te.groupby("payment_id") if len(g) > 1]
    print(f"Train payments {tr.payment_id.nunique():,} · test payments {te.payment_id.nunique():,} (from {a.split})")
    print(f"{'':28}{'NDCG@3':>8}{'Picks best':>12}")
    for name, col in [("Ops choice (history)", "was_chosen"), ("Simulator heuristic", "heur"), ("LightGBM ranker", "model")]:
        g = gs(col)
        print(f"{name:28}{ndcg_at_k(g):8.3f}{top1(g) * 100:11.1f}%")

    ok = df.realized_outcome != "Returned"
    tr_r, te_r = tr[ok.loc[tr.index]], te[ok.loc[te.index]]
    # predict the correction to the simulator (log ratio), not the hours from scratch
    ratio = np.log(tr_r.realized_hours.clip(lower=0.25) / tr_r.est_hours.clip(lower=0.25))
    reg_t = lgb.LGBMRegressor(objective="l1", n_estimators=500, learning_rate=0.05, num_leaves=63, verbose=-1).fit(tr_r[FEATURES], ratio)
    reg_c = lgb.LGBMRegressor(n_estimators=300, learning_rate=0.05, num_leaves=31, verbose=-1).fit(tr_r[FEATURES], tr_r.realized_cost_usd)
    under72 = te_r.realized_hours < 72
    mae_sim = np.mean(np.abs(te_r.est_hours - te_r.realized_hours)[under72])
    pred_h = te_r.est_hours.clip(lower=0.25) * np.exp(reg_t.predict(te_r[FEATURES]))
    mae_mod = np.mean(np.abs(pred_h - te_r.realized_hours)[under72])
    mape_c = np.mean(np.abs(reg_c.predict(te_r[FEATURES]) - te_r.realized_cost_usd) / te_r.realized_cost_usd) * 100
    print(f"Time to credit MAE (routes under 72 h): simulator {mae_sim:.1f} h · LightGBM {mae_mod:.1f} h")
    print(f"All-in cost MAPE: LightGBM {mape_c:.1f}%")


if __name__ == "__main__":
    main()
