package com.corridoriq.backend.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.corridoriq.backend.config.CorridorIqProperties;
import com.corridoriq.backend.history.HistoryModels.Case;
import com.corridoriq.backend.history.HistoryModels.CategoryCount;
import com.corridoriq.backend.history.HistoryModels.CorridorSummary;
import com.corridoriq.backend.history.HistoryModels.FxPoint;
import com.corridoriq.backend.history.HistoryModels.HistoryWindow;
import com.corridoriq.backend.history.HistoryModels.Incident;
import com.corridoriq.backend.history.HistoryModels.RoutePerformance;
import com.corridoriq.backend.history.HistoryModels.RouteSummary;
import com.corridoriq.backend.history.HistoryModels.SimilarCase;

/**
 * Read access to the history database for the Historical Cases tab and the similar-cases panel.
 *
 * <p>The joins and aggregates live in the views (schema.sql) and the SQL in historical_cases.sql; this
 * class only runs the named queries and maps the rows. Optional filters are bound as NULL to mean "any".
 */
@Repository
public class HistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(HistoryRepository.class);

    /** Mirrors the 50-case cut in v_route_performance_18m. */
    public static final int LOW_SAMPLE_THRESHOLD = 50;

    static final List<String> REQUIRED_VIEWS = List.of("v_history_window", "v_corridor_overview",
            "v_route_performance_18m", "v_fx_corridor", "v_incident", "v_case");

    private final NamedParameterJdbcTemplate jdbc;
    private final NamedQueries queries;
    private final HistoryWindow window;
    private final Map<String, CorridorSummary> corridors;
    private final Set<String> templateIds;

    public HistoryRepository(NamedParameterJdbcTemplate jdbc, CorridorIqProperties props) {
        this.jdbc = jdbc;
        this.queries = NamedQueries.load(props.sqlPath());

        Set<String> views = new HashSet<>(jdbc.getJdbcTemplate()
                .queryForList("SELECT name FROM sqlite_master WHERE type = 'view'", String.class));
        List<String> missing = REQUIRED_VIEWS.stream().filter(v -> !views.contains(v)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(props.dbPath() + " lacks views " + String.join(", ", missing)
                    + "; recreate them with ApplyViews (see code/README.md)");
        }

        // The backend only reads the database, so the corridor list, route templates and window are loaded
        // once: every corridor endpoint checks the corridor exists, and v_corridor_overview counts all payments.
        this.window = rows("history_window", Map.of(),
                (rs, i) -> new HistoryWindow(rs.getString("as_of"), rs.getString("window_start"))).getFirst();
        Map<String, CorridorSummary> byId = new LinkedHashMap<>();
        for (CorridorSummary c : rows("corridors", Map.of(), HistoryRepository::corridorSummary)) {
            byId.put(c.corridorId(), c);
        }
        this.corridors = byId;
        this.templateIds = new LinkedHashSet<>(rows("route_templates", Map.of(), (rs, i) -> rs.getString(1)));
        log.info("History database {}: {} named queries from {}, views present {}, {} corridors, window {} to {}",
                props.dbPath(), queries.names().size(), props.sqlPath(), REQUIRED_VIEWS, corridors.size(),
                window.windowStart(), window.asOf());
    }

    // ------------------------------------------------------------------ reference
    public HistoryWindow window() {
        return window;
    }

    public List<CorridorSummary> corridors() {
        return List.copyOf(corridors.values());
    }

    public Optional<CorridorSummary> corridor(String corridorId) {
        return Optional.ofNullable(corridors.get(corridorId));
    }

    /** Every route template id (EXP, ASIA, ...), sorted. */
    public Set<String> templateIds() {
        return templateIds;
    }

    // ------------------------------------------------------------------ tab panels
    public List<RoutePerformance> routePerformance(String corridorId) {
        return rows("route_performance", Map.of("corridor", corridorId), (rs, i) -> new RoutePerformance(
                rs.getString("template_id"), rs.getString("route_name"), rs.getString("via"), rs.getInt("cases"),
                dbl(rs, "median_hours"), dbl(rs, "p90_hours"), dbl(rs, "avg_cost_usd"), dbl(rs, "cost_bps"),
                dbl(rs, "stp_pct"), dbl(rs, "returned_pct"), rs.getInt("low_sample") == 1));
    }

    public List<FxPoint> fxSeries(String corridorId, int days) {
        return rows("fx_series", Map.of("corridor", corridorId, "days", days),
                (rs, i) -> new FxPoint(rs.getString("rate_date"), rs.getDouble("mid_rate")));
    }

    public List<CategoryCount> incidentCategories(String corridorId) {
        return rows("incident_categories", Map.of("corridor", corridorId),
                (rs, i) -> new CategoryCount(rs.getString("category"), rs.getInt("incidents")));
    }

    public int incidentCount(String corridorId, String category) {
        return count("incident_count", params("corridor", corridorId, "category", category));
    }

    public List<Incident> incidents(String corridorId, String category, int limit, int offset) {
        return rows("incidents", params("corridor", corridorId, "category", category, "limit", limit, "offset", offset),
                (rs, i) -> new Incident(rs.getLong("incident_id"), rs.getLong("payment_id"), rs.getString("value_date"),
                        rs.getString("released_at_utc"), rs.getString("template_id"), rs.getString("route_name"),
                        rs.getDouble("amount_usd"), rs.getString("client_type"), dbl(rs, "hours_to_credit"),
                        rs.getString("outcome"), rs.getString("cause_code"), rs.getString("category"),
                        rs.getString("cause"), rs.getString("note")));
    }

    public int caseCount(String corridorId, String route, String outcome) {
        return count("case_count", params("corridor", corridorId, "route", route, "outcome", outcome));
    }

    public List<Case> cases(String corridorId, String route, String outcome, int limit, int offset) {
        return rows("cases", params("corridor", corridorId, "route", route, "outcome", outcome, "limit", limit,
                "offset", offset), (rs, i) -> new Case(rs.getLong("payment_id"), rs.getString("uetr"),
                rs.getString("value_date"), rs.getString("released_at_utc"), rs.getString("template_id"),
                rs.getString("route_name"), rs.getDouble("amount_usd"), rs.getString("client_type"),
                rs.getString("charges"), rs.getString("purpose_code"), dbl(rs, "hours_to_credit"),
                rs.getDouble("all_in_cost_usd"), rs.getDouble("cost_bps"), rs.getInt("nonbusiness_days"),
                rs.getString("outcome"), rs.getInt("stp") == 1, rs.getString("ops_note")));
    }

    public List<SimilarCase> similarCases(String corridorId, double amountUsd, String clientType, String valueDate,
                                          int limit) {
        return rows("similar_cases", Map.of("corridor", corridorId, "amount", amountUsd, "client_type", clientType,
                "value_date", valueDate, "limit", limit), (rs, i) -> new SimilarCase(rs.getLong("payment_id"),
                rs.getString("value_date"), rs.getString("released_at_utc"), rs.getString("template_id"),
                rs.getString("route_name"), rs.getDouble("amount_usd"), rs.getString("client_type"),
                dbl(rs, "hours_to_credit"), rs.getDouble("all_in_cost_usd"), rs.getString("outcome"),
                rs.getString("ops_note")));
    }

    public Optional<RouteSummary> routeSummary(String corridorId, String templateId) {
        return rows("route_summary", Map.of("corridor", corridorId, "template", templateId),
                (rs, i) -> new RouteSummary(rs.getString("template_id"), rs.getString("route_name"), rs.getInt("cases"),
                        dbl(rs, "median_hours"), dbl(rs, "p90_hours"), rs.getInt("low_sample") == 1))
                .stream().findFirst();
    }

    // ------------------------------------------------------------------ calculations
    /** Sample stdev of daily log returns, annualised, in percent to 2 dp; null with fewer than 3 points. */
    public static Double annualisedVolatilityPct(List<Double> rates) {
        if (rates.size() < 3) {
            return null;
        }
        List<Double> rets = new ArrayList<>(rates.size() - 1);
        for (int i = 1; i < rates.size(); i++) {
            rets.add(Math.log(rates.get(i) / rates.get(i - 1)));
        }
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum();
        double stdev = Math.sqrt(ss / (rets.size() - 1));
        return round(stdev * Math.sqrt(365) * 100, 2);
    }

    /** Rounds like Python's round(): to the nearest, ties to even, on the double's exact value. */
    static double round(double x, int places) {
        return new BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }

    // ------------------------------------------------------------------ helpers
    /**
     * Runs a named query and logs it: name, the views and tables it reads, parameters, rows and time.
     * At DEBUG the SQL text is logged too.
     */
    private <T> List<T> rows(String name, Map<String, ?> params, RowMapper<T> mapper) {
        String sql = queries.get(name);
        Map<String, ?> shown = new TreeMap<>(params);   // sorted, so the line reads the same every time
        log.debug("SQL {} text:\n{}", name, sql);
        long start = System.nanoTime();
        try {
            List<T> out = jdbc.query(sql, params, mapper);
            log.info("SQL {} on {} {} -> {} rows ({} ms)", name, queries.sources(name), shown, out.size(),
                    (System.nanoTime() - start) / 1_000_000);
            return out;
        } catch (DataAccessException e) {
            log.error("SQL {} on {} {} failed after {} ms: {}", name, queries.sources(name), shown,
                    (System.nanoTime() - start) / 1_000_000, e.getMostSpecificCause().toString());
            throw e;
        }
    }

    private int count(String name, Map<String, ?> params) {
        return rows(name, params, (rs, i) -> rs.getInt("total")).getFirst();
    }

    /** Named parameters that may be null (Map.of rejects null values). */
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A nullable numeric column as Double: SQLite returns INTEGER or REAL depending on the stored value. */
    private static Double dbl(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static CorridorSummary corridorSummary(ResultSet rs, int i) throws SQLException {
        return new CorridorSummary(rs.getString("corridor_id"), rs.getString("dest_country"),
                rs.getString("country_name"), rs.getString("currency_code"), rs.getString("currency_name"),
                rs.getInt("pegged_to_usd") == 1, rs.getString("local_rail"), rs.getInt("cases"),
                rs.getString("first_value_date"), rs.getString("last_value_date"), rs.getInt("incidents"));
    }
}
