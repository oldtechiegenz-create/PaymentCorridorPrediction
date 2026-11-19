# CorridorIQ by example: how the engine and the models work together

When a payment is ranked, three parts do three different jobs:

| Part | Where | Job |
| --- | --- | --- |
| **Engine** | Java, `backend/.../engine/` | Applies the rules (which routes the payment may use) and the timetable (fees, FX margins, cut-offs, weekends, holidays). It turns the payment into numbers. Same payment, same numbers, every time. No learning. |
| **Models** | Python, `prediction_service/` | Learned from history how reality differs from the timetable. They score each allowed route and predict its real time and cost. |
| **Ranking** | Java, `backend/.../prediction/RouteRanker.java` | Asks the models for scores, sorts the routes by them, and builds the answer. |

In short: **the engine decides which routes may be considered, the model decides their order, and Java builds the answer.**

Every number below is real. The examples were run against the backend and the saved models (`lgbm-20261101T175656Z`, trained on payments before 1 April 2026). Retraining changes the model scores; the engine's numbers change only if its code or reference data changes.

## Contents

- [Templates and routes](#templates-and-routes)
- [The whole sequence, step by step](#the-whole-sequence-step-by-step)
  - [Easy-to-make mistakes about this sequence](#easy-to-make-mistakes-about-this-sequence)
- [How one request flows in production](#how-one-request-flows-in-production)
- [The 12 numbers, explained with a $30,000 payment to India](#the-12-numbers-explained-with-a-30000-payment-to-india)
  - [The 12 numbers for Express, one by one](#the-12-numbers-for-express-one-by-one)
- [Example 1 — $30,000 on a Friday: the rules remove routes before the model sees them](#example-1--30000-on-a-friday-the-rules-remove-routes-before-the-model-sees-them)
- [Example 2 — the same payment at $4,000: every route allowed](#example-2--the-same-payment-at-4000-every-route-allowed)
- [Example 3 — the client's priority decides between cheap and fast](#example-3--the-clients-priority-decides-between-cheap-and-fast)
- [Example 4 — a large ticket from a financial institution](#example-4--a-large-ticket-from-a-financial-institution)
- [Example 5 — a holiday changes the ranking](#example-5--a-holiday-changes-the-ranking)
- [Example 6 — payments refused before the model is asked](#example-6--payments-refused-before-the-model-is-asked)
- [Example 7 — when the scoring service is down](#example-7--when-the-scoring-service-is-down)
- [The model and the simple formula](#the-model-and-the-simple-formula)
- [Around the requests: running it in production](#around-the-requests-running-it-in-production)
  - [Changing the configuration: adding a beneficiary bank](#changing-the-configuration-adding-a-beneficiary-bank)
- [Try it yourself](#try-it-yourself)

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

## The whole sequence, step by step

What happens to one payment, in order:

| # | Step | Who | Where the information comes from |
| ---: | --- | --- | --- |
| 1 | **The API is called** with the payment: corridor, beneficiary bank, client type (Retail or FI), amount, priority, release time, deadline flag | The caller | The request. There is no "source bank": every payment starts at the same ordering bank, Lakeshore Community Bank in Toledo (`ORIG`) |
| 2 | **Java checks it**: does the corridor exist, does the bank belong to it, is the release date inside the holiday calendar? If not, it answers 422 and stops | Java | Corridors and banks: `Reference.java`. Holiday years: the database's `holiday` table |
| 3 | **Java gets the corridor's templates**: 6 for USD-INR, 3 for USD-SAR | Java | `Reference.java` |
| 4 | **Each template becomes one route**: `CLR` and `BENE` are filled in with the corridor's clearing bank and the payment's beneficiary bank | Engine | `Reference.java` |
| 5 | **The engine removes** the routes this payment may not use (over a cap, retail only), and keeps the reason | Engine | Caps and rules: `Reference.java` |
| 6 | **The engine computes 12 numbers** for every route, by walking it bank by bank, plus a few numbers about the payment itself | Engine | Fees, FX margins, cut-offs, processing hours: `Reference.java`. Holidays and bank closures: the database |
| 7 | **The model scores** each remaining route. This is the only machine-learning step | Python | The three model files in `prediction_service/artifacts/` |
| 8 | **Java sorts by the model's score** (highest first), numbers the routes #1, #2, …, and adds the removed routes at the end with their reasons | Java | The model's scores |

Where things live today:

| Information | Lives in | Notes |
| --- | --- | --- |
| Corridors, which templates each offers, banks, fees, FX margins, cut-offs, caps, processing hours | `backend/.../engine/Reference.java` (Java code) | The generator copies them into database tables (`corridor`, `corridor_route`, `bank`, …), which the Historical Cases tab reads. **The ranking engine reads the Java code, not those tables.** The MVP 1B plan is to read them from the database, so a fee change becomes a data change |
| Holidays | The database's `holiday` table (loaded when the backend starts); `resources/reference/holidays.csv` for generating new databases | Covers 2023–2027 |
| Ad hoc bank closures | The database's `bank_closure` table | Only the closures someone has entered |
| The trained models | `prediction_service/artifacts/` | Loaded when the scoring service starts |

### Easy-to-make mistakes about this sequence

| It is easy to think… | In fact… |
| --- | --- |
| The corridors come from the database | For ranking, they come from `Reference.java`. The `corridor` table is a copy for the Historical Cases tab |
| The API receives a source bank and a beneficiary bank | Only the beneficiary bank. The source is always the ordering bank, `ORIG` |
| The corridor is "filtered" | It is looked up by its ID and checked. A wrong corridor or bank is refused with a 422 before anything else runs |
| A template is one fixed path | A template is a recipe; each payment fills it in with its own banks, so the same template gives different routes for different corridors and banks |
| The Java engine puts the routes in an order of preference | **The engine never orders.** It only removes routes and computes numbers. It returns the routes in the corridor's configured order (EXP, ASIA, LDN, GULF, PAY, ECO), which is just the order they are listed in |
| The model receives the routes | It receives **numbers** about the routes (the 12 below), not the bank chains. It never sees `ORIG → NY1 → …`, only what the engine worked out from it |
| The model may change Java's order | Java has no order of its own. **The model's score is the only thing that decides the order.** What can differ from the model's order is a simpler ordering, such as "fastest first" or the simple formula, and neither of those is used to decide anything (see [The model and the simple formula](#the-model-and-the-simple-formula)) |

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

The next section explains each of the engine's numbers in detail, with a worked example.

---

## The 12 numbers, explained with a $30,000 payment to India

For every route, the engine produces 12 numbers. They are the main thing the model reads, so they are worth understanding one by one.

**The payment:** USD-INR to Malabar Coast Bank in Kochi (`IN_B2`), retail client, **$30,000**, priority 60, released **Friday 24 April 2026, 10:00 New York time** (19:30 in India).

This weekend was chosen because two calendar rules show up:

- **Saturday 25 April is the 4th Saturday of the month.** Indian banks close on the 2nd and 4th Saturdays (their only regular weekend day is otherwise Sunday).
- **Bahrain works Sunday to Thursday.** Its weekend is Friday and Saturday, so the Gulf hub is shut on the Friday the payment is sent.

### How the engine walks the Express route

Express for this payment is `ORIG → NY1 → IN_CLR (Mumbai) → IN_B2 (Kochi)`. The engine moves the payment bank by bank, in each bank's own time zone:

```
ORIG    Fri 10:00 New York → done 10:30                         0.5 h processing
NY1     Fri 10:30 New York → done 12:00                         1.5 h processing
IN_CLR  arrives Fri 21:30 India, after its 15:00 cut-off     → waits until Sat 09:00   (11.5 h)
        Sat 25 is the 4th Saturday, banks closed             → waits until Sun 09:00   (24 h)
        Sun 26 is Sunday, banks closed                       → waits until Mon 09:00   (24 h)
        Mon 09:00 → done 11:00                                  2 h processing
IN_B2   Mon 11:00 → credited 12:30 India                        1.5 h processing
```

### The 12 numbers for Express, one by one

| # | Number | Value | What it means and how it is worked out |
| ---: | --- | ---: | --- |
| | **Time** | | |
| 1 | `est_hours` | **65.0** | The walk above: from Friday 10:00 New York to Monday 12:30 India. It uses every bank's time zone, cut-off, weekend and holidays |
| 2 | `naive_hours` | **5.5** | Processing time only: 0.5 + 1.5 + 2 + 1.5. This is what an operator estimated before CorridorIQ, blind to India's cut-off and weekend. Here it is wrong by 59.5 hours. (It only adds a wait if the payment misses the US cut-off or the US is closed) |
| 3 | `est_nonbusiness_days` | **2** | Closed days the payment sat through on the way: Saturday (4th Saturday) and Sunday |
| 4 | `calendar_conflict` | **0** | 1 if a **hub** bank (an intermediary) is closed today or tomorrow while New York is open. NY1 is open on Friday; on Saturday it is closed, but so is New York, so that is not a conflict |
| 5 | `cutoff_slack_hours` | **6.0** | Hours until the ordering bank's 16:00 cut-off: 16:00 − 10:00. A negative number means the payment is already too late for today |
| | **Cost** | | |
| 6 | `fee_total_usd` | **60.00** | The fee at each bank on the route: $30 + $18 + $12 + $0 (from the template) |
| 7 | `fx_bps` | **38** | Express's FX margin in basis points (1 bps = 0.01%). The tighter "large ticket" margin, 15 bps, only applies from $250,000 |
| 8 | `est_cost_usd` | **174.00** | Fees + amount × margin: 60 + 30,000 × 38 / 10,000 = 60 + 114 |
| | **Structure and risk** | | |
| 9 | `hop_count` | **4** | Banks on the route |
| 10 | `intermediaries` | **2** | Banks between the sender and the beneficiary bank: NY1 and the Mumbai clearing bank |
| 11 | `max_risk` | **2** | The highest jurisdiction risk on the route, 1 (low) to 3 (high): the New York banks are 1, the Indian banks are 2, and the India corridor itself is 2 |
| 12 | `stp_rate_nominal` | **0.972** | The template's expected share of payments that go through with no manual repair ("straight-through processing") |

All 12 are rounded to 2 decimals, exactly as they are stored in the training data.

### All six routes side by side

| Number | EXP | ASIA | LDN | GULF | PAY | ECO |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 `est_hours` | 65.0 | 68.0 | 90.5 | 67.5 | 1.5 | 257.5 |
| 2 `naive_hours` | 5.5 | 12.5 | 11.0 | 14.5 | 1.5 | 87.0 |
| 3 `est_nonbusiness_days` | 2 | 2 | 2 | **3** | 0 | 3 |
| 4 `calendar_conflict` | 0 | 0 | 0 | **1** | 0 | 0 |
| 5 `cutoff_slack_hours` | 6.0 | 6.0 | 6.0 | 6.0 | 6.0 | 6.0 |
| 6 `fee_total_usd` | 60 | 50 | 58 | 46 | 5 | 4 |
| 7 `fx_bps` | 38 | 22 | 27 | 16 | 50 | 10 |
| 8 `est_cost_usd` | 174 | 116 | 139 | **94** | 155 | 34 |
| 9 `hop_count` | 4 | 5 | 5 | 5 | 3 | 5 |
| 10 `intermediaries` | 2 | 3 | 3 | 3 | 1 | 3 |
| 11 `max_risk` | 2 | 2 | 2 | 2 | 2 | 2 |
| 12 `stp_rate_nominal` | 0.972 | 0.948 | 0.955 | 0.931 | 0.986 | 0.962 |
| **Sent to the model?** | yes | yes | yes | yes | **no**: over the $5,000 cap | **no**: over the $25,000 cap |

What the numbers say:

- **GULF** is the cheapest allowed route ($94) and almost as fast as Express (67.5 h against 65 h). But it has a **calendar conflict** (Bahrain is shut on Friday) and **3 closed days**: Bahrain's Friday and Saturday, then India's Sunday.
- **ASIA** goes through Singapore, which is closed on Saturday and Sunday, and reaches Mumbai on Monday morning: 68 h.
- **LDN** is the slowest bank route (90.5 h): London is already past its 16:00 cut-off on Friday, and on Monday the payment reaches Mumbai after its 15:00 cut-off, so it waits again.
- **The cut-off slack is the same for every route**, because every route starts at the same ordering bank.
- **PAY and ECO** get all 12 numbers too, but they are over their caps, so they are never sent to the model. PAY would have taken 1.5 hours; ECO, waiting for Singapore's Thursday batch, 257.5.

### What the model receives

For each of the 4 allowed routes, the 12 numbers above. Once for the payment:

| Payment number | Value | Notes |
| --- | --- | --- |
| `amount_usd` | 30000 | The model uses its logarithm (`log_amount`), because amounts range from $50 to $5 million |
| `client_type` | Retail | The model uses 0 for retail, 1 for an FI client (`is_fi`) |
| `priority` | 60 | 0 = cheapest preferred, 100 = fastest |
| `release_hour_et` | 10.0 | Hour of release in New York, with minutes as a fraction (13:30 → 13.5) |
| `release_weekday` | 5 | Friday (0 = Sunday … 6 = Saturday), on the US date |
| `day_of_month` | 24 | Month-end payments behave differently |
| `has_deadline` | 0 | 1 if the client gave an arrive-by date |
| route, corridor, bank | EXP…, USD-INR, IN_B2 | As **category codes**: fixed integer codes from lists saved with the models |

So the model reads **22 inputs for each route**: 12 from the engine, 7 about the payment, and 3 categories. The derived ones (`log_amount`, `is_fi` and the category codes) are computed in Python by `features.py`, the same way for training and for scoring.

### What the model answered

Real result from the saved models:

| Rank | Route | est h | pred h | quote $ | pred $ | model score | simple formula |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **#1** | **GULF** | 67.5 | 68.1 | 94.00 | 94.72 | **0.884** | −0.068 |
| #2 | EXP | 65.0 | 65.4 | 174.00 | 173.97 | −0.696 | −0.400 |
| #3 | ASIA | 68.0 | **72.5** | 116.00 | 115.58 | −1.151 | −0.218 |
| #4 | LDN | 90.5 | 91.0 | 139.00 | 140.65 | −4.526 | −0.854 |
| − | PAY | | | | | | not allowed: Above the $5,000 per-transfer cap |
| − | ECO | | | | | | not allowed: Above the $25,000 per-transfer cap |

- **GULF wins**, as in Example 1: about as fast as Express and $80 cheaper.
- **The model and the simple formula disagree on #2.** The formula, which only knows the timetable, puts ASIA second. The model puts EXP second, and the reason is visible in its time prediction: it expects ASIA to take **72.5 hours, not the 68 the timetable says**. It learned from history that this route runs slower than planned for India, and so it trusts it less.

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

### Changing the configuration: adding a beneficiary bank

Say the USD-JPY corridor gets a fourth beneficiary bank, `JP_B4`. Three steps are needed, and the third is easy to forget.

**Step 1: describe the bank and add it to the corridor, in `Reference.java`** (two places):

```java
// 1. Define the bank, next to JP_B1..JP_B3 (around line 220)
bank("JP_B4", "Seto Inland Bank", "SETOJPJH", "Hiroshima", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 1.0);
//    id       name                BIC         city         country role             cut-off      risk speed
//                                                                                   (all-day, intermediary, non-bank flags: 0)

// 2. Add it to the corridor's banks (around line 234). The shares must still add up to 1.0
corridor("USD-JPY", ..., List.of(b("JP_B1", .40), b("JP_B2", .30), b("JP_B3", .15), b("JP_B4", .15)), ...);
```

- **The templates do not change.** Their `BENE` placeholder takes `JP_B4` automatically, so all six routes work for the new bank.
- The **share** only tells the generator how often synthetic payments go to each bank. The **speed** is the generator's hidden "real pace" of the bank; the engine never uses it. A test (`GeneratorHelpersTest`) checks that every corridor's shares add up to 1.

**Step 2: what happens if you stop here.** The engine would compute the routes for `JP_B4`, but the scoring service would refuse them, and Java would pass the refusal on as a 422:

```
Unknown bene_bank_id JP_B4; the models know ['AE_B1', ..., 'JP_B3', 'SA_B1', ...]
```

That is deliberate. The models read the bank as an **integer code** from a fixed list of 15 banks, and `JP_B4` is not on it. Without the check, the model would quietly read `JP_B4` as some other bank.

**Step 3: give the models history with the new bank, and retrain:**

1. Get payment history that includes `JP_B4`: regenerate it with `GenerateHistory`, or, with real data, load the bank's payments. This also writes `JP_B4` into the database's `beneficiary_bank` table, where training reads the bank list from.
2. Retrain: `python3 -m prediction_service.train`. The bank list grows to 16 and is saved with the models in `metadata.json`.
3. Restart the scoring service, and the backend for the `Reference.java` change.

**Also:** the portal's "Route a payment" tab has its own bank list in `corridoriq.html` (its browser copy of the engine), so it would need the same change until that tab calls the backend.

**Adding a whole new corridor or country is bigger:** the country (time zone, weekend days), its holidays in the calendar, its clearing bank, its beneficiary banks, the corridor with its templates, and then the same history-and-retraining step.

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
| The 12 numbers | USD-INR | IN_B2 | Retail | 30000 | 60 | 2026-04-24T10:00:00-04:00 |
