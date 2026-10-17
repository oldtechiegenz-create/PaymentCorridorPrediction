-- CorridorIQ historical data store (SQLite 3.25+; window functions are used in views)
-- MVP 1 foundation: reference data, settled payments with hop timelines, every candidate
-- route per payment with its estimated and realised outcome (ranking labels), incidents and FX.
--
-- Conventions
--   * Timestamps are ISO 8601 UTC text: 'YYYY-MM-DDTHH:MM:SSZ'. Dates are 'YYYY-MM-DD'.
--   * Money is USD unless the column says otherwise. Basis points (bps) = 1/100 of a percent.
--   * Weekday numbers follow the portal: 0 = Sunday ... 6 = Saturday.
--   * Hours are elapsed hours from release to the event.

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------- metadata
CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- ---------------------------------------------------------------- reference data
CREATE TABLE currency (
  code          TEXT PRIMARY KEY,            -- ISO 4217
  name          TEXT NOT NULL,
  decimals      INTEGER NOT NULL,
  pegged_to_usd INTEGER NOT NULL DEFAULT 0   -- 1 = pegged (AED, SAR): FX timing risk negligible
);

CREATE TABLE country (
  code          TEXT PRIMARY KEY,            -- ISO 3166 alpha-2
  name          TEXT NOT NULL,
  tz_name       TEXT NOT NULL,               -- IANA zone, DST-aware
  weekend_days  TEXT NOT NULL,               -- comma list of weekday numbers, e.g. '5,6' for Fri-Sat
  currency_code TEXT REFERENCES currency(code),
  extra_rule    TEXT                         -- e.g. 'IN_2ND_4TH_SAT' (Indian banks closed 2nd and 4th Saturday)
);

CREATE TABLE holiday (
  country_code TEXT NOT NULL REFERENCES country(code),
  holiday_date TEXT NOT NULL,
  name         TEXT NOT NULL,
  source       TEXT NOT NULL,                -- library and version, or 'rule'
  PRIMARY KEY (country_code, holiday_date)
);

CREATE TABLE bank (
  bank_id         TEXT PRIMARY KEY,          -- short key used by route templates (NY1, SG, ...)
  name            TEXT NOT NULL,
  bic             TEXT NOT NULL UNIQUE,      -- 'API' for the non-bank partner
  city            TEXT NOT NULL,
  country_code    TEXT NOT NULL REFERENCES country(code),
  role            TEXT NOT NULL,             -- Ordering bank, USD correspondent, Asia hub, ...
  opens_local     TEXT NOT NULL DEFAULT '09:00',
  cutoff_local    TEXT,                      -- NULL when all_day = 1
  all_day         INTEGER NOT NULL DEFAULT 0,-- 1 = processes 24x7 (payout partner)
  is_intermediary INTEGER NOT NULL DEFAULT 0,
  is_nonbank      INTEGER NOT NULL DEFAULT 0,
  risk_rating     INTEGER NOT NULL DEFAULT 1 -- internal jurisdiction rating 1 low .. 3 high
);

CREATE TABLE bank_closure (                  -- ad hoc closures on top of country calendars
  bank_id      TEXT NOT NULL REFERENCES bank(bank_id),
  closure_date TEXT NOT NULL,
  reason       TEXT NOT NULL,
  PRIMARY KEY (bank_id, closure_date)
);

CREATE TABLE corridor (
  corridor_id      TEXT PRIMARY KEY,         -- 'USD-JPY'
  origin_country   TEXT NOT NULL REFERENCES country(code),
  dest_country     TEXT NOT NULL REFERENCES country(code),
  currency_code    TEXT NOT NULL REFERENCES currency(code),
  local_rail       TEXT NOT NULL,            -- Zengin, NEFT / IMPS, ...
  country_risk     INTEGER NOT NULL,
  clearing_bank_id TEXT NOT NULL REFERENCES bank(bank_id)
);

CREATE TABLE beneficiary_bank (
  corridor_id TEXT NOT NULL REFERENCES corridor(corridor_id),
  bank_id     TEXT NOT NULL REFERENCES bank(bank_id),
  share       REAL NOT NULL,                 -- share of payments to this bank
  PRIMARY KEY (corridor_id, bank_id)
);

