# CorridorIQ code

Two services. The **Java backend** is what the portal and other systems call. It does everything except run the models. The **Python scoring service** holds the trained models and nothing else; only the backend calls it.

```
portal (corridoriq.html) ──► Java backend :8000 ──► SQLite history DB (read-only)
                                   │
                                   │  POST /v1/routes/rank
                                   │   1. validates the payment
                                   │   2. simulates every route of the corridor (RouteEstimator)
                                   │   3. sends the eligible routes' features ──► Python scoring service :8001
                                   │                                              POST /v1/routes/score
                                   │   4. ranks the routes by the returned     ◄── (LightGBM models only)
                                   └──    scores and answers the caller
```

| Folder | Language | What it does |
| --- | --- | --- |
| `backend/` | Java 21, Spring Boot | The portal at `/`, the history API under `/v1/history/...`, route ranking at `POST /v1/routes/rank`, `/health` for both services, the route simulator (`engine`), and the generator of the synthetic history (`generator`) |
| `prediction_service/` | Python | Training (`train.py`, `features.py`, `metrics.py`) and scoring (`predictor.py`, `api.py`, `schemas.py`). Nothing else |
| `notebooks/` | Python | Training walkthrough |
| `tests/` | Python | Tests of the scoring service |

## Why the features are computed in Java

The models read simulator estimates: how long each route should take given every bank's calendar, cut-offs and fees. The same Java code (`RouteEstimator`) writes those numbers into the history database for training and computes them again when a payment is ranked, so the models never see inputs built a different way from the ones they learned on. Two tests keep that true:

- `EngineParityTest` re-estimates stored payments in Java and compares them with the 188,740 candidate routes the original Python engine wrote into the database.
- `test_scores_match_the_offline_models_on_stored_candidates` (Python) checks that scoring through the HTTP service gives the same numbers as the model files do on the training data.

## Java packages (`backend/src/main/java/com/corridoriq/backend/`)

| Package | Main classes | Job |
| --- | --- | --- |
| `engine` | `Reference`, `BankCalendar`, `RouteSimulator`, `RouteEstimator` | The fictional banks, corridors and routes; the banking calendar (holidays come from `resources/reference/holidays.csv`); the hop-by-hop simulation; the estimate of every route of a payment |
| `generator` | `HistoryGenerator`, `HistoryWriter`, `GenerateHistory`, `ApplyViews` | Builds the synthetic payment history into SQLite; recreates the views of an existing database |
| `prediction` | `RouteRanker`, `PredictionClient`, `PredictionController` | Validates a payment, builds the model inputs, calls the scoring service and ranks the answer |
| `history` | `HistoryRepository`, `HistoryController` | The Historical Cases tab |

Generate a history (from `backend/`; it never overwrites an existing file unless you pass `--force`):

```bash
./mvnw -q compile spring-boot:run \
  -Dspring-boot.run.main-class=com.corridoriq.backend.generator.GenerateHistory \
  -Dspring-boot.run.arguments="--payments 40000 --seed 42 --out ../../Data/corridoriq_history/new.db"
```

The same seed gives the same database each time, but a different one from the original Python generator's (the random number generators differ). The behaviour matches; the corridor mix and exact figures do not.

## Run

```bash
# 1. Scoring service (from code/)
pip install -r requirements.txt
python3 -m prediction_service.train                          # only if artifacts/ is missing or you want new models
python3 -m uvicorn prediction_service.api:app --port 8001

# 2. Backend (from code/backend/, in another terminal; Java 21, no Maven install needed)
./mvnw spring-boot:run
```

Open http://127.0.0.1:8000/. The history tabs work even when the scoring service is down. In that case `/health` reports `degraded` and `/v1/routes/rank` returns 503.

```bash
curl -s localhost:8000/health
curl -s localhost:8000/v1/routes/rank -H 'Content-Type: application/json' -d '{"corridor_id": "USD-INR",
  "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580, "priority": 15,
  "released_at": "2026-04-01T13:01:00Z", "has_deadline": false}'
```

Backend settings are in `backend/src/main/resources/application.properties`. Override them with environment variables: `CORRIDORIQ_DB`, `CORRIDORIQ_SQL`, `CORRIDORIQ_PORTAL`, `CORRIDORIQ_PREDICTION_URL` and `PORT`. The scoring service reads `CORRIDORIQ_MODEL_DIR`.

After changing the Java code, restart the backend. After changing the Python code or the models, restart the scoring service.

## Test

```bash
cd backend && ./mvnw test                 # 152 tests: engine, generator, views, ranking (against a stand-in scorer), history API
cd .. && python3 -m pytest tests -q       # 18 tests: the scoring service, against the real saved models
```

Model quality is measured when the models are trained: `python3 -m prediction_service.train` scores them on the held-out payments and saves the results in `artifacts/metadata.json` (`holdout`).

After changing a view in `schema.sql`, recreate the views in an existing database (from `backend/`):

```bash
./mvnw -q compile spring-boot:run -Dspring-boot.run.main-class=com.corridoriq.backend.generator.ApplyViews
# --db path/to.db to choose another database
```

## The contracts

- **Public:** `POST /v1/routes/rank` takes a payment and returns every route of the corridor, ranked. It is defined only in Java (`PaymentRequest` and `RankResponse` in `backend/.../prediction/PredictionModels.java`).
- **Internal:** `POST /v1/routes/score` takes the payment-level inputs and the features of the eligible routes, and returns one score per route. It is defined in `prediction_service/schemas.py` and mirrored as Java records in `PredictionModels.java` (`ScorePayment`, `RouteFeatures`, `ScoreRequest`, `ScoreResponse`). Change both together. The scoring service's OpenAPI document is at http://127.0.0.1:8001/openapi.json.

The history SQL lives in `../Data/corridoriq_history/historical_cases.sql` as named queries over the views in `schema.sql`. The backend loads the queries by name.

## Older model files

Training now saves the route, corridor and bank lists the models were trained with (`categories` in `metadata.json`), because LightGBM reads them as integer codes. Models trained before 6 November 2026 lack them. Retrain, or add them to the old file without changing the models:

```bash
python3 - <<'EOF'
import json, sqlite3, sys
sys.path.insert(0, ".")
from prediction_service import features as F
p = "prediction_service/artifacts/metadata.json"
meta = json.load(open(p))
meta["categories"] = F.load_categories(sqlite3.connect(F.DB_PATH))
json.dump(meta, open(p, "w"), indent=2)
EOF
```
