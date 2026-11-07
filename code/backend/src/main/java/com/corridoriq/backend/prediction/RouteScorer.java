package com.corridoriq.backend.prediction;

import com.corridoriq.backend.prediction.PredictionModels.ScoreRequest;
import com.corridoriq.backend.prediction.PredictionModels.ScoreResponse;

/** Whatever scores the eligible routes of a payment: in production the Python service, in tests a stand-in. */
@FunctionalInterface
public interface RouteScorer {

    ScoreResponse score(ScoreRequest request);
}
