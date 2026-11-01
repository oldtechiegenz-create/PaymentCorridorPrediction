"""Candidate-route features, shared by training and serving so both see identical inputs."""
import sqlite3
from contextlib import closing
from datetime import date, datetime, time, timezone
from pathlib import Path

import numpy as np
import pandas as pd

import reference as R
from engine import TZ, Calendar, calendar_conflict, naive_hours, portal_dow, resolve_hops, simulate

UTC = timezone.utc
DB_PATH = Path(__file__).resolve().parents[2] / "Data" / "corridoriq_history" / "corridoriq_history.db"

FEATURES = ["est_hours", "naive_hours", "est_cost_usd", "fee_total_usd", "fx_bps", "hop_count", "intermediaries",
            "est_nonbusiness_days", "calendar_conflict", "cutoff_slack_hours", "max_risk", "stp_rate_nominal",
            "priority", "log_amount", "is_fi", "release_hour_et", "release_weekday", "day_of_month", "has_deadline",
            "tpl", "corr", "bene"]

# Fixed category lists keep the integer codes LightGBM sees identical between training and serving.
TEMPLATE_IDS = sorted(R.TEMPLATES)
CORRIDOR_IDS = sorted(R.CORRIDORS)
BENE_BANK_IDS = sorted(b for c in R.CORRIDORS.values() for b, _ in c[5])


def bene_banks(corridor_id: str):
    return [b for b, _ in R.CORRIDORS[corridor_id][5]]


def load_calendar(db_path: Path = DB_PATH):
    """Banking calendar from the history database, plus the (first, last) years it covers."""
    with closing(sqlite3.connect(db_path)) as conn:
        hol = conn.execute("SELECT country_code, holiday_date, name FROM holiday").fetchall()
        clo = conn.execute("SELECT bank_id, closure_date, reason FROM bank_closure").fetchall()
    table = {}
    for cc, d, name in hol:
        table.setdefault(cc, {})[date.fromisoformat(d)] = name
    closures = {(b, date.fromisoformat(d)): r for b, d, r in clo}
    years = sorted(int(d[:4]) for _, d, _ in hol)
    return Calendar(table, closures), (years[0], years[-1])


def payment_context(amount_usd, client_type, corridor_id, bene_bank_id, priority, released_at: datetime, has_deadline):
    """Payment-level columns, named as in v_ranking_training."""
    local = released_at.astimezone(TZ["US"])
    vd = local.date()
    return {
        "corridor_id": corridor_id, "bene_bank_id": bene_bank_id, "client_type": client_type,
        "amount_usd": float(amount_usd), "priority": int(priority),
        "release_hour_et": round(local.hour + local.minute / 60, 2),
        "release_weekday": portal_dow(vd), "day_of_month": vd.day, "has_deadline": int(bool(has_deadline)),
    }


def estimate_candidates(corridor_id, bene_bank_id, client_type, amount_usd, released_at: datetime, cal: Calendar):
    """Simulator estimate for every route of the corridor, as known at release.

    Mirrors the estimate half of generate_history.generate(); the two must stay in step
    (evaluate_api.py reports any drift against the stored route_candidate rows).
    """
    t0 = released_at.astimezone(UTC)
    vd = t0.astimezone(TZ["US"]).date()
    corridor = R.CORRIDORS[corridor_id]
    out = []
    for tid in corridor[6]:
        tpl = R.TEMPLATES[tid]
        hops = resolve_hops(tid, corridor_id, bene_bank_id)
        fees = list(tpl[3])
        if client_type == "FI":
            fees[0] = min(fees[0], R.FI_FIRST_HOP_FEE_CAP)
        bps = tpl[5] if amount_usd >= R.LARGE_TICKET_USD else tpl[4]
        nominal = list(tpl[6])
        est = simulate(hops, tid, t0, cal, nominal)
        reasons = []
        if tpl[8] and amount_usd > tpl[8]:
            reasons.append(f"Above the ${tpl[8]:,.0f} per-transfer cap")
        if tpl[9] and client_type == "FI":
            reasons.append("Available to retail clients only")
        first = hops[0]
        cut = datetime.combine(vd, time(first.cutoff), tzinfo=TZ[first.country]).astimezone(UTC)
        out.append({
            "template_id": tid,
            "eligible": not reasons,
            "ineligible_reasons": reasons,
            "hop_count": len(hops),
            "intermediaries": len(hops) - 2,
            "fee_total_usd": round(sum(fees), 2),
            "fx_bps": bps,
            "est_cost_usd": round(sum(fees) + amount_usd * bps / 1e4, 2),
            "est_hours": round(est.hours, 2),
            "naive_hours": round(naive_hours(tid, t0, cal, nominal), 2),
            "est_nonbusiness_days": est.calendar_days,
            "calendar_conflict": int(calendar_conflict(hops, vd, cal)),
            "cutoff_slack_hours": round((cut - t0).total_seconds() / 3600, 2),
            "max_risk": max([h.risk for h in hops] + [corridor[3]]),
            "stp_rate_nominal": tpl[7],
        })
    return out


def add_model_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["log_amount"] = np.log(df.amount_usd)
    df["is_fi"] = (df.client_type == "FI").astype(int)
    df["tpl"] = pd.Categorical(df.template_id, categories=TEMPLATE_IDS)
    df["corr"] = pd.Categorical(df.corridor_id, categories=CORRIDOR_IDS)
    df["bene"] = pd.Categorical(df.bene_bank_id, categories=BENE_BANK_IDS)
    return df


def heuristic_score(g: pd.DataFrame) -> pd.Series:
    """MVP 1 v1 score: priority-weighted, per-payment normalised log time and cost. Higher is better."""
    lt, lc = np.log(g.est_hours.clip(lower=0.5)), np.log(g.est_cost_usd)
    tn = (lt - lt.min()) / (lt.max() - lt.min()) if lt.max() > lt.min() else lt * 0
    cn = (lc - lc.min()) / (lc.max() - lc.min()) if lc.max() > lc.min() else lc * 0
    w = g.priority.iloc[0] / 100
    return -(w * tn + (1 - w) * cn)
