package com.corridoriq.backend.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.corridoriq.backend.history.HistoryRepository;
import com.corridoriq.backend.prediction.PredictionClient;
import com.corridoriq.backend.prediction.PredictionModels.ModelHealth;
import com.corridoriq.backend.prediction.PredictionServiceException;

/**
 * Backend health. "ok" when the prediction service answers too, "degraded" when it does not: the
 * history API still works then, but route ranking returns 503.
 */
@RestController
public class HealthController {

    public record PredictionStatus(String url, String status, ModelHealth model, String error) {
    }

    public record Health(String status, String historyAsOf, PredictionStatus predictionService) {
    }

    private final HistoryRepository history;
    private final PredictionClient prediction;

    public HealthController(HistoryRepository history, PredictionClient prediction) {
        this.history = history;
        this.prediction = prediction;
    }

    @GetMapping("/health")
    public Health health() {
        PredictionStatus ps;
        try {
            ps = new PredictionStatus(prediction.baseUrl(), "ok", prediction.health(), null);
        } catch (PredictionServiceException e) {
            ps = new PredictionStatus(prediction.baseUrl(), "unavailable", null, e.getMessage());
        }
        return new Health("ok".equals(ps.status()) ? "ok" : "degraded", history.window().asOf(), ps);
    }
}