CREATE TABLE route_template (
  template_id      TEXT PRIMARY KEY,         -- EXP, ASIA, LDN, GULF, PAY, ECO
  name             TEXT NOT NULL,
  via              TEXT NOT NULL,
  gpi              INTEGER NOT NULL,
  stp_rate_nominal REAL NOT NULL,            -- straight-through rate assumed by the generator
  cap_usd          REAL,                     -- per-transfer cap, NULL = none
  retail_only      INTEGER NOT NULL DEFAULT 0,
  non_bank         INTEGER NOT NULL DEFAULT 0,
  instant_payout   INTEGER NOT NULL DEFAULT 0,
  batch_hop_seq    INTEGER,                  -- hop held for the Thursday netting batch
  fx_bps_standard  REAL NOT NULL,
  fx_bps_large     REAL NOT NULL,            -- applies at or above meta.large_ticket_usd
  note             TEXT
);

CREATE TABLE route_template_hop (
  template_id TEXT NOT NULL REFERENCES route_template(template_id),
  seq         INTEGER NOT NULL,              -- 0 = ordering bank
  bank_ref    TEXT NOT NULL,                 -- bank_id, or 'CLR' / 'BENE' resolved per corridor and payment
  fee_usd     REAL NOT NULL,
  proc_hours  REAL NOT NULL,                 -- nominal processing time at this hop
  PRIMARY KEY (template_id, seq)
);

CREATE TABLE corridor_route (
  corridor_id TEXT NOT NULL REFERENCES corridor(corridor_id),
  template_id TEXT NOT NULL REFERENCES route_template(template_id),
  PRIMARY KEY (corridor_id, template_id)
);

CREATE TABLE fx_rate_daily (
  currency_code TEXT NOT NULL REFERENCES currency(code),
  rate_date     TEXT NOT NULL,
  mid_rate      REAL NOT NULL,               -- units of currency per 1 USD
  source        TEXT NOT NULL,
  PRIMARY KEY (currency_code, rate_date)
);

CREATE TABLE cause_code (
  code     TEXT PRIMARY KEY,
  category TEXT NOT NULL,                    -- calendar, cutoff, batch, repair, return
  label    TEXT NOT NULL
);

CREATE TABLE client (
  client_id        TEXT PRIMARY KEY,
  client_type      TEXT NOT NULL CHECK (client_type IN ('Retail','FI')),
  display_name     TEXT NOT NULL,
  segment          TEXT NOT NULL,
  home_corridor_id TEXT REFERENCES corridor(corridor_id)
);

-- ---------------------------------------------------------------- facts
CREATE TABLE payment (
  payment_id          INTEGER PRIMARY KEY,
  uetr                TEXT NOT NULL UNIQUE,  -- gpi unique end-to-end transaction reference
  end_to_end_id       TEXT NOT NULL,
  client_id           TEXT NOT NULL REFERENCES client(client_id),
  client_type         TEXT NOT NULL CHECK (client_type IN ('Retail','FI')),
  corridor_id         TEXT NOT NULL REFERENCES corridor(corridor_id),
  amount_usd          REAL NOT NULL,
  charges             TEXT NOT NULL CHECK (charges IN ('OUR','SHA','BEN')),
  purpose_code        TEXT NOT NULL,
  priority            INTEGER NOT NULL,      -- 0 = lowest cost .. 100 = fastest credit
  value_date          TEXT NOT NULL,         -- US date the payment was released for
  deadline_date       TEXT,                  -- client's arrive-by date (beneficiary local), optional
  released_at_utc     TEXT NOT NULL,
  release_hour_et     REAL NOT NULL,         -- local hour at release, New York time
  beneficiary_name    TEXT NOT NULL,
  bene_bank_id        TEXT NOT NULL REFERENCES bank(bank_id),
  template_id         TEXT NOT NULL REFERENCES route_template(template_id),
  chosen_by           TEXT NOT NULL DEFAULT 'ops',
  est_hours           REAL NOT NULL,         -- simulator estimate at release (nominal processing times)
  fee_total_usd       REAL NOT NULL,
  fx_bps              REAL NOT NULL,
  fx_cost_usd         REAL NOT NULL,
  all_in_cost_usd     REAL NOT NULL,         -- fees + FX margin, as realised
  mid_rate            REAL NOT NULL,
  rate_offered        REAL NOT NULL,
  amount_converted_usd REAL NOT NULL,        -- after fees deducted under SHA / BEN
  beneficiary_amount  REAL NOT NULL,         -- in corridor currency
  outcome             TEXT NOT NULL CHECK (outcome IN ('Credited','Repaired','Returned')),
  stp                 INTEGER NOT NULL,      -- 1 = no manual intervention anywhere on the route
  credited_at_utc     TEXT,
  returned_at_utc     TEXT,
  hours_to_credit     REAL,                  -- NULL when returned
  nonbusiness_days    INTEGER NOT NULL,      -- distinct non-business days that held the payment
  deadline_met        INTEGER                -- NULL when no deadline
);
CREATE INDEX ix_payment_corridor_time ON payment (corridor_id, released_at_utc);
CREATE INDEX ix_payment_template      ON payment (corridor_id, template_id);
CREATE INDEX ix_payment_client        ON payment (client_id);

