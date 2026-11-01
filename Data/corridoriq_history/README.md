# CorridorIQ historical data (MVP 1 foundation)

A SQLite database of synthetic cross-border payment history, plus the code that builds it. It powers the **Historical Cases** tab and the **Similar historical cases** panel, and gives the ranking and estimate models their training data from day one of MVP 1.

Everything here is test data. Banks, BICs, clients, FX rates and outcomes are fictional. When the design-partner bank's exports arrive, they load into the same tables.

## Files

| File | What it is |
| --- | --- |
| `corridoriq_history.db` | The generated database: 40,000 payments from April 2024 to September 2026 |
| `schema.sql` | Tables, indexes and views (SQLite 3.25 or later) |
| `code/scripts/reference.py` | Banks, corridors, route templates, fees, FX anchors (mirrors the demo portal) |
| `code/scripts/engine.py` | Banking calendar and hop-by-hop route simulation. This is the core of MVP 1 modules M3 and M6, reusable by the route engine |
| `code/scripts/generate_history.py` | Builds the database from the reference data |
| `historical_cases.sql` | Parameterised queries for each panel of the Historical Cases tab and the similar-cases panel |
| `code/scripts/train_baseline.py` | Trains a LightGBM ranker and regressors on the history, to show the data supports model predictions |
| `code/prediction_service/` | Prediction service (step 1C): trains and saves the models, serves them through a FastAPI app, plus an HTTP client and an evaluation script that replays the test payments through the API |

## Quick start

```bash
pip install holidays            # generator
pip install lightgbm pandas     # baseline models only

# run from the repository root; the database is written to Data/corridoriq_history/
python3 code/scripts/generate_history.py --payments 40000 --seed 42   # about 30 seconds
python3 code/scripts/train_baseline.py --split 2026-04-01
```

Prediction API (`pip install -r code/requirements.txt`, then from `code/`):

```bash
python3 -m prediction_service.train                          # saves models to prediction_service/artifacts/
python3 -m uvicorn prediction_service.api:app --port 8000    # docs at http://127.0.0.1:8000/docs
python3 -m prediction_service.evaluate_api                   # replays the test payments through the API
```

`POST /v1/routes/rank` takes the payment (corridor, beneficiary bank, client type, amount, priority, release time, deadline flag). It simulates every route, then returns them ranked with the simulator estimate, the predicted time and cost, the model score and the v1 heuristic score. Routes the payment can't use are listed with their reasons. Use `--train-until all` to train the production model on all history.

`--payments`, `--start`, `--end` and `--seed` control volume, window and reproducibility. The same seed always gives the same database. At 40,000 payments the file is about 65 MB; it scales roughly linearly.

## What is in the database

| Table | Rows | Holds |
| --- | ---: | --- |
| `payment` | 40,000 | One settled payment: client, corridor, amount, charges, purpose, priority, route used, fees, FX, outcome, time to credit |
| `payment_hop` | 151,932 | gpi-style timeline: each bank's arrival, start and finish, fee, status code, and any hold with its reason |
| `route_candidate` | 188,740 | Every route that was available for each payment: estimate at release and realised outcome, plus a ranking label |
| `incident` | 3,763 | Ops annotations on delays, repairs and returns (cause code and note) |
| `client` | 4,820 | 4,800 retail senders and 20 corporate or FI clients of the ordering bank |
| `fx_rate_daily` | 5,165 | Daily mid rates for JPY, INR, AED, SAR and EUR |
| `holiday` | 629 | Bank holidays for the nine countries on the network, 2023 to 2027 |
| `bank`, `corridor`, `route_template`, `route_template_hop`, `corridor_route`, `beneficiary_bank`, `bank_closure`, `country`, `currency`, `cause_code` | small | Reference data |
| `meta` | 11 | Generator version, seed, window and label definition |

Views: `v_case`, `v_route_performance`, `v_incident`, `v_corridor_monthly`, `v_ranking_training`.

## How the history is generated

- **Calendars.** Real time zones with daylight saving, and holidays from the `holidays` library for each bank's financial centre. On top of that:
    - Fedwire rules: a Sunday holiday closes Monday, but a Saturday holiday does not close Friday.
    - Gulf Friday–Saturday weekends in Bahrain and Saudi Arabia.
    - Indian bank closures on the 2nd and 4th Saturday.
    - Japanese bank holidays on 2–3 January and 31 December.
    - A few ad hoc bank closures.
    - Islamic holiday dates are estimates.
