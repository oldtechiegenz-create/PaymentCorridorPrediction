package com.corridoriq.backend.prediction;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * The contract with the Python prediction service (code/prediction_service/schemas.py), in snake_case JSON.
 * Keep the two in step: the Python side is the source of truth for the model's inputs and outputs.
 */
public final class PredictionModels {

    private PredictionModels() {
    }

    /**
     * One payment to rank routes for. The prediction service also checks that the corridor exists and that
     * the beneficiary bank belongs to it, and returns 422 otherwise.
     *
     * @param priority    0 = cheapest route preferred, 100 = fastest
     * @param releasedAt  release time with an offset, e.g. 2026-04-01T13:01:00Z
     * @param hasDeadline optional, false when omitted (as in the prediction service)
     */
    public record PaymentRequest(
            @NotBlank String corridorId,
            @NotBlank String beneBankId,
            @NotNull @Pattern(regexp = "Retail|FI", message = "must be Retail or FI") String clientType,
            @NotNull @Positive Double amountUsd,
            @NotNull @Min(0) @Max(100) Integer priority,
            @NotNull OffsetDateTime releasedAt,
            Boolean hasDeadline) {

        public PaymentRequest {
            hasDeadline = hasDeadline != null && hasDeadline;
        }
    }

    /**
     * One candidate route. Eligible routes carry a rank (1 = recommended) and the model outputs; ineligible
     * routes have null rank and model fields, and list their reasons.
     *
     * @param estHours       simulator estimate, calendar-aware
     * @param predictedHours model's time to credit: simulator estimate x learned correction
     * @param modelScore     ranker score; only comparable within one payment
     * @param heuristicScore MVP 1 v1 formula score, for comparison
     */
    public record RouteResult(Integer rank, String templateId, String name, String via, boolean eligible,
                              List<String> ineligibleReasons, double estHours, double naiveHours, double estCostUsd,
                              double feeTotalUsd, double fxBps, int hopCount, int intermediaries,
                              int estNonbusinessDays, boolean calendarConflict, double cutoffSlackHours, int maxRisk,
                              double stpRateNominal, Double predictedHours, Double predictedCostUsd,
                              Double modelScore, Double heuristicScore) {
    }

    public record RankResponse(String modelVersion, String recommendedTemplateId, List<RouteResult> routes) {
    }

    /** trainedUntil: the models saw value dates before this; null = all history. */
    public record ModelHealth(String status, String modelVersion, String trainedUntil, List<Integer> calendarYears) {
    }
}
