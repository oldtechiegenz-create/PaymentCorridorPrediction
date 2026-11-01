# CorridorIQ — ML Model Learnings & Walkthrough Notes

A personal reference document covering key concepts from the `train_baseline_walkthrough.ipynb` notebook.

---

## 1. What the Data Represents

- The dataset has **169,240 rows** across **40,000 payments**.
- Each row = **one candidate route for one payment** (not one payment per row).
- A payment typically has **4–6 eligible routes** (templates: EXP, ASIA, LDN, GULF, PAY, ECO).
- Only rows where `eligible = 1` are loaded — routes that were actually available for that payment.

---

## 2. Key Columns — What They Mean

### Identity & Payment Details
| Column | Meaning |
|--------|---------|
| `payment_id` | Identifies the payment — groups all candidate routes together |
| `corridor_id` | e.g. USD-INR, USD-JPY |
| `value_date` | Date the payment was made |
| `template_id` | The route used (EXP, ASIA, LDN, GULF, PAY, ECO) |
| `bene_bank_id` | Beneficiary (receiving) bank |
| `client_type` | Retail or FI (Financial Institution) |
| `amount_usd` | Payment amount in USD |
| `priority` | 0 = cheapest route preferred, 100 = fastest route preferred |

### Simulator Estimates (Known at Decision Time)
| Column | Meaning |
|--------|---------|
| `est_hours` | Calendar-aware estimated time to credit (hours) |
| `naive_hours` | Raw processing hours with no calendar awareness |
| `est_cost_usd` | Quoted total cost: correspondent fees + FX margin |
| `fee_total_usd` | Correspondent bank fees component only |
| `fx_bps` | FX spread in basis points |
| `est_nonbusiness_days` | Non-business days the route crosses |
| `calendar_conflict` | 1 = an intermediary is closed while US is open |
| `cutoff_slack_hours` | Hours until the ordering bank's cut-off |

### Route Structure & Risk
| Column | Meaning |
|--------|---------|
| `hop_count` | Number of banks in the chain |
| `intermediaries` | Number of non-ordering (middle) banks |
| `max_risk` | Highest jurisdiction risk score on the route (1–3) |
| `stp_rate_nominal` | Expected straight-through processing rate (0–1) |

### Actual Outcomes (Only Known After the Fact)
| Column | Meaning |
|--------|---------|
| `realized_hours` | How many hours the payment actually took |
| `realized_cost_usd` | What the payment actually cost |
| `realized_outcome` | Credited / Repaired / Returned |

### Labels (Used to Train the Model)
| Column | Meaning |
|--------|---------|
| `relevance` | 0–4 label. 4 = best route in hindsight, 0 = worst |
| `was_chosen` | 1 = ops actually picked this route, 0 = not chosen |
| `utility` | Underlying performance score used to derive `relevance` |

### Engineered Features (Added in Step 3)
| Column | Meaning |
|--------|---------|
| `log_amount` | `log(amount_usd)` — compresses skewed scale |
| `is_fi` | 1 if client_type = FI, else 0 |
| `tpl`, `corr`, `bene` | template_id, corridor_id, bene_bank_id re-encoded as categories |

---

## 3. Relevance vs Was_Chosen — They Are Independent

These two columns are often confused but mean completely different things:

- **`relevance`** = how good a route *would have been* in hindsight (based on actual outcomes). Assigned 0–4 across the candidates for a payment.
- **`was_chosen`** = which route ops *actually picked* at the time. Exactly one row per payment has `was_chosen = 1`.

**Key insight:** Ops could pick a `relevance=0` route (worst possible choice) and it would still have `was_chosen=1`. The whole point of the ML model is to close this gap.

Example for payment 16:
```
LDN:  relevance=0, was_chosen=1  ← ops picked the worst route!
EXP:  relevance=2, was_chosen=0
GULF: relevance=1, was_chosen=0
ECO:  relevance=3, was_chosen=0
ASIA: relevance=4, was_chosen=0  ← best route, not chosen
```

---

## 4. Relevance Label Distribution

Relevance is **rank-based within each payment** (from `meta.label_definition` in the database): routes are sorted by realised utility, best first, and labelled 4, 3, 2, 1. Every route after the 4th gets 0.

```
relevance
0    29,439   ← the 5th and 6th routes: 3,135 payments × 1 + 13,152 payments × 2
1    24,551   ← payments with 4+ routes
2    35,250   ← payments with 3+ routes (40,000 − 4,750 two-route payments)
3    40,000   ← every payment has at least 2 routes
4    40,000   ← every payment has exactly one best route
```

