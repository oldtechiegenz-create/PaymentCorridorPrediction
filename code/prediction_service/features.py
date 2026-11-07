"""The model's inputs, shared by training and scoring so both see identical columns and category codes.

The route features themselves (simulator estimates, fees, calendar conflicts, eligibility) are computed by the
Java backend (RouteEstimator) and stored in route_candidate for training, or sent to /v1/routes/score for
serving. This module only turns those columns into what the models read.
"""
import sqlite3
from pathlib import Path

import numpy as np
import pandas as pd

DB_PATH = Path(__file__).resolve().parents[2] / "Data" / "corridoriq_history" / "corridoriq_history.db"

FEATURES = ["est_hours", "naive_hours", "est_cost_usd", "fee_total_usd", "fx_bps", "hop_count", "intermediaries",
            "est_nonbusiness_days", "calendar_conflict", "cutoff_slack_hours", "max_risk", "stp_rate_nominal",
            "priority", "log_amount", "is_fi", "release_hour_et", "release_weekday", "day_of_month", "has_deadline",
            "tpl", "corr", "bene"]

# Columns the caller supplies for each route, and once per payment; the rest of FEATURES is derived below.
ROUTE_COLUMNS = ["template_id", "est_hours", "naive_hours", "est_cost_usd", "fee_total_usd", "fx_bps", "hop_count",
                 "intermediaries", "est_nonbusiness_days", "calendar_conflict", "cutoff_slack_hours", "max_risk",
                 "stp_rate_nominal"]
PAYMENT_COLUMNS = ["corridor_id", "bene_bank_id", "client_type", "amount_usd", "priority", "release_hour_et",
                   "release_weekday", "day_of_month", "has_deadline"]


def load_categories(conn: sqlite3.Connection) -> dict:
    """The route, corridor and beneficiary-bank values the models were trained on, in a fixed (sorted) order.

    LightGBM reads these columns as integer codes, so training and serving must use the same lists in the same
    order. Training reads them from the database and saves them in metadata.json; serving reads them from there.
    """
    def column(sql):
        return [row[0] for row in conn.execute(sql)]

    return {
        "template_id": column("SELECT template_id FROM route_template ORDER BY template_id"),
        "corridor_id": column("SELECT corridor_id FROM corridor ORDER BY corridor_id"),
        "bene_bank_id": column("SELECT DISTINCT bank_id FROM beneficiary_bank ORDER BY bank_id"),
    }


def add_model_features(df: pd.DataFrame, categories: dict) -> pd.DataFrame:
    df = df.copy()
    df["log_amount"] = np.log(df.amount_usd)
    df["is_fi"] = (df.client_type == "FI").astype(int)
    df["tpl"] = pd.Categorical(df.template_id, categories=categories["template_id"])
    df["corr"] = pd.Categorical(df.corridor_id, categories=categories["corridor_id"])
    df["bene"] = pd.Categorical(df.bene_bank_id, categories=categories["bene_bank_id"])
    return df


def heuristic_score(g: pd.DataFrame) -> pd.Series:
    """MVP 1 v1 score: priority-weighted, per-payment normalised log time and cost. Higher is better."""
    lt, lc = np.log(g.est_hours.clip(lower=0.5)), np.log(g.est_cost_usd)
    tn = (lt - lt.min()) / (lt.max() - lt.min()) if lt.max() > lt.min() else lt * 0
    cn = (lc - lc.min()) / (lc.max() - lc.min()) if lc.max() > lc.min() else lc * 0
    w = g.priority.iloc[0] / 100
    return -(w * tn + (1 - w) * cn)
