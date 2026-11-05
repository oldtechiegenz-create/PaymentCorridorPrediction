package com.corridoriq.backend.history;

import java.util.List;

/**
 * Response bodies of the history API (Historical Cases tab and similar cases). Field names are
 * serialised in snake_case. Dates are 'YYYY-MM-DD' strings and timestamps ISO 8601 UTC strings,
 * exactly as stored in the history database.
 */
public final class HistoryModels {

    private HistoryModels() {
    }

    /** Cause categories of annotated incidents. */
    public static final List<String> CATEGORIES = List.of("calendar", "cutoff", "batch", "repair", "return");
    public static final List<String> OUTCOMES = List.of("Credited", "Repaired", "Returned");
    public static final List<String> CLIENT_TYPES = List.of("Retail", "FI");

    /** asOf: newest value date in the history; windowStart: first value date of the 18-month window. */
    public record HistoryWindow(String asOf, String windowStart) {
    }

    public record CorridorSummary(String corridorId, String destCountry, String countryName, String currencyCode,
                                  String currencyName, boolean peggedToUsd, String localRail, int cases,
                                  String firstValueDate, String lastValueDate, int incidents) {
    }

    public record CorridorList(HistoryWindow window, List<CorridorSummary> corridors) {
    }

    /**
     * medianHours and p90Hours are null with no credited cases; costBps is value-weighted; stpPct is the
     * share with no manual intervention; lowSample marks fewer cases than the threshold (indicative only).
     */
    public record RoutePerformance(String templateId, String routeName, String via, int cases, Double medianHours,
                                   Double p90Hours, Double avgCostUsd, Double costBps, Double stpPct,
                                   Double returnedPct, boolean lowSample) {
    }

    public record RoutePerformanceResponse(String corridorId, HistoryWindow window, int lowSampleThreshold,
                                           List<RoutePerformance> routes) {
    }

    public record FxPoint(String rateDate, double midRate) {
    }

    /** annualisedVolatilityPct: stdev of daily log returns x sqrt(365); null with fewer than 3 points. */
    public record FxResponse(String corridorId, String currencyCode, boolean peggedToUsd, int days,
                             Double annualisedVolatilityPct, List<FxPoint> points) {
    }

    public record CategoryCount(String category, int incidents) {
    }

    /** hoursToCredit is null when the payment was returned. */
    public record Incident(long incidentId, long paymentId, String valueDate, String releasedAtUtc, String templateId,
                           String routeName, double amountUsd, String clientType, Double hoursToCredit,
                           String outcome, String causeCode, String category, String cause, String note) {
    }

    /** total: incidents matching the filter; categories: counts for the whole corridor, for the filter chips. */
    public record IncidentPage(String corridorId, String category, int total, int limit, int offset,
                               List<CategoryCount> categories, List<Incident> items) {
    }

    public record Case(long paymentId, String uetr, String valueDate, String releasedAtUtc, String templateId,
                       String routeName, double amountUsd, String clientType, String charges, String purposeCode,
                       Double hoursToCredit, double allInCostUsd, double costBps, int nonbusinessDays,
                       String outcome, boolean stp, String opsNote) {
    }

    public record CasePage(String corridorId, String route, String outcome, int total, int limit, int offset,
                           List<Case> items) {
    }

    public record SimilarCase(long paymentId, String valueDate, String releasedAtUtc, String templateId,
                              String routeName, double amountUsd, String clientType, Double hoursToCredit,
                              double allInCostUsd, String outcome, String opsNote) {
    }

    public record RouteSummary(String templateId, String routeName, int cases, Double medianHours, Double p90Hours,
                               boolean lowSample) {
    }

    /** routeSummary: 18-month stats for the requested template_id, or null when none was given. */
    public record SimilarCasesResponse(String corridorId, RouteSummary routeSummary, List<SimilarCase> cases) {
    }
}
