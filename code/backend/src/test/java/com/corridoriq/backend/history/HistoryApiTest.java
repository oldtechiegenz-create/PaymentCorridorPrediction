package com.corridoriq.backend.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.corridoriq.backend.config.CorridorIqProperties;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/**
 * History API against the real history database: shapes, totals checked against the raw tables,
 * paging, filters, errors, CORS and the portal.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryApiTest {

    private static final String BASE = "/v1/history/corridors";

    @Autowired
    MockMvc mvc;

    /** The same read-only database, for checking the API against the raw tables. */
    @Autowired
    JdbcTemplate db;

    @Autowired
    CorridorIqProperties props;

    private DocumentContext json(String url) throws Exception {
        MvcResult r = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return JsonPath.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private <T> T scalar(Class<T> type, String sql, Object... args) {
        return db.queryForObject(sql, type, args);
    }

    // ------------------------------------------------------------------ SQL sources
    @Test
    void namedQueriesParse() {
        Set<String> names = NamedQueries.load(props.sqlPath()).names();
        assertThat(names).contains("history_window", "route_templates", "corridors", "route_performance",
                "fx_series", "incident_categories", "incident_count", "incidents", "case_count", "cases",
                "similar_cases", "route_summary");
    }

    // ------------------------------------------------------------------ corridors
    @Test
    void corridors() throws Exception {
        DocumentContext d = json(BASE);
        assertThat(d.read("$.window.as_of", String.class)).isEqualTo(scalar(String.class, "SELECT MAX(value_date) FROM payment"));
        assertThat(d.read("$.window.window_start", String.class)).isEqualTo("2025-04-01");
        List<String> ids = d.read("$.corridors[*].corridor_id");
        assertThat(ids).containsExactly("USD-JPY", "USD-INR", "USD-AED", "USD-SAR", "USD-EUR");
        assertThat(sum(d.read("$.corridors[*].cases"))).isEqualTo(scalar(Integer.class, "SELECT COUNT(*) FROM payment"));
        assertThat(sum(d.read("$.corridors[*].incidents"))).isEqualTo(scalar(Integer.class, "SELECT COUNT(*) FROM incident"));
        List<Map<String, Object>> corridors = d.read("$.corridors");
        Map<String, Object> pegged = corridors.stream()
                .collect(Collectors.toMap(c -> (String) c.get("currency_code"), c -> c.get("pegged_to_usd")));
        assertThat(pegged).isEqualTo(Map.of("JPY", false, "INR", false, "AED", true, "SAR", true, "EUR", false));
    }

    // ------------------------------------------------------------------ route performance
    @ParameterizedTest
    @ValueSource(strings = {"USD-JPY", "USD-AED"})
    void routePerformanceMatchesRawRows(String corridor) throws Exception {
        DocumentContext d = json(BASE + "/" + corridor + "/route-performance");
        String since = d.read("$.window.window_start");
        int threshold = d.read("$.low_sample_threshold");
        List<Map<String, Object>> routes = d.read("$.routes");

        Set<String> offered = new HashSet<>(db.queryForList(
                "SELECT template_id FROM corridor_route WHERE corridor_id = ?", String.class, corridor));
        assertThat(routes.stream().map(r -> (String) r.get("template_id")).collect(Collectors.toSet())).isEqualTo(offered);
        assertThat(routes.stream().mapToInt(r -> (Integer) r.get("cases")).sum()).isEqualTo(scalar(Integer.class,
                "SELECT COUNT(*) FROM payment WHERE corridor_id = ? AND value_date >= ?", corridor, since));

        for (Map<String, Object> r : routes) {
            int cases = (Integer) r.get("cases");
            assertThat(r.get("low_sample")).isEqualTo(cases < threshold);
            List<Double> hours = db.queryForList("SELECT hours_to_credit FROM payment WHERE corridor_id = ? "
                    + "AND template_id = ? AND value_date >= ? AND hours_to_credit IS NOT NULL ORDER BY hours_to_credit",
                    Double.class, corridor, r.get("template_id"), since);
            // The view rounds to 0.1 h: compare with the unrounded median within half a step.
            assertThat(((Number) r.get("median_hours")).doubleValue())
                    .isCloseTo(hours.get((hours.size() + 1) / 2 - 1), within(0.0501));
        }
        List<Double> medians = routes.stream().filter(r -> (Integer) r.get("cases") > 0)
                .map(r -> ((Number) r.get("median_hours")).doubleValue()).toList();
        assertThat(medians).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void lowSampleRouteFlagged() throws Exception {
        DocumentContext d = json(BASE + "/USD-AED/route-performance");
        assertThat(d.read("$.routes[?(@.template_id == 'LDN')].low_sample", List.class)).containsExactly(true);
        assertThat(d.read("$.routes[?(@.template_id == 'LDN')].cases", List.class).getFirst()).matches(n -> (Integer) n < 50);
        assertThat(d.read("$.routes[?(@.template_id == 'PAY')].low_sample", List.class)).containsExactly(false);
    }

    // ------------------------------------------------------------------ FX
    @Test
    void fx() throws Exception {
        DocumentContext jpy = json(BASE + "/USD-JPY/fx");
        List<String> dates = jpy.read("$.points[*].rate_date");
        assertThat(dates).hasSize(90).isSorted();
        assertThat(dates.getLast()).isEqualTo(scalar(String.class,
                "SELECT MAX(rate_date) FROM fx_rate_daily WHERE currency_code = 'JPY'"));

        DocumentContext aed = json(BASE + "/USD-AED/fx?days=30");
        assertThat(aed.read("$.pegged_to_usd", Boolean.class)).isTrue();
        assertThat(aed.read("$.points", List.class)).hasSize(30);
        double aedVol = aed.read("$.annualised_volatility_pct", Double.class);
        double jpyVol = jpy.read("$.annualised_volatility_pct", Double.class);
        assertThat(aedVol).isLessThan(1);
        assertThat(jpyVol).isGreaterThan(1);
    }

    @Test
    void volatilityMatchesPython() {
        // Expected values from the Python definition: round(statistics.stdev(log returns) * sqrt(365) * 100, 2)
        assertThat(HistoryRepository.annualisedVolatilityPct(List.of(147.85, 148.10, 147.62, 148.91, 149.30, 148.75)))
                .isEqualTo(9.66);
        assertThat(HistoryRepository.annualisedVolatilityPct(List.of(1.0, 1.01, 1.0201, 1.0))).isEqualTo(32.93);
        assertThat(HistoryRepository.annualisedVolatilityPct(List.of(1.0, 1.01))).isNull();
        // Python's round(): ties to even on the double's exact value
        assertThat(HistoryRepository.round(0.125, 2)).isEqualTo(0.12);
        assertThat(HistoryRepository.round(2.675, 2)).isEqualTo(2.67);
    }

    // ------------------------------------------------------------------ incidents
    @Test
    void incidentsTotalsAndPaging() throws Exception {
        int raw = scalar(Integer.class, "SELECT COUNT(*) FROM incident i JOIN payment p USING (payment_id) "
                + "WHERE p.corridor_id = 'USD-INR'");
        DocumentContext p1 = json(BASE + "/USD-INR/incidents?limit=20");
        DocumentContext p2 = json(BASE + "/USD-INR/incidents?limit=20&offset=20");
        assertThat(p1.read("$.total", Integer.class)).isEqualTo(raw);
        assertThat(sum(p1.read("$.categories[*].incidents"))).isEqualTo(raw);
        assertThat(p1.read("$.items", List.class)).hasSize(20);
        List<Integer> ids1 = p1.read("$.items[*].incident_id");
        List<Integer> ids2 = p2.read("$.items[*].incident_id");
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);
        List<String> stamps = new java.util.ArrayList<>(p1.read("$.items[*].released_at_utc"));
        stamps.addAll(p2.read("$.items[*].released_at_utc"));
        assertThat(stamps).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void incidentsCategoryFilter() throws Exception {
        DocumentContext d = json(BASE + "/USD-JPY/incidents?category=batch&limit=200");
        List<Integer> batch = d.read("$.categories[?(@.category == 'batch')].incidents");
        int total = d.read("$.total");
        assertThat(total).isEqualTo(batch.getFirst()).isEqualTo(d.read("$.items", List.class).size());
        assertThat(new HashSet<>(d.read("$.items[*].category", List.class))).containsExactly("batch");
    }

    // ------------------------------------------------------------------ cases
    @Test
    void casesTotalsFiltersAndOrder() throws Exception {
        DocumentContext d = json(BASE + "/USD-EUR/cases?limit=40");
        assertThat(d.read("$.total", Integer.class))
                .isEqualTo(scalar(Integer.class, "SELECT COUNT(*) FROM payment WHERE corridor_id = 'USD-EUR'"));
        assertThat(d.read("$.items", List.class)).hasSize(40);
        List<String> stamps = d.read("$.items[*].released_at_utc");
        assertThat(stamps).isSortedAccordingTo(Comparator.reverseOrder());

        DocumentContext f = json(BASE + "/USD-EUR/cases?route=LDN&outcome=Returned&limit=200");
        assertThat(f.read("$.total", Integer.class)).isEqualTo(scalar(Integer.class, "SELECT COUNT(*) FROM payment "
                + "WHERE corridor_id = 'USD-EUR' AND template_id = 'LDN' AND outcome = 'Returned'"));
        List<Map<String, Object>> items = f.read("$.items");
        assertThat(items).isNotEmpty().allSatisfy(x -> {
            assertThat(x.get("template_id")).isEqualTo("LDN");
            assertThat(x.get("hours_to_credit")).isNull();
        });
    }

    // ------------------------------------------------------------------ similar cases
    @Test
    void similarCasesFindsSeededIncident() throws Exception {
        // The India 2nd-Saturday seed: Express, $7,000, retail, released Friday 11 Sep 2026.
        DocumentContext d = json(BASE + "/USD-INR/similar-cases?amount_usd=7000&client_type=Retail"
                + "&value_date=2026-09-11&template_id=EXP");
        assertThat(d.read("$.cases", List.class)).hasSize(6);
        assertThat(d.read("$.cases[0].value_date", String.class)).isEqualTo("2026-09-11");
        assertThat(d.read("$.cases[0].template_id", String.class)).isEqualTo("EXP");
        assertThat(d.read("$.cases[0].amount_usd", Double.class)).isEqualTo(7000.0);
        assertThat(d.read("$.cases[0].ops_note", String.class)).contains("2nd Saturday");
        assertThat(d.read("$.route_summary.template_id", String.class)).isEqualTo("EXP");
        assertThat(d.read("$.route_summary.cases", Integer.class)).isGreaterThan(50);
    }

    @Test
    void similarCasesWithoutRoute() throws Exception {
        DocumentContext d = json(BASE + "/USD-SAR/similar-cases?amount_usd=30000&client_type=FI&value_date=2026-10-05");
        assertThat(d.read("$.route_summary", Object.class)).isNull();
        assertThat(d.read("$.cases", List.class)).isNotEmpty();
    }

    // ------------------------------------------------------------------ errors, CORS, portal
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/USD-XXX/cases | 404",
            "/USD-JPY/cases?route=NOPE | 422",
            "/USD-JPY/cases?route= | 422",
            "/USD-JPY/cases?limit=500 | 422",
            "/USD-JPY/cases?limit=abc | 422",
            "/USD-JPY/incidents?category=weather | 422",
            "/USD-JPY/incidents?offset=-1 | 422",
            "/USD-JPY/fx?days=3 | 422",
            "/USD-JPY/similar-cases?amount_usd=0&client_type=FI&value_date=2026-10-05 | 422",
            "/USD-JPY/similar-cases?amount_usd=10&client_type=FI | 422",
            "/USD-JPY/similar-cases?amount_usd=10&client_type=Bank&value_date=2026-10-05 | 422",
            "/USD-JPY/similar-cases?amount_usd=10&client_type=FI&value_date=05-10-2026 | 422",
    })
    void errors(String path, int expected) throws Exception {
        MvcResult r = mvc.perform(get(BASE + path)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(expected);
        assertThat(JsonPath.parse(r.getResponse().getContentAsString()).read("$.detail", String.class)).isNotBlank();
    }

    @Test
    void corsAllowsLocalPagesOnly() throws Exception {
        for (String origin : List.of("null", "http://localhost:5500", "http://127.0.0.1:8000", "https://localhost:3000")) {
            String allowed = mvc.perform(get(BASE).header("Origin", origin)).andReturn().getResponse()
                    .getHeader("Access-Control-Allow-Origin");
            assertThat(allowed).as(origin).isEqualTo(origin);
        }
        // Spring rejects a disallowed cross-origin request outright (403) rather than omitting the header.
        var other = mvc.perform(get(BASE).header("Origin", "https://example.com")).andReturn().getResponse();
        assertThat(other.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void portalServed() throws Exception {
        var response = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse();
        // Without charset=UTF-8 browsers decode the page as Windows-1252 and show "Â·" for "·".
        assertThat(response.getContentType()).isEqualToIgnoringCase("text/html;charset=UTF-8");
        String html = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).startsWith("<meta charset=\"utf-8\">");
        assertThat(html).contains("<title>CorridorIQ", "/v1/history/corridors", "USD → ", " · ");
    }

    private static int sum(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).sum();
    }
}