CREATE TABLE payment_hop (                   -- gpi-style timeline, one row per bank on the route
  payment_id     INTEGER NOT NULL REFERENCES payment(payment_id),
  seq            INTEGER NOT NULL,
  bank_id        TEXT NOT NULL REFERENCES bank(bank_id),
  arrived_at_utc TEXT NOT NULL,
  started_at_utc TEXT NOT NULL,              -- processing began (after any hold)
  left_at_utc    TEXT NOT NULL,              -- passed on, or credited at the last hop
  fee_usd        REAL NOT NULL,
  status_code    TEXT NOT NULL,              -- ACSP in progress, ACCC credited, RJCT returned
  hold_kind      TEXT,                       -- calendar, cutoff, batch, repair (largest hold)
  hold_reason    TEXT,                       -- all holds at this hop, '; ' separated
  hold_hours     REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (payment_id, seq)
);
CREATE INDEX ix_hop_bank ON payment_hop (bank_id);

CREATE TABLE route_candidate (               -- every route that existed for the payment, including the one used
  payment_id            INTEGER NOT NULL REFERENCES payment(payment_id),
  template_id           TEXT NOT NULL REFERENCES route_template(template_id),
  eligible              INTEGER NOT NULL,    -- passed caps and client rules at release
  exclusion_reason      TEXT,
  hop_count             INTEGER NOT NULL,
  intermediaries        INTEGER NOT NULL,
  fee_total_usd         REAL NOT NULL,       -- quoted fees
  fx_bps                REAL NOT NULL,
  est_cost_usd          REAL NOT NULL,       -- quoted fees + quoted FX margin
  est_hours             REAL NOT NULL,       -- calendar-aware simulator estimate, nominal processing
  naive_hours           REAL NOT NULL,       -- processing hours only, no foreign calendars (pre-CorridorIQ view)
  est_nonbusiness_days  INTEGER NOT NULL,
  calendar_conflict     INTEGER NOT NULL,    -- an intermediary is closed on the value date or next day while the US is open
  cutoff_slack_hours    REAL NOT NULL,       -- hours from release to the ordering bank's cut-off (negative = after)
  max_risk              INTEGER NOT NULL,
  stp_rate_nominal      REAL NOT NULL,
  realized_hours        REAL NOT NULL,       -- what would have happened (actual for the route used); returns = time to return + 72 h penalty
  realized_cost_usd     REAL NOT NULL,
  realized_outcome      TEXT NOT NULL,
  utility               REAL,                -- lower is better; NULL when not eligible
  relevance             INTEGER NOT NULL,    -- ranking label 0..4 within the payment (4 = best realised route)
  was_chosen            INTEGER NOT NULL,
  PRIMARY KEY (payment_id, template_id)
);
CREATE INDEX ix_candidate_template ON route_candidate (template_id);

CREATE TABLE incident (                      -- ops annotations the model learns from
  incident_id      INTEGER PRIMARY KEY,
  payment_id       INTEGER NOT NULL REFERENCES payment(payment_id),
  cause_code       TEXT NOT NULL REFERENCES cause_code(code),
  note             TEXT NOT NULL,
  annotated_by     TEXT NOT NULL,
  annotated_at_utc TEXT NOT NULL
);
CREATE INDEX ix_incident_payment ON incident (payment_id);

-- ---------------------------------------------------------------- views for the Historical Cases tab

-- All cases: one row per payment, newest first when ordered by released_at_utc DESC
CREATE VIEW v_case AS
SELECT p.payment_id, p.uetr, p.corridor_id, c.dest_country, p.value_date, p.released_at_utc,
       p.template_id, t.name AS route_name, t.via, p.amount_usd, p.client_type, p.charges, p.purpose_code,
       p.hours_to_credit, p.all_in_cost_usd, ROUND(p.all_in_cost_usd * 10000.0 / p.amount_usd, 1) AS cost_bps,
       p.nonbusiness_days, p.outcome, p.stp, p.est_hours,
       (SELECT i.note FROM incident i WHERE i.payment_id = p.payment_id ORDER BY i.incident_id LIMIT 1) AS ops_note
