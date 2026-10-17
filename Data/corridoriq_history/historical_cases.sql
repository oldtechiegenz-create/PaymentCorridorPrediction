-- Queries behind the portal's Historical Cases tab and the "Similar historical cases" panel.
-- Named parameters (:corridor, :since, ...) work with Python sqlite3, better-sqlite3 and most drivers.
-- :corridor is a corridor_id such as 'USD-JPY'. Dates are 'YYYY-MM-DD'.

-- 1. Route performance for a corridor since a date (portal: "Route performance, last 18 months")
--    :since = 18 months before the latest value_date, e.g. '2025-04-01'
WITH base AS (
  SELECT * FROM payment WHERE corridor_id = :corridor AND value_date >= :since
), ranked AS (
  SELECT template_id, hours_to_credit AS h,
         ROW_NUMBER() OVER (PARTITION BY template_id ORDER BY hours_to_credit) AS rn,
         COUNT(*)     OVER (PARTITION BY template_id) AS n
  FROM base WHERE hours_to_credit IS NOT NULL
)
SELECT b.template_id, t.name AS route_name, t.via, COUNT(*) AS cases,
       (SELECT ROUND(h, 1) FROM ranked r WHERE r.template_id = b.template_id AND r.rn = (r.n + 1) / 2) AS median_hours,
       (SELECT ROUND(h, 1) FROM ranked r WHERE r.template_id = b.template_id AND r.rn = MAX(1, CAST(ROUND(0.9 * r.n) AS INTEGER))) AS p90_hours,
       ROUND(SUM(b.all_in_cost_usd) * 10000.0 / SUM(b.amount_usd), 1) AS cost_bps,
       ROUND(AVG(b.stp) * 100.0, 1) AS stp_pct
FROM base b JOIN route_template t ON t.template_id = b.template_id
GROUP BY b.template_id
ORDER BY median_hours;

-- 2. FX rate, last 90 days (annualised volatility = stdev of daily log returns x sqrt(252), computed in the app)
SELECT f.rate_date, f.mid_rate, c.pegged_to_usd
FROM fx_rate_daily f
JOIN corridor co ON co.currency_code = f.currency_code
JOIN currency c ON c.code = f.currency_code
WHERE co.corridor_id = :corridor
  AND f.rate_date > date((SELECT MAX(rate_date) FROM fx_rate_daily), '-90 days')
ORDER BY f.rate_date;

-- 3. Annotated incidents for a corridor, newest first
SELECT value_date, route_name, amount_usd, hours_to_credit, outcome, cause, note
FROM v_incident
WHERE corridor_id = :corridor
ORDER BY released_at_utc DESC
LIMIT :limit;

-- 4. All cases for a corridor, newest first, with a total for the header ("N cases, newest first")
SELECT COUNT(*) AS total_cases FROM payment WHERE corridor_id = :corridor;

SELECT value_date, route_name, amount_usd, client_type, hours_to_credit, all_in_cost_usd, nonbusiness_days, outcome
FROM v_case
WHERE corridor_id = :corridor
ORDER BY released_at_utc DESC
LIMIT :limit OFFSET :offset;

-- 5. Similar historical cases for a new request (route tab)
--    Matches the portal's scoring: amount closeness (ratio), same client type, annotated cases first, same release weekday.
--    :amount (USD), :client_type ('Retail' or 'FI'), :weekday (0 = Sunday .. 6 = Saturday)
SELECT value_date, route_name, template_id, amount_usd, client_type, hours_to_credit, all_in_cost_usd, outcome, ops_note
FROM v_case
WHERE corridor_id = :corridor
ORDER BY (MAX(amount_usd / :amount, :amount / amount_usd)
          * CASE WHEN client_type = :client_type THEN 1.0 ELSE 1.82 END
          * CASE WHEN ops_note IS NOT NULL THEN 0.61 ELSE 1.0 END
          * CASE WHEN CAST(strftime('%w', value_date) AS INTEGER) = :weekday THEN 0.74 ELSE 1.0 END)
LIMIT 6;

-- 6. Median time to credit on one route (sentence above the similar-cases table)
WITH r AS (
  SELECT hours_to_credit AS h, ROW_NUMBER() OVER (ORDER BY hours_to_credit) AS rn, COUNT(*) OVER () AS n
  FROM payment WHERE corridor_id = :corridor AND template_id = :template AND hours_to_credit IS NOT NULL
)
SELECT MAX(n) AS cases, ROUND(MAX(CASE WHEN rn = (n + 1) / 2 THEN h END), 1) AS median_hours FROM r;

-- 7. Calendar for the route tab: non-business days by country in a window
SELECT country_code, holiday_date, name FROM holiday
WHERE holiday_date BETWEEN :from AND :to
ORDER BY holiday_date, country_code;
