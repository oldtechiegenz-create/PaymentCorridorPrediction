-- Queries behind the portal's Historical Cases tab and the "Similar historical cases" panel.
--
-- Each query starts with a "-- name: <name>" line; code/prediction_service/history_repository.py
-- loads them by that name, so this file is the single source of the tab's SQL. The joins and
-- aggregates live in the views (schema.sql: v_corridor_overview, v_route_performance_18m,
-- v_fx_corridor, v_incident, v_case, v_history_window); the queries only filter, order and page.
--
-- Named parameters work with Python sqlite3, better-sqlite3 and most drivers.
-- :corridor is a corridor_id such as 'USD-JPY'. Dates are 'YYYY-MM-DD'.
-- Optional filters are passed as NULL to mean "any".

-- name: history_window
-- Newest value date in the data and the start of the 18-month reporting window.
SELECT as_of, window_start FROM v_history_window;

-- name: corridors
-- Corridor picker. The API loads this once at startup and also uses it to reject unknown corridors.
SELECT corridor_id, dest_country, country_name, currency_code, currency_name, pegged_to_usd, local_rail,
       cases, first_value_date, last_value_date, incidents
FROM v_corridor_overview
ORDER BY sort_order;

-- name: route_performance
-- "Route performance, last 18 months": fastest median first, routes with no cases last.
SELECT template_id, route_name, via, cases, median_hours, p90_hours, avg_cost_usd, cost_bps,
       stp_pct, returned_pct, low_sample, window_start, as_of
FROM v_route_performance_18m
WHERE corridor_id = :corridor
ORDER BY cases = 0, median_hours, template_id;

-- name: fx_series
-- Daily mid rate for the last :days days of data. Annualised volatility is computed in the API
-- (stdev of daily log returns x sqrt(365), as the series has a rate for every calendar day).
SELECT rate_date, mid_rate
FROM v_fx_corridor
WHERE corridor_id = :corridor
  AND rate_date > date((SELECT MAX(rate_date) FROM v_fx_corridor WHERE corridor_id = :corridor), '-' || :days || ' days')
ORDER BY rate_date;

-- name: incident_categories
-- Incident counts by cause category, for the filter chips.
SELECT category, COUNT(*) AS incidents
FROM v_incident
WHERE corridor_id = :corridor
GROUP BY category
ORDER BY incidents DESC, category;

-- name: incident_count
SELECT COUNT(*) AS total
FROM v_incident
WHERE corridor_id = :corridor AND (:category IS NULL OR category = :category);

-- name: incidents
-- Annotated incidents, newest first. :category NULL = all.
SELECT incident_id, payment_id, value_date, released_at_utc, template_id, route_name, amount_usd, client_type,
       hours_to_credit, outcome, cause_code, category, cause, note
FROM v_incident
WHERE corridor_id = :corridor AND (:category IS NULL OR category = :category)
ORDER BY released_at_utc DESC, incident_id DESC
LIMIT :limit OFFSET :offset;

-- name: case_count
SELECT COUNT(*) AS total
FROM v_case
WHERE corridor_id = :corridor
  AND (:route IS NULL OR template_id = :route)
  AND (:outcome IS NULL OR outcome = :outcome);

-- name: cases
-- All cases, newest first. :route and :outcome NULL = all.
SELECT payment_id, uetr, value_date, released_at_utc, template_id, route_name, amount_usd, client_type,
       charges, purpose_code, hours_to_credit, all_in_cost_usd, cost_bps, nonbusiness_days, outcome, stp, ops_note
FROM v_case
WHERE corridor_id = :corridor
  AND (:route IS NULL OR template_id = :route)
  AND (:outcome IS NULL OR outcome = :outcome)
ORDER BY released_at_utc DESC, payment_id DESC
LIMIT :limit OFFSET :offset;

-- name: similar_cases
-- Similar historical cases for a new request (Route tab). Lower score = more similar. Each factor:
--   amount closeness as a ratio (x2 amount or a half scores 2.0);
--   other client type x1.82; annotated cases x0.61 (they explain what happened); same release weekday x0.74;
--   age: x1.3 per year before the newest data, so recent cases win ties.
-- :amount (USD), :client_type ('Retail' or 'FI'), :value_date (the new payment's value date), :limit
SELECT payment_id, value_date, released_at_utc, template_id, route_name, amount_usd, client_type,
       hours_to_credit, all_in_cost_usd, outcome, ops_note
FROM v_case
WHERE corridor_id = :corridor
ORDER BY (MAX(amount_usd / :amount, :amount / amount_usd)
          * CASE WHEN client_type = :client_type THEN 1.0 ELSE 1.82 END
          * CASE WHEN ops_note IS NOT NULL THEN 0.61 ELSE 1.0 END
          * CASE WHEN strftime('%w', value_date) = strftime('%w', :value_date) THEN 0.74 ELSE 1.0 END
          * (1.0 + 0.3 * (julianday((SELECT as_of FROM v_history_window)) - julianday(value_date)) / 365.0)),
         released_at_utc DESC
LIMIT :limit;

-- name: route_summary
-- Cases and median time to credit on one route over the 18-month window (sentence above the similar cases).
SELECT template_id, route_name, cases, median_hours, p90_hours, low_sample
FROM v_route_performance_18m
WHERE corridor_id = :corridor AND template_id = :template;

-- name: holidays
-- Non-business days by country in a window (for the Route tab calendar; not served by the API yet).
SELECT country_code, holiday_date, name
FROM holiday
WHERE holiday_date BETWEEN :from AND :to
ORDER BY holiday_date, country_code;
