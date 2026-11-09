# CorridorIQ by example: how the engine and the models work together

When a payment is ranked, three parts do three different jobs:

| Part | Where | Job |
| --- | --- | --- |
| **Engine** | Java, `backend/.../engine/` | Applies the rules (which routes the payment may use) and the timetable (fees, FX margins, cut-offs, weekends, holidays). It turns the payment into numbers. Same payment, same numbers, every time. No learning. |
| **Models** | Python, `prediction_service/` | Learned from history how reality differs from the timetable. They score each allowed route and predict its real time and cost. |
| **Ranking** | Java, `backend/.../prediction/RouteRanker.java` | Asks the models for scores, sorts the routes by them, and builds the answer. |

In short: **the engine decides which routes may be considered, the model decides their order, and Java builds the answer.**

Every number below is real. The examples were run against the backend and the saved models (`lgbm-20261101T175656Z`, trained on payments before 1 April 2026). Retraining changes the model scores; the engine's numbers change only if its code or reference data changes.

---

## Templates and routes

The **corridor** is fixed by the payment: money to India travels on USD-INR, whatever happens. What CorridorIQ chooses is the **route**: the chain of banks the money passes through on the way.

### A template is a recipe

A **template** describes one way of moving money, once, in `Reference.java`, so it can be reused for any corridor and any beneficiary bank. Each template holds:

- **the chain of banks**, with placeholders, e.g. Express: `ORIG → NY1 → CLR → BENE`
- **the fee** at each bank, e.g. $30, $18, $12, $0
- **the FX margin**, standard and large-ticket, e.g. 38 and 15 bps
- **the processing hours** at each bank
- **the rules:** a per-transfer cap, retail clients only, the hop that waits for the Thursday batch
- **the expected straight-through rate**

There are six:

| Template | Name | Goes via | In short |
| --- | --- | --- | --- |
| EXP | Express gpi | New York direct | Fewest hops; highest FX margin |
| ASIA | Asia hub | Singapore | Asia-session FX, strong in JPY and INR |
| LDN | Europe hub | London | Strong in EUR and AED |
| GULF | Gulf hub | Manama | Tightest FX for large amounts; Friday–Saturday weekend |
| PAY | Payout partner | prefunded local account | Instant payout; retail only, up to $5,000 |
| ECO | Economy batch | weekly netting, Singapore | Cheapest and slowest; waits for Thursday's batch; up to $25,000 |

Without templates, the same route would have to be described again for every country. "Express" works the same way to Japan, India, the UAE, Saudi Arabia and Germany; only the last two banks change, and `CLR` and `BENE` stand for those.

### A route is the recipe filled in for one payment

A **route** is a template with its placeholders replaced by real banks (`RouteSimulator.resolveHops`): `CLR` by the corridor's clearing bank, `BENE` by the payment's beneficiary bank.

| Template EXP | becomes this route |
| --- | --- |
| for USD-JPY to JP_B1 | ORIG → NY1 → **JP_CLR** (Tokyo) → **JP_B1** (Kyoto) |
| for USD-INR to IN_B2 | ORIG → NY1 → **IN_CLR** (Mumbai) → **IN_B2** (Kochi) |

The same template gives different routes, with different banks, calendars, cut-offs and times, depending on where the money is going. That is why the engine simulates each route separately for every payment.

Two more things:

- **Not every corridor offers every template.** The `corridor_route` table lists them: USD-JPY and USD-INR have all six, USD-AED four, USD-SAR and USD-EUR three.
- **A route is named by its template.** In the data and the API, a payment's routes are identified by `template_id`, so "route GULF" in an answer means "template GULF, filled in for this payment". That is why the two words often seem interchangeable.

---

## How one request flows in production

```
caller (portal, payments system)
   │  POST /v1/routes/rank  {corridor, bank, client type, amount, priority, release time, deadline}
   ▼
Java backend :8000 ─ RouteRanker.rank()
   1. validate        corridor exists? bank on that corridor? date inside the holiday calendar?   → else 422
   2. engine          RouteEstimator.estimate(): every route of the corridor, bank by bank
                      → rules (allowed or not, and why) + 12 numbers per route
   3. ask the model   POST /v1/routes/score  {payment numbers, one row per ALLOWED route}  ─────┐
                                                                                                   ▼
                                                              Python scoring service :8001 ─ predictor.py
                                                              3 LightGBM models, loaded at start-up:
                                                              ranker score, predicted hours, predicted cost
   4. rank            sort allowed routes by model score (highest first)            ◄─────────────┘
   5. answer          ranked routes, then the routes not allowed with their reasons
```

