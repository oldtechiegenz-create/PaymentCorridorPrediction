"""Loads the trained models and scores the eligible routes of one payment.

The route features come from the Java backend (see schemas.py); this module holds no banking logic. It is
usable in-process by other modules, or through the HTTP API in api.py.
"""
import json
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

from . import features as F
from .train import MODEL_DIR


class InvalidPayment(ValueError):
    pass


class Predictor:
    def __init__(self, model_dir: Path = MODEL_DIR):
        missing = [n for n in ("ranker.txt", "time_regressor.txt", "cost_regressor.txt", "metadata.json")
                   if not (model_dir / n).exists()]
        if missing:
            raise FileNotFoundError(f"{model_dir} is missing {missing}; run: python3 -m prediction_service.train")
        self.ranker = lgb.Booster(model_file=str(model_dir / "ranker.txt"))
        self.time_reg = lgb.Booster(model_file=str(model_dir / "time_regressor.txt"))
        self.cost_reg = lgb.Booster(model_file=str(model_dir / "cost_regressor.txt"))
        self.metadata = json.loads((model_dir / "metadata.json").read_text())
        self.categories = self.metadata.get("categories")
        if not self.categories:
            raise ValueError(f"{model_dir / 'metadata.json'} has no 'categories'; retrain with "
                             f"python3 -m prediction_service.train, or add them (see code/README.md)")

    def score(self, payment: dict, routes: list) -> list:
        """Scores for each route, in the order given. Rounded as the API returns them."""
        self._check(payment, routes)
        df = pd.DataFrame(routes)
        for column, value in payment.items():
            df[column] = value
        X = F.add_model_features(df, self.categories)[F.FEATURES]
        model_score = self.ranker.predict(X)
        predicted_hours = df.est_hours.clip(lower=0.25) * np.exp(self.time_reg.predict(X))
        predicted_cost = self.cost_reg.predict(X)
        heuristic = F.heuristic_score(df)
        return [{"template_id": df.template_id.iloc[i],
                 "model_score": round(float(model_score[i]), 4),
                 "predicted_hours": round(float(predicted_hours.iloc[i]), 2),
                 "predicted_cost_usd": round(float(predicted_cost[i]), 2),
                 "heuristic_score": round(float(heuristic.iloc[i]), 4)} for i in range(len(df))]

    def _check(self, payment: dict, routes: list) -> None:
        """Values outside the categories the models were trained on would silently read as missing."""
        for key, value in (("corridor_id", payment["corridor_id"]), ("bene_bank_id", payment["bene_bank_id"])):
            if value not in self.categories[key]:
                raise InvalidPayment(f"Unknown {key} {value}; the models know {self.categories[key]}")
        seen = set()
        for r in routes:
            if r["template_id"] not in self.categories["template_id"]:
                raise InvalidPayment(f"Unknown template_id {r['template_id']}; the models know "
                                     f"{self.categories['template_id']}")
            if r["template_id"] in seen:
                raise InvalidPayment(f"template_id {r['template_id']} appears twice")
            seen.add(r["template_id"])
