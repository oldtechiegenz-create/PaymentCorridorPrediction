There are 9 Python files plus one notebook, all under `code/`. Python does two jobs only: it **trains** the models and it **serves** them (scores routes). Everything else is Java, in `code/backend`: the route simulator, the data generator, recreating database views, validating a payment and ranking its routes. The Java backend calls only one Python file directly: `prediction_service/api.py`.

## `prediction_service/`: training and serving

| File | Lines | Job | Purpose |
| --- | ---: | --- | --- |
| [train.py](code/prediction_service/train.py) | 101 | Train | Trains the ranker and the time and cost models from `v_ranking_training`, scores them on held-out payments, and saves the models plus `metadata.json` (hold-out scores and the category lists) to `artifacts/`. Use `--train-until all` to train on all history. |
| [metrics.py](code/prediction_service/metrics.py) | 18 | Train | Ranking metrics: NDCG@k and top-1 accuracy, used by `train.py` to score the models. |
| [features.py](code/prediction_service/features.py) | 60 | Both | Turns the route and payment columns into the model inputs, with the same code for training and serving. Reads the route, corridor and bank lists from the database (`load_categories`) so both use the same category codes, and computes the simple formula score used for comparison. |
| [api.py](code/prediction_service/api.py) | 49 | Serve | The scoring web service (FastAPI, port 8001): `/health` and `POST /v1/routes/score`. Loads the models once at startup. |
| [predictor.py](code/prediction_service/predictor.py) | 65 | Serve | Loads the three saved models and scores the routes it is given: a ranker score, a predicted time and cost, and the formula score for each. Checks that the corridor, bank and routes are ones the models know. It does not rank them; Java does. |
| [schemas.py](code/prediction_service/schemas.py) | 69 | Serve | The request and response formats of the scoring service (Pydantic). The Java copy is `PredictionModels.java`; change both together. |
| [__init__.py](code/prediction_service/__init__.py) | 1 | — | Marks the folder as a package, so the files can import each other (`from . import features`). |
| `artifacts/` | — | — | Not Python: the saved models (`ranker.txt`, `time_regressor.txt`, `cost_regressor.txt`) and `metadata.json`. |

## `tests/`

| File | Lines | Purpose |
| --- | ---: | --- |
| [conftest.py](code/tests/conftest.py) | 17 | Sets up the import path and provides a shared test client for the scoring service. |
| [test_prediction_api.py](code/tests/test_prediction_api.py) | 159 | 18 tests: health, one score per route in the order sent, scores matching the model files on stored data, ranking quality on held-out payments, bad input, and the saved category lists. |

## `notebooks/`

| File | Purpose |
| --- | --- |
| [train_baseline_walkthrough.ipynb](code/notebooks/train_baseline_walkthrough.ipynb) | Your step-by-step walkthrough of the training. It goes with `CorridorIQ_ML_Learnings.md`. It holds its own copy of the code and reads the database directly, so it does not depend on any other file here. |

## What moved to Java

| Former Python file | Java replacement (under `code/backend/src/main/java/com/corridoriq/backend/`) |
| --- | --- |
| `scripts/reference.py` | `engine/Reference.java` |
| `scripts/engine.py` | `engine/BankCalendar.java`, `engine/RouteSimulator.java`, `engine/RouteEstimator.java` |
| `scripts/generate_history.py` | `generator/HistoryGenerator.java`, `HistoryWriter.java`, `GenerateHistory.java` |
| `scripts/apply_views.py` | `generator/ApplyViews.java` |
| The feature building in `features.py` and ranking in `predictor.py` | `engine/RouteEstimator.java`, `prediction/RouteRanker.java` |

Removed without a replacement, because they were neither training nor serving and what they checked is covered elsewhere:

- `scripts/train_baseline.py`: a copy of the training that saved nothing. `train.py` does the same and keeps the models.
- `prediction_service/evaluate_api.py` and `client.py`: an end-to-end replay. Model quality is reported by `train.py`, Java's `EngineParityTest` checks the features against the stored history, and a Python test checks that served scores equal the model files' scores.

All of them are still in git history if you want to read them.
