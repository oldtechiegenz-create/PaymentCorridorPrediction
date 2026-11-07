# CorridorIQ historical data (MVP 1 foundation)

A SQLite database of synthetic cross-border payment history, plus the code that builds it. It powers the **Historical Cases** tab and the **Similar historical cases** panel, and gives the ranking and estimate models their training data from day one of MVP 1.

Everything here is test data. Banks, BICs, clients, FX rates and outcomes are fictional. When the design-partner bank's exports arrive, they load into the same tables.

## Files

| File | What it is |
| --- | --- |
| `corridoriq_history.db` | The generated database: 40,000 payments from April 2024 to September 2026 |
| `schema.sql` | Tables, indexes and views (SQLite 3.25 or later) |
| `code/backend/.../engine/Reference.java` | Banks, corridors, route templates, fees, FX anchors (mirrors the demo portal) |
| `code/backend/.../engine/` | Banking calendar and hop-by-hop route simulation (`BankCalendar`, `RouteSimulator`, `RouteEstimator`). This is the core of MVP 1 modules M3 and M6, reusable by the route engine. It replaces the Python `engine.py` |
| `code/backend/.../generator/GenerateHistory.java` | Builds the database from the reference data (Java; replaces the former `generate_history.py`) |
| `historical_cases.sql` | Named queries over the views, one or more per panel of the Historical Cases tab and the similar-cases panel; the history API loads them by name |
| `code/backend/.../generator/ApplyViews.java` | Recreates the views from `schema.sql` in an existing database, leaving the tables untouched |
| `code/prediction_service/` | Prediction service (step 1C, Python): trains and saves the models and scores routes through a FastAPI app, plus an HTTP client and an evaluation script that replays the test payments through the backend |
| `code/backend/` | Java backend (Spring Boot): serves the portal and the history API, simulates and ranks the routes of a payment (asking the prediction service for the model scores), and generates the synthetic history |

## Quick start

```bash
# 1. Generate the database with the Java backend (from code/backend/; about 15 seconds)
./mvnw -q compile spring-boot:run \
  -Dspring-boot.run.main-class=com.corridoriq.backend.generator.GenerateHistory \
  -Dspring-boot.run.arguments="--payments 40000 --seed 42 --out ../../Data/corridoriq_history/new.db"
```

Prediction service (`pip install -r code/requirements.txt`, then from `code/`):

```bash
python3 -m prediction_service.train                          # trains, scores on held-out payments, saves to prediction_service/artifacts/
python3 -m uvicorn prediction_service.api:app --port 8001    # scoring API, docs at http://127.0.0.1:8001/docs
python3 -m pytest tests -q                                   # prediction service tests
```

Backend (Java 21, from `code/backend/`; the wrapper downloads Maven the first time):

```bash
./mvnw spring-boot:run      # portal at http://127.0.0.1:8000/
./mvnw test                 # history API tests against the database, gateway tests
```

`POST /v1/routes/rank` takes the payment (corridor, beneficiary bank, client type, amount, priority, release time, deadline flag). The backend simulates every route, has the prediction service score the ones the payment can use, and returns them ranked with the simulator estimate, the predicted time and cost, the model score and the v1 heuristic score. Routes the payment can't use are listed with their reasons. Other systems only call the backend. Use `--train-until all` to train the production model on all history.

The Historical Cases tab and the similar-cases panel read the backend's `/v1/history/...` endpoints (see the table below). Open the portal from the backend at `http://127.0.0.1:8000/`. If you open `corridoriq.html` from disk or another local server instead, it calls `http://127.0.0.1:8000`; add `?api=<url>` to point it elsewhere.

The Java backend can generate the same kind of database (`GenerateHistory`, see `code/README.md`) with the same options. Models trained on a Java-generated history score the same as on the Python one: LightGBM picks the best route for 94.4% of held-out payments, against 94.5%.

`--payments`, `--start`, `--end` and `--seed` control volume, window and reproducibility. The same seed always gives the same database. At 40,000 payments the file is about 65 MB; it scales roughly linearly.

## What is in the database