In the code (`RouteRanker.java`): validation is `validate()`, the engine is called at line 57, the model at line 65, the sort is lines 69–70, and the answer is built in lines 72–80.

**At start-up:** the backend loads the banking calendar (holidays and bank closures) from the history database; the routes, banks, fees and cut-offs are in `Reference.java`. The scoring service loads the three model files and `metadata.json` from `prediction_service/artifacts/`.

### Reading the tables below

| Column | From | Meaning |
| --- | --- | --- |
| est h | engine | Hours to credit by the timetable, with every bank's weekends, holidays and cut-offs |
| naive h | engine | Processing hours only, ignoring foreign calendars: what operators estimated before CorridorIQ |
| closed d | engine | Days the payment sits at a closed bank on the way |
| conflict | engine | 1 = a hub bank is closed today or tomorrow while New York is open |
| quote $ | engine | Fees + amount × FX margin, from the bank contracts in `Reference.java` |
| pred h, pred $ | **model** | What the models expect really to happen |
| score | **model** | The ranker's score. Higher is better. Only comparable within one payment |

---

## Example 1 — $30,000 on a Friday: the rules remove routes before the model sees them

**Payment:** USD-JPY to Shirakawa Shinkin Bank (Kyoto), retail client, **$30,000**, priority 60, released **Friday 24 April 2026, 15:30** New York time.

**Step 1–2, the engine.** Two routes are over their per-transfer caps, so they are not allowed. The other four are walked bank by bank. Japan is closed on Saturday and Sunday, so every bank route waits for Monday:

- **EXP** (New York direct): reaches Tokyo Saturday 06:30, waits until Monday 09:00 → **56.0 h**
- **GULF** (Manama): reaches Bahrain on Saturday (Gulf weekend), waits 31 h, then Tokyo on Sunday → **58.5 h**
- **ASIA** (Singapore) and **LDN** (London): weekends and cut-offs → 78.5 h and 81.5 h

**Step 3, what the model receives** (`POST /v1/routes/score`). Only the four allowed routes are sent:

```json
{
  "payment": {"corridor_id": "USD-JPY", "bene_bank_id": "JP_B1", "client_type": "Retail", "amount_usd": 30000.0,
              "priority": 60, "release_hour_et": 15.5, "release_weekday": 5, "day_of_month": 24, "has_deadline": 0},
  "routes": [
    {"template_id": "EXP", "est_hours": 56.0, "naive_hours": 5.5, "est_cost_usd": 174.0, "fee_total_usd": 60.0,
     "fx_bps": 38.0, "hop_count": 4, "intermediaries": 2, "est_nonbusiness_days": 2, "calendar_conflict": 0,
     "cutoff_slack_hours": 0.5, "max_risk": 1, "stp_rate_nominal": 0.972},
    {"template_id": "ASIA", ...}, {"template_id": "LDN", ...}, {"template_id": "GULF", ...}
  ]
}
```

**Step 4–5, the model's scores and the answer:**

| Rank | Route | est h | naive h | closed d | conflict | quote $ | pred h | pred $ | score |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **#1** | **GULF** | 58.5 | 14.5 | 2 | 1 | 94.00 | 59.9 | 94.74 | **1.164** |
| #2 | EXP | 56.0 | 5.5 | 2 | 0 | 174.00 | 56.6 | 173.99 | −0.180 |
| #3 | ASIA | 78.5 | 12.5 | 2 | 0 | 116.00 | 81.7 | 115.61 | −1.512 |
| #4 | LDN | 81.5 | 11.0 | 2 | 0 | 139.00 | 82.1 | 140.68 | −2.973 |
| − | PAY | 1.5 | | | | | | | not allowed: Above the $5,000 per-transfer cap |
| − | ECO | 320.5 | | | | | | | not allowed: Above the $25,000 per-transfer cap |

**Why GULF wins:** it takes about as long as Express (58.5 h against 56 h, both stuck behind the Japanese weekend) but costs **$80 less**, because Gulf Crescent's FX margin is 16 bps against Express's 38 bps. On $30,000 that difference matters. The fastest route of all, the payout partner (1.5 h), was never an option: the engine ruled it out, so the model never saw it.

