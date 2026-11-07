package com.corridoriq.backend.prediction;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.PredictionModels.RankResponse;

/**
 * Route ranking for one payment. {@link RouteRanker} validates the request, simulates the routes and has the
 * Python service score them.
 */
@RestController
public class PredictionController {

    private final RouteRanker ranker;

    public PredictionController(RouteRanker ranker) {
        this.ranker = ranker;
    }

    @PostMapping("/v1/routes/rank")
    public RankResponse rankRoutes(@Valid @RequestBody PaymentRequest payment) {
        return ranker.rank(payment);
    }
}
