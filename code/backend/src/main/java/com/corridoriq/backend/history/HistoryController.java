package com.corridoriq.backend.history;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.corridoriq.backend.history.HistoryModels.CasePage;
import com.corridoriq.backend.history.HistoryModels.CorridorList;
import com.corridoriq.backend.history.HistoryModels.CorridorSummary;
import com.corridoriq.backend.history.HistoryModels.FxPoint;
import com.corridoriq.backend.history.HistoryModels.FxResponse;
import com.corridoriq.backend.history.HistoryModels.IncidentPage;
import com.corridoriq.backend.history.HistoryModels.RoutePerformanceResponse;
import com.corridoriq.backend.history.HistoryModels.SimilarCasesResponse;
import com.corridoriq.backend.web.ApiException;

/**
 * History endpoints: the data behind the Historical Cases tab and the similar-cases panel.
 * All read-only GETs under /v1/history. Unknown corridors return 404, bad parameters 422.
 */
@RestController
@RequestMapping("/v1/history")
public class HistoryController {

    private final HistoryRepository repo;

    public HistoryController(HistoryRepository repo) {
        this.repo = repo;
    }

    /** Corridors with case counts and date range. */
    @GetMapping("/corridors")
    public CorridorList corridors() {
        return new CorridorList(repo.window(), repo.corridors());
    }

    /** Per-route time to credit, cost and STP over the last 18 months of data. */
    @GetMapping("/corridors/{corridorId}/route-performance")
    public RoutePerformanceResponse routePerformance(@PathVariable String corridorId) {
        CorridorSummary c = corridor(corridorId);
        return new RoutePerformanceResponse(c.corridorId(), repo.window(), HistoryRepository.LOW_SAMPLE_THRESHOLD,
                repo.routePerformance(c.corridorId()));
    }

    /** Daily FX mid rate and annualised volatility for the last {@code days} days of data (7 to 365). */
    @GetMapping("/corridors/{corridorId}/fx")
    public FxResponse fx(@PathVariable String corridorId, @RequestParam(defaultValue = "90") int days) {
        CorridorSummary c = corridor(corridorId);
        range("days", days, 7, 365);
        List<FxPoint> points = repo.fxSeries(c.corridorId(), days);
        return new FxResponse(c.corridorId(), c.currencyCode(), c.peggedToUsd(), days,
                HistoryRepository.annualisedVolatilityPct(points.stream().map(FxPoint::midRate).toList()), points);
    }

    /** Ops-annotated incidents, newest first; {@code category} omitted = all. */
    @GetMapping("/corridors/{corridorId}/incidents")
    public IncidentPage incidents(@PathVariable String corridorId,
                                  @RequestParam(required = false) String category,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        CorridorSummary c = corridor(corridorId);
        oneOf("category", category, HistoryModels.CATEGORIES);
        range("limit", limit, 1, 200);
        range("offset", offset, 0, Integer.MAX_VALUE);
        return new IncidentPage(c.corridorId(), category, repo.incidentCount(c.corridorId(), category), limit, offset,
                repo.incidentCategories(c.corridorId()), repo.incidents(c.corridorId(), category, limit, offset));
    }

    /** All cases, newest first; {@code route} (template id) and {@code outcome} omitted = all. */
    @GetMapping("/corridors/{corridorId}/cases")
    public CasePage cases(@PathVariable String corridorId,
                          @RequestParam(required = false) String route,
                          @RequestParam(required = false) String outcome,
                          @RequestParam(defaultValue = "40") int limit,
                          @RequestParam(defaultValue = "0") int offset) {
        CorridorSummary c = corridor(corridorId);
        oneOf("route", route, repo.templateIds());
        oneOf("outcome", outcome, HistoryModels.OUTCOMES);
        range("limit", limit, 1, 200);
        range("offset", offset, 0, Integer.MAX_VALUE);
        return new CasePage(c.corridorId(), route, outcome, repo.caseCount(c.corridorId(), route, outcome), limit,
                offset, repo.cases(c.corridorId(), route, outcome, limit, offset));
    }

    /**
     * Past payments most like a new request (Route tab). {@code value_date} is the new payment's value date
     * (matched on weekday); {@code template_id}, the recommended route, adds its 18-month summary.
     */
    @GetMapping("/corridors/{corridorId}/similar-cases")
    public SimilarCasesResponse similarCases(@PathVariable String corridorId,
                                             @RequestParam("amount_usd") double amountUsd,
                                             @RequestParam("client_type") String clientType,
                                             @RequestParam("value_date") String valueDate,
                                             @RequestParam(name = "template_id", required = false) String templateId,
                                             @RequestParam(defaultValue = "6") int limit) {
        CorridorSummary c = corridor(corridorId);
        if (!(amountUsd > 0)) {
            throw ApiException.invalid("amount_usd must be greater than 0");
        }
        oneOf("client_type", clientType, HistoryModels.CLIENT_TYPES);
        String date = isoDate("value_date", valueDate);
        oneOf("template_id", templateId, repo.templateIds());
        range("limit", limit, 1, 20);
        return new SimilarCasesResponse(c.corridorId(),
                templateId == null ? null : repo.routeSummary(c.corridorId(), templateId).orElse(null),
                repo.similarCases(c.corridorId(), amountUsd, clientType, date, limit));
    }

    // ------------------------------------------------------------------ validation
    private CorridorSummary corridor(String corridorId) {
        return repo.corridor(corridorId).orElseThrow(() -> ApiException.notFound(
                "Unknown corridor '" + corridorId + "'; see /v1/history/corridors"));
    }

    private static void range(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw ApiException.invalid(max == Integer.MAX_VALUE
                    ? name + " must be at least " + min
                    : name + " must be between " + min + " and " + max);
        }
    }

    /** null means "not given" and passes; anything else must be one of the allowed values. */
    private static void oneOf(String name, String value, Collection<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw ApiException.invalid(name + " must be one of " + String.join(", ", allowed));
        }
    }

    private static String isoDate(String name, String value) {
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw ApiException.invalid(name + " must be a date as YYYY-MM-DD");
        }
    }
}
