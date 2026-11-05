package com.corridoriq.backend.prediction;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.PredictionModels.RankResponse;

/**
 * Route ranking for one payment. The backend validates the request and forwards it to the Python
 * prediction service, which computes the features and runs the models.
 */
@RestController
public class PredictionController {

    private final PredictionClient prediction;

    public PredictionController(PredictionClient prediction) {
        this.prediction = prediction;
    }

    @PostMapping("/v1/routes/rank")
    public RankResponse rankRoutes(@Valid @RequestBody PaymentRequest payment) {
        return prediction.rankRoutes(payment);
    }
}