- **Why the imbalance?** It comes purely from how many eligible routes each payment has (2 to 6). A payment with only 2 routes gets labels 4 and 3 and nothing else. It is **not** an absolute "bad route" threshold.
- **Does this hurt the ML model?** No. LambdaRank learns from pairwise comparisons *within each payment group*, not from absolute label counts.

---

## 5. Why Estimated Values Exist When Realized Values Are Also in the Data

This is the most important conceptual point:

> **At decision time, realized values do not exist yet.**

```
9:00 AM  → Payment arrives. Ops must pick a route NOW.
           est_hours = 1.5h, est_cost = $7.90    ← KNOWN
           realized_hours = ???                   ← doesn't exist yet

Next day  → Payment credits.
           realized_hours = 2.18h                ← NOW we know
           realized_cost  = $7.90                ← NOW we know
```

- **Estimated values** are what the simulator provides at decision time — they're the inputs.
- **Realized values** are recorded after the payment settles — they're only used as the answer key to train the model historically.
- In live production use, the model only sees `est_hours`, `est_cost_usd`, and the other 20 features. It never sees `realized_hours` or `realized_cost_usd`.

---

## 6. How the Simulator Calculated Estimated Cost & Time

The simulator is a **separate rules-based system** — not ML, not statistics. It was built before any model was trained.

### Estimated Cost Formula (exact, from contracts):
```
est_cost_usd = fee_total_usd + (amount_usd × fx_bps / 10,000)
```

Real example — PAY route, payment_id=1, amount=$580:
```
= $5.00 + (580 × 50 / 10,000)
= $5.00 + $2.90
= $7.90  ✓
```

- **Fees** come from pre-agreed correspondent bank contracts.
- **FX spread** is a fixed rate per corridor — not estimated, it's contractual.

### Estimated Time (rules-based calendar engine):
```
est_hours = naive_hours + penalties for:
  - non-business days crossed (bank closed = payment sits idle)
  - calendar conflicts
  - cut-off time windows at each bank in the chain
```

Real example — ECO route (5 hops, crosses a weekend):
```
naive_hours          = 87h
est_nonbusiness_days = 2 (weekend)
est_hours            = 210.48h  ← 87h + waiting for banks to reopen
```

vs PAY route (3 hops, no weekend):
```
naive_hours          = 1.5h
est_nonbusiness_days = 0
est_hours            = 1.5h    ← no adjustment needed
```

---

## 7. The 22 Features Fed Into the ML Model

The realized values (`realized_hours`, `realized_cost_usd`) are **NOT** in the 22 features. They are only used as labels.

```python
FEATURES = [
    # Simulator time estimates
    "est_hours", "naive_hours",
    # Simulator cost estimates
    "est_cost_usd", "fee_total_usd", "fx_bps",
    # Route structure
    "hop_count", "intermediaries",
    # Calendar risk
    "est_nonbusiness_days", "calendar_conflict", "cutoff_slack_hours",
    # Risk & quality
    "max_risk", "stp_rate_nominal",
    # Payment context
    "priority", "log_amount", "is_fi",
    "release_hour_et", "release_weekday", "day_of_month", "has_deadline",
    # Entity IDs (categorical)
    "tpl", "corr", "bene",
]
```

### Feature Importance (from Step 9):
```
priority          1947  ← most important
est_hours         1864
est_cost_usd      1598
log_amount        1152
day_of_month       846
cutoff_slack_hours 592
```

The top 3 make intuitive sense. The remaining features add context the simulator ignores — *when* the payment was sent, *who* sent it, *which* corridor — patterns only visible in historical data.

---

## 8. Train/Test Split — Time-Based

**Never randomly shuffle** for time-series data — that leaks the future into training.

```
Train: value_date < 2026-04-01   → 30,335 payments (128,332 rows)
Test:  value_date ≥ 2026-04-01   →  9,665 payments  (40,908 rows)
```

The pandas row index numbers you see in test output (128332, 128333...) are the **original row positions** from the full dataframe — not payment IDs. Since the test set starts at row 128,332, pandas preserves that numbering.

---

## 9. train_groups — What It Is and Why It's Needed

```python
train_groups = tr.groupby("payment_id").size().values
# → [5, 6, 4, 3, 6, 5, ...]
```

- Each number = how many candidate routes that payment has in the training set.
- LightGBM needs this because the training data is one flat table of 128,332 rows with no inherent grouping.
- `train_groups` tells LightGBM: *"the next 5 rows are payment A, the next 6 rows are payment B..."*
- Without this, it would try to rank routes across all payments instead of within each payment.