- **Route simulation.** Each payment moves bank by bank, respecting local cut-offs, opening hours, weekends, holidays and the Thursday netting batch. This is the same engine the portal used, ported to Python.
- **Realistic noise the simulator does not know.** These effects make the realised outcomes differ from the simulator's estimate, so a model has something real to learn:
    - each bank's true processing speed;
    - day-level backlogs;
    - month-end volume;
    - extra screening on large payments;
    - information requests (repairs);
    - returns;
    - occasional unexpected intermediary fees.
- **Who chose the route.** A pre-CorridorIQ operator policy picked the route used. It has habits (Express by default, Gulf for large FX tickets) and ignores foreign calendars about 70% of the time. Ops picked the best route in about 80% of payments, which leaves room for a model to improve.
- **Counterfactuals.** For every payment the generator also realises what each other route would have done, using the same shared randomness. That is what makes `route_candidate` a complete ranking dataset.
- **Labels.** `utility` combines realised time and cost, weighted by the payment's priority (0 = cost, 100 = speed). Returns are penalised, as are credits after the client's deadline. `relevance` is 4 for the best realised route, then 3, 2 and 1. The exact formula is in `meta.label_definition`.
- **Seeded incidents.** The eight incidents described in the portal appear as real cases, for example the Gulf-weekend case of 9 October 2025 and the Showa Day case. Their notes come from the simulation itself.

## Which query feeds which panel

| Portal panel | Source |
| --- | --- |
| Route performance (cases, median, P90, cost, STP) | `historical_cases.sql` query 1, or the all-time `v_route_performance` view |
| FX rate, last 90 days | Query 2. Volatility is computed in the app from daily log returns |
| Annotated incidents | Query 3, from the `v_incident` view |
| All cases | Query 4, from the `v_case` view |
| Similar historical cases (route tab) | Queries 5 and 6 |
| Banking calendar (route tab) | Query 7, plus `country.weekend_days` and `bank_closure` |
| Model training | The `v_ranking_training` view |

## Model readiness check

Results from `train_baseline.py`: trained on payments before 1 April 2026 and tested on 9,665 payments after it.

| Approach | NDCG@3 | Picks the best route |
| --- | ---: | ---: |
| Ops choice (history) | 0.883 | 80.1% |
| Simulator heuristic (MVP 1 v1 score) | 0.975 | 91.6% |
| LightGBM LambdaRank | 0.979 | 94.4% |

Time-to-credit error on routes under 72 hours is 3.4 hours for LightGBM, against 3.6 for the simulator alone. All-in cost error is 4.6%. These numbers show the pipeline works end to end. They say nothing about real-world accuracy until the models are retrained on the bank's real history.

## MVP 1 build order, with history first

| Step | Delivers | Status |
| --- | --- | --- |
| 1A Historical data foundation | SQLite schema, reference data, calendars, synthetic history, tab queries, baseline models | Done (this package) |
| 1B Route engine and rules | `engine.py` reading reference data from SQLite; rules with reason codes; calendar-conflict rule | Next |
| 1C Prediction service | v1 heuristic score, plus LightGBM ranker and time/cost regressors trained from `v_ranking_training`, with P50/P90 and score explanations. Runs in shadow until real data replaces the synthetic history | In progress: API, ranker and regressors in `code/prediction_service/`; P50/P90 and explanations to do |
| 1D Console | Route tab on the engine API; Historical Cases tab and similar cases served from SQLite | |
| 1E Parallel run with the partner bank | Load the bank's exports into the same tables, retrain, and measure agreement with ops | |

**Effect on the wider plan.** MVP 2 shrinks to loading and calibrating on the bank's real data. MVP 4 becomes promoting the model from shadow to live once it has been validated on real outcomes.

## When real data replaces the synthetic history

- **Filled directly from exports.** `payment`, `payment_hop` (from MT103/pacs.008 and gpi tracker events), `fx_rate_daily`, `holiday`, the reference tables, and `incident` (from ops annotations).
- **`route_candidate` needs a different source.** Real history only shows the outcome of the route that was used. For the other routes there are three options:
    - fill `realized_*` from the calibrated simulator;
    - learn from checker overrides once MVP 3 is live;
    - use counterfactual learning methods.

  Until then, the ranker trained here demonstrates the pipeline, not real routing quality.
- **Things the generator invents that real data will contain instead.** Client priorities, deadlines, and the hidden bank speeds.

## Simplifications to know about

- There is one ordering bank (Lakeshore Community Bank). All payments are released on US business days for the same-day value date.
- Value-date scheduling in advance, partial returns, and charge-back of fees on returns are not modelled.
- FX rates are a random walk anchored to the portal's rates on 30 September 2026. They are not market history.
