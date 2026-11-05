There are 16 Python files plus one notebook, all under `code/`. The Java backend calls only one of them directly: `prediction_service/api.py`.

## `prediction_service/`: the ML service

| File | Lines | Purpose |
| --- | ---: | --- |
| [api.py](code/prediction_service/api.py) | 48 | The ML API (FastAPI, port 8001). It has two endpoints: `/health` and `POST /v1/routes/rank`. It loads the models once at startup. |
| [predictor.py](code/prediction_service/predictor.py) | 85 | Loads the three saved LightGBM models and ranks the routes for one payment. It checks the input, builds the features, scores each route and predicts its hours and cost. Eligible routes come first, in ranked order, then ineligible ones with their reasons. |
| [features.py](code/prediction_service/features.py) | 118 | Builds the model inputs, using the same code for training and serving. It lists every route for the corridor (eligibility, fees, FX, cut-off slack, simulator estimates), adds the model features, and computes the simple formula score used for comparison. It also loads the banking calendar from the database. |
| [schemas.py](code/prediction_service/schemas.py) | 58 | The request and response formats for the ML API (Pydantic). The Java copy is `PredictionModels.java`; change both together. |
| [train.py](code/prediction_service/train.py) | 99 | Production training. It trains the ranker and the time and cost models, scores them on held-out payments, and saves the models plus `metadata.json` to `artifacts/`. Use `--train-until all` to train on all history. |
| [metrics.py](code/prediction_service/metrics.py) | 18 | Ranking metrics: NDCG@k and top-1 accuracy. |
| [evaluate_api.py](code/prediction_service/evaluate_api.py) | 100 | Sends the held-out payments to the running ML service. It reports whether the features match the stored history, how well the ranking does against ops and the simple formula, the time and cost errors, and the response time. |
| [client.py](code/prediction_service/client.py) | 31 | A small Python HTTP client for the ML service, used by `evaluate_api.py`. |
| [__init__.py](code/prediction_service/__init__.py) | 8 | Adds `scripts/` to the import path, so `engine` and `reference` can be imported. |
| `artifacts/` | — | Not Python: the saved models (`ranker.txt`, `time_regressor.txt`, `cost_regressor.txt`) and `metadata.json`. |

## `scripts/`: data and simulation

| File | Lines | Purpose |
| --- | ---: | --- |
| [reference.py](code/scripts/reference.py) | 164 | Fictional reference data: currencies, countries, banks, corridors, route templates (hops, fees, FX spreads, caps), cause codes and the seeded incidents. Everything else reads this one source. |
| [engine.py](code/scripts/engine.py) | 232 | The banking calendar and the route simulator. It handles holidays, weekends, Fedwire rules, the 2nd/4th Saturday rule in India and the Gulf weekends. It moves a payment bank by bank through cut-offs, opening hours and the Thursday netting batch. The data generator and `features.py` both use it. |
| [generate_history.py](code/scripts/generate_history.py) | 538 | Builds the synthetic SQLite history: payments, hop timelines, every possible route with its realised outcome and ranking label, incidents, FX and holidays. |
| [apply_views.py](code/scripts/apply_views.py) | 54 | Recreates the views from `schema.sql` in an existing database without touching the tables. Run it after you change a view. |
| [train_baseline.py](code/scripts/train_baseline.py) | 96 | The original one-off check that models can learn from the data. It trains and prints results but saves nothing. |

## `tests/`

| File | Lines | Purpose |
| --- | ---: | --- |
| [conftest.py](code/tests/conftest.py) | 18 | Sets up the import paths and provides a shared test client for the ML API. |
| [test_prediction_api.py](code/tests/test_prediction_api.py) | 96 | 12 tests: health, ranking order, the cap and retail-only rules, feature parity with stored history, bad inputs, and the view order in `schema.sql`. |

## `notebooks/`

| File | Purpose |
| --- | --- |
| [train_baseline_walkthrough.ipynb](code/notebooks/train_baseline_walkthrough.ipynb) | Your step-by-step walkthrough of the training. It goes with `CorridorIQ_ML_Learnings.md`. |

`train_baseline.py` and `train.py` do much the same training. `train.py` is the one that saves the models the service uses, so `train_baseline.py` and the notebook are effectively learning material. You could keep them as they are or fold them into `train.py` later.