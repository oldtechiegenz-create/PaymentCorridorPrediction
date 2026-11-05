# CorridorIQ code

Two services. The **Java backend** is what the portal and other systems call. The **Python prediction service** hosts the ML models, and only the backend calls it.

```
portal (corridoriq.html) ──► Java backend :8000 ──► SQLite history DB (read-only)
                                   │
                                   └── POST /v1/routes/rank ──► Python prediction service :8001
                                                                  (features + LightGBM models)
```

| Folder | Language | What it does |
| --- | --- | --- |
| `backend/` | Java 21, Spring Boot | Serves the portal at `/` and the history API under `/v1/history/...`. Offers `POST /v1/routes/rank` and forwards it to the prediction service. Reports `/health` for both |
| `prediction_service/` | Python | Training (`train.py`), evaluation (`evaluate_api.py`, `metrics.py`) and the ML API (`api.py`: `/health`, `/v1/routes/rank`). `features.py` builds the model inputs for both training and serving |
| `scripts/` | Python | Builds the history database (`generate_history.py`, `engine.py`, `reference.py`), recreates its views (`apply_views.py`) and trains the baseline (`train_baseline.py`) |
| `notebooks/` | Python | Training walkthrough |

Feature engineering stays in Python, next to the models: route candidates, banking calendar, eligibility rules and model features. Training and serving then run the same code, so the model never receives inputs computed differently from the ones it learned on. `tests/test_prediction_api.py` checks that against the stored history.

## Run

```bash
# 1. Prediction service (from code/)
pip install -r requirements.txt
python3 -m prediction_service.train                          # only if artifacts/ is missing or you want new models
python3 -m uvicorn prediction_service.api:app --port 8001

# 2. Backend (from code/backend/, in another terminal; Java 21, no Maven install needed)
./mvnw spring-boot:run
```

Open http://127.0.0.1:8000/. The history tabs work even when the prediction service is down. In that case `/health` reports `degraded` and `/v1/routes/rank` returns 503.

```bash
curl -s localhost:8000/health
curl -s localhost:8000/v1/routes/rank -H 'Content-Type: application/json' -d '{"corridor_id": "USD-INR",
  "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580, "priority": 15,
  "released_at": "2026-04-01T13:01:00Z", "has_deadline": false}'
```

Backend settings are in `backend/src/main/resources/application.properties`. Override them with environment variables: `CORRIDORIQ_DB`, `CORRIDORIQ_SQL`, `CORRIDORIQ_PORTAL`, `CORRIDORIQ_PREDICTION_URL` and `PORT`.

## Test

```bash
cd backend && ./mvnw test                 # history API against the database; rank gateway against a fake model service
cd .. && python3 -m pytest tests -q       # prediction service, including feature parity with stored history
python3 -m prediction_service.evaluate_api --limit 500   # full replay against the running prediction service
```

## The contract between the two

The request and response of `/v1/routes/rank` are defined in `prediction_service/schemas.py`. They are mirrored as Java records in `backend/.../prediction/PredictionModels.java`. Change both together. The prediction service's OpenAPI document is at http://127.0.0.1:8001/openapi.json.

The history SQL lives in `../Data/corridoriq_history/historical_cases.sql` as named queries over the views in `schema.sql`. The backend loads the queries by name.