FROM payment p
JOIN route_template t ON t.template_id = p.template_id
JOIN corridor c ON c.corridor_id = p.corridor_id;

-- Route performance: cases, median and P90 time to credit, average cost (USD and value-weighted bps), STP rate
CREATE VIEW v_route_performance AS
WITH all_cases AS (
  SELECT corridor_id, template_id, COUNT(*) AS cases,
         ROUND(AVG(all_in_cost_usd), 2) AS avg_cost_usd,
         ROUND(SUM(all_in_cost_usd) * 10000.0 / SUM(amount_usd), 1) AS cost_bps,   -- value-weighted
         ROUND(AVG(stp) * 100.0, 1) AS stp_pct,
         ROUND(AVG(outcome = 'Returned') * 100.0, 2) AS returned_pct
  FROM payment GROUP BY corridor_id, template_id
), ranked AS (
  SELECT corridor_id, template_id, hours_to_credit AS h,
         ROW_NUMBER() OVER (PARTITION BY corridor_id, template_id ORDER BY hours_to_credit) AS rn,
         COUNT(*)     OVER (PARTITION BY corridor_id, template_id) AS n
  FROM payment WHERE hours_to_credit IS NOT NULL
), pct AS (
  SELECT corridor_id, template_id,
         MAX(CASE WHEN rn = (n + 1) / 2 THEN h END) AS median_hours,
         MAX(CASE WHEN rn = MAX(1, CAST(ROUND(0.9 * n) AS INTEGER)) THEN h END) AS p90_hours
  FROM ranked GROUP BY corridor_id, template_id
)
SELECT a.corridor_id, a.template_id, t.name AS route_name, t.via, a.cases,
       ROUND(p.median_hours, 1) AS median_hours, ROUND(p.p90_hours, 1) AS p90_hours,
       a.avg_cost_usd, a.cost_bps, a.stp_pct, a.returned_pct
FROM all_cases a
JOIN pct p USING (corridor_id, template_id)
JOIN route_template t ON t.template_id = a.template_id;

-- Annotated incidents, newest first when ordered by released_at_utc DESC
CREATE VIEW v_incident AS
SELECT i.incident_id, p.payment_id, p.corridor_id, p.value_date, p.released_at_utc, p.template_id, t.name AS route_name,
       p.amount_usd, p.client_type, p.hours_to_credit, p.outcome, i.cause_code, cc.category, cc.label AS cause, i.note
FROM incident i
JOIN payment p ON p.payment_id = i.payment_id
JOIN route_template t ON t.template_id = p.template_id
JOIN cause_code cc ON cc.code = i.cause_code;

-- Monthly volume and value per corridor
CREATE VIEW v_corridor_monthly AS
SELECT corridor_id, substr(value_date, 1, 7) AS month, COUNT(*) AS payments,
       ROUND(SUM(amount_usd), 0) AS value_usd, ROUND(AVG(hours_to_credit), 1) AS avg_hours,
       ROUND(AVG(stp) * 100.0, 1) AS stp_pct
FROM payment GROUP BY corridor_id, month;

-- Ranking training set: one row per (payment, candidate route) with features known at release
-- and the realised label. Group by payment_id for LambdaRank; filter eligible = 1.
CREATE VIEW v_ranking_training AS
SELECT rc.payment_id, p.corridor_id, p.value_date, rc.template_id, p.bene_bank_id,
       p.client_type, p.amount_usd, p.charges, p.purpose_code, p.priority,
       p.release_hour_et,
       CAST(strftime('%w', p.value_date) AS INTEGER) AS release_weekday,
       (p.deadline_date IS NOT NULL) AS has_deadline,
       CAST(strftime('%d', p.value_date) AS INTEGER) AS day_of_month,
       rc.eligible, rc.hop_count, rc.intermediaries, rc.fee_total_usd, rc.fx_bps, rc.est_cost_usd,
       rc.est_hours, rc.naive_hours, rc.est_nonbusiness_days, rc.calendar_conflict, rc.cutoff_slack_hours,
       rc.max_risk, rc.stp_rate_nominal,
       rc.realized_hours, rc.realized_cost_usd, rc.realized_outcome, rc.utility, rc.relevance, rc.was_chosen
FROM route_candidate rc
JOIN payment p ON p.payment_id = rc.payment_id;