| Table | Rows | Holds |
| --- | ---: | --- |
| `payment` | 40,000 | One settled payment: client, corridor, amount, charges, purpose, priority, route used, fees, FX, outcome, time to credit |
| `payment_hop` | 151,932 | gpi-style timeline: each bank's arrival, start and finish, fee, status code, and any hold with its reason |
| `route_candidate` | 188,740 | Every route that was available for each payment: estimate at release and realised outcome, plus a ranking label |
| `incident` | 3,753 | Ops annotations on delays, repairs and returns (cause code and note) |
| `client` | 4,820 | 4,800 retail senders and 20 corporate or FI clients of the ordering bank |
| `fx_rate_daily` | 5,165 | Daily mid rates for JPY, INR, AED, SAR and EUR |
| `holiday` | 613 | Bank holidays for the nine countries on the network, 2023 to 2027 |
| `bank`, `corridor`, `route_template`, `route_template_hop`, `corridor_route`, `beneficiary_bank`, `bank_closure`, `country`, `currency`, `cause_code` | small | Reference data |
| `meta` | 11 | Generator version, seed, window and label definition |

Views: `v_case`, `v_route_performance`, `v_incident`, `v_corridor_monthly`, `v_history_window`, `v_corridor_overview`, `v_route_performance_18m`, `v_fx_corridor`, `v_ranking_training`.

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

## Which view, query and endpoint feeds which panel

The joins and aggregates live in views (`schema.sql`). `historical_cases.sql` holds named queries (`-- name: ...`) that only filter, order and page those views; the Java backend (`code/backend/src/main/java/com/corridoriq/backend/history/HistoryRepository.java`) loads them by name. All endpoints are `GET` under `/v1/history/corridors`.

| Portal panel | View | Named query | Endpoint |
| --- | --- | --- | --- |
| Corridor picker, "test data to" date | `v_corridor_overview`, `v_history_window` | `corridors`, `history_window` | `/v1/history/corridors` |
| Route performance, last 18 months (cases, median, P90, cost, STP, low sample) | `v_route_performance_18m` | `route_performance` | `.../{corridor_id}/route-performance` |
| FX rate, last 90 days | `v_fx_corridor` | `fx_series` (volatility computed in the API) | `.../{corridor_id}/fx?days=90` |
| Annotated incidents, with cause filter | `v_incident` | `incidents`, `incident_count`, `incident_categories` | `.../{corridor_id}/incidents?category=&limit=&offset=` |
| All cases, with route and outcome filters | `v_case` | `cases`, `case_count` | `.../{corridor_id}/cases?route=&outcome=&limit=&offset=` |
| Similar historical cases (route tab) | `v_case`, `v_route_performance_18m` | `similar_cases`, `route_summary` | `.../{corridor_id}/similar-cases?amount_usd=&client_type=&value_date=&template_id=` |
| Banking calendar (route tab) | `holiday`, plus `country.weekend_days` and `bank_closure` | `holidays` | not served yet |
| Model training | `v_ranking_training` | | |

The 18-month window ends at the newest value date in the data, not today, so a fixed extract always reads the same. After changing a view, run `ApplyViews` (from `code/backend/`: `./mvnw -q compile spring-boot:run -Dspring-boot.run.main-class=com.corridoriq.backend.generator.ApplyViews`) to recreate the views in an existing database without regenerating it.

## Model readiness check

Results from `python3 -m prediction_service.train` (the hold-out scores it saves in `metadata.json`): trained on payments before 1 April 2026 and tested on 9,665 payments after it.

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
| 1B Route engine and rules | Route engine reading reference data from SQLite; rules with reason codes; calendar-conflict rule | In progress: the engine is Java (`RouteSimulator`, `RouteEstimator`) and serves ranking, with ineligibility reasons and the calendar-conflict flag; its reference data is still in `Reference.java`, not read from SQLite |
| 1C Prediction service | v1 heuristic score, plus LightGBM ranker and time/cost regressors trained from `v_ranking_training`, with P50/P90 and score explanations. Runs in shadow until real data replaces the synthetic history | In progress: API, ranker and regressors in `code/prediction_service/`; P50/P90 and explanations to do |
| 1D Console | Route tab on the engine API; Historical Cases tab and similar cases served from SQLite | In progress: Historical Cases tab and similar cases served by `/v1/history`; Route tab still runs the in-browser engine |
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