**Three ways to order these routes.** The engine produces numbers but does not rank. Here is what three possible orderings would give:

| Ordering | How it is made | Order |
| --- | --- | --- |
| Fastest first | The engine's `est_hours` alone | EXP (56.0) → GULF (58.5) → ASIA (78.5) → LDN (81.5) |
| Simple formula (`heuristic_score`) | The engine's time and cost, weighted by the priority (60% time, 40% cost); no learning | **GULF** (−0.070) → EXP (−0.400) → ASIA (−0.677) → LDN (−0.854) |
| **The model** (`model_score`) | Learned from history; **this is the one Java sorts by** | **GULF** (1.164) → EXP (−0.180) → ASIA (−1.512) → LDN (−2.973) |

Sorted by time alone, Express would come first. The simple formula and the model agree here: GULF is only 2.5 hours slower but $80 cheaper. The formula's score is computed in Python next to the model's and returned for comparison only; it never decides the order. For when the two disagree, see [The model and the simple formula](#the-model-and-the-simple-formula).

**What lines 65–80 of `RouteRanker.java` did here:**

| Lines | Code | Here |
| --- | --- | --- |
| 65–66 | `scorer.score(new ScoreRequest(context(...), eligible...features))` | Sent the payment numbers and 4 route rows to Python |
| 67 | `scoresByTemplate(scored, eligible)` | Got 4 scores back and checked they were for exactly EXP, ASIA, LDN and GULF |
| 69–70 | `ranked.sort(...modelScore()).reversed())` | Sorted: GULF 1.164, EXP −0.180, ASIA −1.512, LDN −2.973 |
| 72–75 | first loop | Added them as #1 to #4, with the engine's estimate and the model's outputs |
| 76–80 | second loop | Added PAY and ECO at the end, with no rank and their reasons |

---

## Example 2 — the same payment at $4,000: every route allowed

Same Friday, same bank, priority 60, but **$4,000**. Now nothing is over a cap, so all six routes go to the model.

| Rank | Route | est h | quote $ | pred h | score |
| ---: | --- | ---: | ---: | ---: | ---: |
| **#1** | **PAY** | 1.5 | 25.00 | 1.7 | **5.956** |
| #2 | ECO | 320.5 | 8.00 | 319.8 | 1.664 |
| #3 | GULF | 58.5 | 52.40 | 59.8 | −0.130 |
| #4 | EXP | 56.0 | 75.20 | 56.5 | −2.090 |
| #5 | ASIA | 78.5 | 58.80 | 81.7 | −2.200 |
| #6 | LDN | 81.5 | 68.80 | 82.0 | −3.595 |

**What changed:** the payout partner is allowed again, and it is both fast and cheap. Its last step is an instant local payment that ignores bank hours, so it pays out even on a Japanese Saturday. It wins clearly.

**Worth noticing:** the economy route comes second although it takes 320 hours. It is waiting for Singapore's Thursday netting batch and then hits Japan's Golden Week. At priority 60, cost still counts for 40%, and at $8.00 it is by far the cheapest. The simple formula (`heuristic_score`) also puts it second. A client who cares about speed would set a higher priority (see Example 3).

---

## Example 3 — the client's priority decides between cheap and fast

**Payment:** USD-INR to Malabar Coast Bank (Kochi), retail, **$20,000**, released **Monday 20 April 2026, 10:00**. Sent twice, once at **priority 0** (cheapest) and once at **priority 100** (fastest). The engine's numbers are identical both times; only the priority, which the model reads, is different.

| Route | est h | quote $ | score at priority 0 | rank | score at priority 100 | rank |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ECO | 185.5 | 24.00 | **4.758** | **#1** | −6.787 | #5 |
| GULF | 43.5 | 78.00 | −0.249 | #2 | −2.300 | #4 |
| ASIA | 20.0 | 94.00 | −2.111 | #3 | 1.539 | #2 |
| LDN | 42.5 | 112.00 | −3.966 | #4 | −1.387 | #3 |
| EXP | 17.0 | 136.00 | −5.978 | #5 | **4.002** | **#1** |
| PAY | 1.5 | — | not allowed (over $5,000) | − | not allowed | − |

**What it shows:** the same routes, with the same engine numbers, are ranked in nearly opposite order. The cost-minded client gets the economy batch (8 days, $24). The rushed client gets Express (17 hours, $136). The engine supplies the facts; the model weighs them by what this client wants.

---

## Example 4 — a large ticket from a financial institution

**Payment:** USD-AED to Creek Merchant Bank (Dubai), **FI client**, **$900,000**, priority 50, released Tuesday 21 April 2026, 11:00.

| Rank | Route | est h | fx bps | quote $ | pred $ | score |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| **#1** | **GULF** | 44.0 | 6 | 581.00 | 593.28 | **1.847** |
| #2 | EXP | 17.5 | 15 | 1,395.00 | 1,304.51 | −0.691 |
| #3 | LDN | 43.0 | 11 | 1,041.00 | 985.18 | −2.061 |
| − | PAY | 1.5 | | | | not allowed: Above the $5,000 per-transfer cap; Available to retail clients only |

**What it shows:**
- From $250,000, every route uses its tighter "large ticket" FX margin. The engine applies that rule, so Gulf's margin drops to 6 bps and the route saves about **$800** against Express.
- The payout partner fails two rules at once, and both reasons are listed.
- The simple formula gives GULF and EXP the same score (−0.500): GULF is cheapest but slowest, EXP the reverse, and at priority 50 the formula cannot separate them. The model, trained on what really happened, prefers GULF clearly.

---

## Example 5 — a holiday changes the ranking

**Payment:** USD-JPY to Shirakawa Shinkin Bank, retail, $4,000, priority 60, released **Tuesday 28 April 2026, 15:30**. The next day, **Wednesday 29 April, is Showa Day** in Japan. The same payment a week earlier, Tuesday 21 April, has no holiday.

| Route | est h, 21 April | rank | est h, 28 April | rank |
| --- | ---: | ---: | ---: | ---: |
| PAY | 1.5 | #1 | 1.5 | #1 |
| ECO | 152.5 | #2 | 224.5 | #2 |
| EXP | **8.0** | **#3** | **32.0** | **#5** |
| GULF | 34.5 | #4 | 34.5 | #3 |
| ASIA | 30.5 | #5 | 33.5 | #4 |
| LDN | 33.5 | #6 | 33.5 | #6 |

**What it shows:** Express is the fastest bank route, so it reaches the Tokyo clearing bank on **Wednesday morning, Showa Day itself**, and waits 26.5 hours for Thursday. Its time goes from 8 to 32 hours and it drops from #3 to #5. The slower routes reach Japan after the holiday, so they barely change (GULF and LDN not at all).

The model was never told about Showa Day. The engine found it in the holiday calendar and turned it into numbers (`est_hours` 32.0, `est_nonbusiness_days` 1). The model reacted to those numbers. The naive estimate (5.5 h either way) would not have noticed anything.

---

## Example 6 — payments refused before the model is asked

| Payment | HTTP | Answer |
| --- | ---: | --- |
| USD-INR with bank `JP_B1` (a Japanese bank) | 422 | `JP_B1 is not a beneficiary bank on USD-INR; expected one of [IN_B1, IN_B2, IN_B3]` |
| Released 21 April **2028** | 422 | `Banking calendar only covers 2023-2027` |

**What it shows:** Java checks what the model cannot. A wrong bank would give wrong route numbers. A date beyond the holiday file would treat every 2028 holiday as a working day. In both cases the payment is refused with a reason, and the model is never called.

---

## Example 7 — when the scoring service is down

With the Python service stopped:

| Call | Answer |
| --- | --- |
| `POST /v1/routes/rank` | **503** `Cannot reach the prediction service at http://127.0.0.1:8001. Start it from the code folder: python3 -m uvicorn prediction_service.api:app --port 8001` |
| `GET /health` | `"status": "degraded"`, with `"prediction_service": {"status": "unavailable"}` |
| `GET /v1/history/corridors` | 200: the Historical Cases tab keeps working |

The backend never ranks without the model and never guesses. It says clearly what is missing.

---

## The model and the simple formula

Every answer carries two scores per route. The **model score** decides the order. The **simple formula** (`heuristic_score` in `features.py`, the "MVP 1 v1" score) is returned beside it for comparison: it weighs the engine's time and cost by the client's priority and has learned nothing.

They often agree, but not always:

| Example | Simple formula's order | Model's order |
| --- | --- | --- |
| 1 ($30,000, Friday) | GULF, EXP, ASIA, LDN | same |
| 2 ($4,000, Friday) | PAY, ECO, GULF, **ASIA, EXP**, LDN | PAY, ECO, GULF, **EXP, ASIA**, LDN |
| 3 (priority 0 and 100) | same as the model | ECO first at priority 0, EXP first at 100 |
| 4 ($900,000, FI) | **GULF and EXP tied** (−0.500 each) | GULF clearly ahead (1.847 against −0.691) |
| 5 (Showa Day) | PAY, ECO, GULF, ASIA, **LDN, EXP** | PAY, ECO, GULF, ASIA, **EXP, LDN** |
| 5 (the week before) | same as the model | PAY, ECO, EXP, GULF, ASIA, LDN |

**Where they differ is the model's value.** The formula only knows the timetable. The model has also learned how each bank really behaves: which ones run slow, which ones raise information requests, how month-end backlogs and large-amount screening slow things down. Over all 9,665 held-out payments, the formula picks the route that really turned out best **91.6%** of the time, the model **94.5%**, and the operators before CorridorIQ **80.1%**.

---

## Around the requests: running it in production

| When | What happens | Who or what |
| --- | --- | --- |
| **Every payment** | Steps 1–5 above, in about 30 ms | Backend and scoring service |
| **When a bank changes a fee, FX margin, cut-off or route** | Update `Reference.java` and redeploy the backend. Until then every quote for that route is wrong, consistently (see `ReferenceDataLessonsTest`, lesson 5) | Operations, then a developer |
| **When a bank announces a closure** | Add a row to the `bank_closure` table | Operations |
| **Once a year** | Extend the holiday calendar, and confirm the moving holidays (Eid, Easter): the file marks Islamic dates as estimates | Operations |
| **After new history arrives** | Load it into the same tables, run `python3 -m prediction_service.train` (check the `holdout` scores in `metadata.json`), then `--train-until all` for the production models, and restart the scoring service. The backend needs no change | ML |
| **Continuously** | Compare predicted with real time and cost. A model correction that keeps growing for one bank or route usually means its reference data is out of date | Monitoring |

### What is still demo, not production

- **The data is synthetic.** The banks, clients and outcomes are generated (`GenerateHistory`). A real deployment loads the bank's own payment history into the same tables. Real history only shows what happened on the route that was used, so the engine (calibrated on real outcomes) has to estimate what the other routes would have done.
- **The portal's "Route a payment" tab** still runs its own copy of the engine in the browser and does not call `/v1/routes/rank`. Until it does, what it shows is not what the model receives.
- **Fees and routes live in code** (`Reference.java`). The plan (MVP 1B) is to read them from the database, so a fee change becomes a data change.

---

## Try it yourself

```bash
# The engine's side, no Python needed (from code/backend/)
./mvnw test -Dtest=RoutingWalkthroughTest      # Examples 1 and 2 step by step, with the exact JSON sent
./mvnw test -Dtest=ReferenceDataLessonsTest    # what happens when the reference data is wrong

# The whole chain: start both services (see README.md), then send any example, e.g. Example 1
curl -s localhost:8000/v1/routes/rank -H 'Content-Type: application/json' -d '{"corridor_id": "USD-JPY",
  "bene_bank_id": "JP_B1", "client_type": "Retail", "amount_usd": 30000, "priority": 60,
  "released_at": "2026-04-24T15:30:00-04:00", "has_deadline": false}'
```

The other examples' payments:

| Example | `corridor_id` | `bene_bank_id` | `client_type` | `amount_usd` | `priority` | `released_at` |
| --- | --- | --- | --- | ---: | ---: | --- |
| 2 | USD-JPY | JP_B1 | Retail | 4000 | 60 | 2026-04-24T15:30:00-04:00 |
| 3 | USD-INR | IN_B2 | Retail | 20000 | 0, then 100 | 2026-04-20T10:00:00-04:00 |
| 4 | USD-AED | AE_B3 | FI | 900000 | 50 | 2026-04-21T11:00:00-04:00 |
| 5 | USD-JPY | JP_B1 | Retail | 4000 | 60 | 2026-04-28T15:30:00-04:00 and 2026-04-21T15:30:00-04:00 |
| 6 | USD-INR / USD-JPY | JP_B1 | Retail | 4000 | 60 | 2026-04-21T15:30:00-04:00 / 2028-04-21T15:30:00-04:00 |