---

## 10. The Heuristic Score — Why It Exists

The heuristic is the **"before ML" baseline** — what CorridorIQ would recommend using only the simulator's outputs, with no trained model.

### Formula:
```
score = -(w × normalised_log_time + (1-w) × normalised_log_cost)
```
- `w = priority / 100` (0 = care only about cost, 1 = care only about speed)
- Both time and cost are normalised within each payment so they're on the same scale
- Negated: higher score = better route

### Why it uses `group_df` (not ML output):
`group_df` is a slice of `te` (test data) for ONE payment — passed in by `groupby().apply()`. It contains the original simulator columns `est_hours` and `est_cost_usd`, which existed in the database before any ML ran. The heuristic and the ML model are two **independent** scorers looking at the same original data — neither feeds into the other.

---

## 11. The Scorecard — Three Rankers Compared

```python
te = te.assign(model=ranker.predict(te[FEATURES]))   # ML scores
te = te.assign(heur=te.groupby(...).apply(heuristic_score))  # formula scores
```

### Results on 9,665 test payments:
```
                          NDCG@3    Picks best route
Ops choice (history)       0.883        80.1%
Simulator heuristic        0.975        91.6%   ← pre-ML baseline
LightGBM ranker            0.979        94.5%   ← ML model
```

- **NDCG@3** — did the top 3 routes end up in roughly the right order? (1.0 = perfect)
- **Picks best** — what % of the time was the #1 ranked route also the truly best route?
- The ML model must beat the heuristic (91.6%) to justify its complexity — it does (94.5%).

---

## 12. How LightGBM Makes a Prediction — Simple Mental Model

Using PAY route, payment_id=1 (`priority=15`, `est_hours=1.5`, `est_cost_usd=$7.90`):

**One tree = a flowchart of yes/no questions:**
```
priority > 50?
    NO → est_hours < 10?
              YES → score = +3.0
```

**LightGBM uses 400 trees, each correcting the last:**
```
Tree 1:   +3.0
Tree 2:   +0.2  (corrects tree 1's error)
Tree 3:   +0.1
...
Tree 400: tiny nudge
─────────────────────
Final score for PAY: +6.69  ← highest among all routes for this payment ✓
```

Higher score = model thinks this is the better route. The scores are only meaningful relative to other routes in the same payment — they have no absolute unit.

---

## 13. Time-to-Credit Regressor (Step 10) — The Log-Ratio Trick

Instead of predicting `realized_hours` from scratch, the model predicts:
```
target = log(realized_hours / est_hours)
```

- If prediction = 0 → simulator was perfect
- If prediction = +0.5 → real time was ~1.65× longer than estimated
- If prediction = -0.3 → real time was ~0.74× shorter than estimated

**Why?** The simulator is already a good estimate. Learning the *correction* is easier than learning the absolute value from scratch.

### Result:
```
Simulator MAE:  3.6 hours
LightGBM MAE:   3.4 hours
Improvement:    5.2%
```

Only payments under 72 hours are evaluated — returned/stuck payments would distort the average.

---

## 14. All-in Cost Regressor (Step 11)

- Predicts `realized_cost_usd` directly (no log-ratio trick — cost structure is different from time).
- Metric: **MAPE** (Mean Absolute Percentage Error) — useful across different payment sizes.
- Result: **4.6% MAPE** — considered good for payment cost prediction.

---

## Summary — The Full Picture

| Step | What it does |
|------|-------------|
| Load data | 169K rows from DB, each = one route option for one payment |
| Feature engineering | Add `log_amount`, `is_fi`, encode 3 ID columns as categories |
| Train/test split | Time-based at 2026-04-01, no data leakage |
| Ranker | LightGBM LambdaRank learns to rank routes by `relevance` within each payment |
| Heuristic baseline | Priority-weighted log(time + cost) formula — the pre-ML benchmark |
| Evaluation | NDCG@3 and Top-1 accuracy across ops, heuristic, and ML model |
| Time regressor | Predicts log(real/estimated) correction applied as multiplier to simulator |
| Cost regressor | Predicts absolute all-in cost, evaluated with MAPE |

**The core insight:** The simulator knows costs and times from contracts and calendars. The ML model learns the *patterns* the simulator can't know — which corridors run late on Fridays, which routes underperform for large FI payments, which templates are unreliable on month-end. That's what takes accuracy from 91.6% to 94.5%.
