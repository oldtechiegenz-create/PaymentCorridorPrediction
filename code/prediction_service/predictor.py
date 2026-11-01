"""Loads the trained models and ranks the candidate routes for one payment.

Usable in-process by other CorridorIQ modules, or through the HTTP API in api.py.
"""
import json
from datetime import datetime
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

import reference as R
from . import features as F
from .train import MODEL_DIR


class InvalidPayment(ValueError):
    pass


class Predictor:
    def __init__(self, model_dir: Path = MODEL_DIR, db_path: Path = F.DB_PATH):
        missing = [n for n in ("ranker.txt", "time_regressor.txt", "cost_regressor.txt", "metadata.json")
                   if not (model_dir / n).exists()]
        if missing:
            raise FileNotFoundError(f"{model_dir} is missing {missing}; run: python3 -m prediction_service.train")
        self.ranker = lgb.Booster(model_file=str(model_dir / "ranker.txt"))
        self.time_reg = lgb.Booster(model_file=str(model_dir / "time_regressor.txt"))
        self.cost_reg = lgb.Booster(model_file=str(model_dir / "cost_regressor.txt"))
        self.metadata = json.loads((model_dir / "metadata.json").read_text())
        self.calendar, self.calendar_years = F.load_calendar(db_path)

    def rank(self, corridor_id: str, bene_bank_id: str, client_type: str, amount_usd: float, priority: int,
             released_at: datetime, has_deadline: bool = False) -> dict:
        """Every route of the corridor: eligible ones ranked best first, then ineligible ones with reasons."""
        if corridor_id not in R.CORRIDORS:
            raise InvalidPayment(f"Unknown corridor {corridor_id}")
        if bene_bank_id not in F.bene_banks(corridor_id):
            raise InvalidPayment(f"{bene_bank_id} is not a beneficiary bank on {corridor_id}; "
                                 f"expected one of {F.bene_banks(corridor_id)}")
        if released_at.tzinfo is None:
            raise InvalidPayment("released_at must include a time zone")
        first, last = self.calendar_years
        if not first <= released_at.year <= last:
            raise InvalidPayment(f"Banking calendar only covers {first}-{last}")

        ctx = F.payment_context(amount_usd, client_type, corridor_id, bene_bank_id, priority, released_at, has_deadline)
        cands = F.estimate_candidates(corridor_id, bene_bank_id, client_type, amount_usd, released_at, self.calendar)
        df = pd.DataFrame([{**ctx, **c} for c in cands])
        el = df[df.eligible].copy()
        X = F.add_model_features(el)[F.FEATURES]
        el["model_score"] = self.ranker.predict(X)
        el["predicted_hours"] = el.est_hours.clip(lower=0.25) * np.exp(self.time_reg.predict(X))
        el["predicted_cost_usd"] = self.cost_reg.predict(X)
        el["heuristic_score"] = F.heuristic_score(el)
        el = el.sort_values("model_score", ascending=False)

        routes = []
        for rank, (_, r) in enumerate(el.iterrows(), start=1):
            routes.append({**self._route(r), "rank": rank,
                           "model_score": round(float(r.model_score), 4),
                           "heuristic_score": round(float(r.heuristic_score), 4),
                           "predicted_hours": round(float(r.predicted_hours), 2),
                           "predicted_cost_usd": round(float(r.predicted_cost_usd), 2)})
        for _, r in df[~df.eligible].iterrows():
            routes.append({**self._route(r), "rank": None, "model_score": None, "heuristic_score": None,
                           "predicted_hours": None, "predicted_cost_usd": None})
        return {"model_version": self.metadata["model_version"],
                "recommended_template_id": routes[0]["template_id"],
                "routes": routes}

    @staticmethod
    def _route(r) -> dict:
        tpl = R.TEMPLATES[r.template_id]
        return {
            "template_id": r.template_id, "name": tpl[0], "via": tpl[1],
            "eligible": bool(r.eligible), "ineligible_reasons": list(r.ineligible_reasons),
            "est_hours": float(r.est_hours), "naive_hours": float(r.naive_hours),
            "est_cost_usd": float(r.est_cost_usd), "fee_total_usd": float(r.fee_total_usd), "fx_bps": float(r.fx_bps),
            "hop_count": int(r.hop_count), "intermediaries": int(r.intermediaries),
            "est_nonbusiness_days": int(r.est_nonbusiness_days), "calendar_conflict": bool(r.calendar_conflict),
            "cutoff_slack_hours": float(r.cutoff_slack_hours), "max_risk": int(r.max_risk),
            "stp_rate_nominal": float(r.stp_rate_nominal),
        }
