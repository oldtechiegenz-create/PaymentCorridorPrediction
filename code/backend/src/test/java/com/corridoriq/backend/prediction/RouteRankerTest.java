package com.corridoriq.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.corridoriq.backend.engine.BankCalendar;
import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.PredictionModels.RankResponse;
import com.corridoriq.backend.prediction.PredictionModels.RouteFeatures;
import com.corridoriq.backend.prediction.PredictionModels.RouteResult;
import com.corridoriq.backend.prediction.PredictionModels.RouteScore;
import com.corridoriq.backend.prediction.PredictionModels.ScoreRequest;
import com.corridoriq.backend.prediction.PredictionModels.ScoreResponse;
import com.corridoriq.backend.web.ApiException;

/** The backend's side of ranking: validation, the features sent to the models, and assembling the answer. */
class RouteRankerTest {

    /** Stands in for the Python service: records what it was sent and scores routes as the test says. */
    static final class FakeScorer implements RouteScorer {
        ScoreRequest last;
        int calls;
        ToDoubleFunction<RouteFeatures> modelScore = f -> -f.estHours();   // faster is better, unless a test says otherwise
        ScoreResponse override;

        @Override
        public ScoreResponse score(ScoreRequest request) {
            calls++;
            last = request;
            if (override != null) {
                return override;
            }
            List<RouteScore> scores = new ArrayList<>();
            for (RouteFeatures f : request.routes()) {
                scores.add(new RouteScore(f.templateId(), modelScore.applyAsDouble(f), f.estHours() * 1.1,
                        f.estCostUsd() * 1.05, -f.estCostUsd()));
            }
            return new ScoreResponse("fake-model", scores);
        }
    }

    private final FakeScorer scorer = new FakeScorer();
    private RouteRanker ranker;

    @BeforeEach
    void setUp() {
        scorer.calls = 0;
        ranker = new RouteRanker(scorer, BankCalendar.bundled(null));
    }

    private static PaymentRequest payment(String corridor, String bank, String client, double amount, String releasedAt) {
        return new PaymentRequest(corridor, bank, client, amount, 60, OffsetDateTime.parse(releasedAt), false);
    }

    /** Thursday 23 April 2026, 15:30 in New York. */
    private static final String THURSDAY_AFTERNOON = "2026-04-23T19:30:00Z";

    private static RouteResult route(RankResponse r, String templateId) {
        return r.routes().stream().filter(x -> x.templateId().equals(templateId)).findFirst().orElseThrow();
    }

    private static RouteFeatures sent(ScoreRequest r, String templateId) {
        return r.routes().stream().filter(x -> x.templateId().equals(templateId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ what the models are sent
    @Test
    void onlyEligibleRoutesAreSentToTheModelsWithTheirSimulatorFeatures() {
        ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, THURSDAY_AFTERNOON));
        assertThat(scorer.last.routes()).extracting(RouteFeatures::templateId)
                .containsExactly("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO");

        RouteFeatures exp = sent(scorer.last, "EXP");
        assertThat(exp.estHours()).isEqualTo(8.0);              // credited the next Japanese morning
        assertThat(exp.naiveHours()).isEqualTo(5.5);
        assertThat(exp.feeTotalUsd()).isEqualTo(60.0);          // 30 + 18 + 12
        assertThat(exp.fxBps()).isEqualTo(38.0);
        assertThat(exp.estCostUsd()).isEqualTo(75.2);           // 60 + 4,000 x 38 bps
        assertThat(exp.hopCount()).isEqualTo(4);
        assertThat(exp.intermediaries()).isEqualTo(2);
        assertThat(exp.cutoffSlackHours()).isEqualTo(0.5);      // 15:30 to the 16:00 cut-off
        assertThat(exp.calendarConflict()).isZero();
        assertThat(exp.maxRisk()).isEqualTo(1);
        assertThat(exp.stpRateNominal()).isEqualTo(0.972);
    }

    @Test
    void routesTheClientCannotUseAreNeverScored() {
        ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 30000, THURSDAY_AFTERNOON));   // over both caps
        assertThat(scorer.last.routes()).extracting(RouteFeatures::templateId).containsExactly("EXP", "ASIA", "LDN", "GULF");

        ranker.rank(payment("USD-JPY", "JP_B1", "FI", 4000, THURSDAY_AFTERNOON));        // payout is retail only
        assertThat(scorer.last.routes()).extracting(RouteFeatures::templateId)
                .containsExactly("EXP", "ASIA", "LDN", "GULF", "ECO");
        assertThat(sent(scorer.last, "EXP").feeTotalUsd()).isEqualTo(45.0);   // FI first-hop fee capped at 15
    }

    @Test
    void theWeekendShowsUpInTheFeaturesTheModelsSee() {
        ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, "2026-04-24T19:30:00Z"));   // Friday 15:30
        assertThat(sent(scorer.last, "EXP").estHours()).isEqualTo(56.0);
        assertThat(sent(scorer.last, "EXP").estNonbusinessDays()).isEqualTo(2);
        assertThat(sent(scorer.last, "EXP").naiveHours()).isEqualTo(5.5);     // the old view sees no weekend
        assertThat(sent(scorer.last, "GULF").estHours()).isEqualTo(58.5);
        assertThat(sent(scorer.last, "GULF").calendarConflict()).isEqualTo(1);   // Bahrain is shut on the value date
        assertThat(sent(scorer.last, "EXP").calendarConflict()).isZero();         // New York is open
        ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, THURSDAY_AFTERNOON));
        assertThat(sent(scorer.last, "GULF").calendarConflict()).isEqualTo(1);   // shut the next day, Friday
        ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, "2026-04-20T19:30:00Z"));   // Monday: Monday and Tuesday are open
        assertThat(sent(scorer.last, "GULF").calendarConflict()).isZero();
    }

    @Test
    void paymentContextIsComputedOnTheUsValueDateInNewYorkTime() {
        // 13:01 UTC is 09:01 in New York (Wednesday 1 April 2026).
        ranker.rank(new PaymentRequest("USD-INR", "IN_B2", "Retail", 580.0, 15,
                OffsetDateTime.parse("2026-04-01T13:01:00Z"), true));
        var p = scorer.last.payment();
        assertThat(p.corridorId()).isEqualTo("USD-INR");
        assertThat(p.beneBankId()).isEqualTo("IN_B2");
        assertThat(p.clientType()).isEqualTo("Retail");
        assertThat(p.amountUsd()).isEqualTo(580.0);
        assertThat(p.priority()).isEqualTo(15);
        assertThat(p.releaseHourEt()).isEqualTo(9.02);          // 9 + 1/60, to 2 decimals
        assertThat(p.releaseWeekday()).isEqualTo(3);            // Wednesday
        assertThat(p.dayOfMonth()).isEqualTo(1);
        assertThat(p.hasDeadline()).isEqualTo(1);
    }

    @Test
    void aTimeAfterMidnightUtcStillCountsAsTheEarlierUsDay() {
        // 01:30 UTC on 2 April is 21:30 on 1 April in New York, and a different offset gives the same instant.
        ranker.rank(payment("USD-EUR", "DE_B1", "Retail", 1000, "2026-04-02T01:30:00Z"));
        assertThat(scorer.last.payment().dayOfMonth()).isEqualTo(1);
        assertThat(scorer.last.payment().releaseWeekday()).isEqualTo(3);
        assertThat(scorer.last.payment().releaseHourEt()).isEqualTo(21.5);
        ranker.rank(payment("USD-EUR", "DE_B1", "Retail", 1000, "2026-04-01T21:30:00-04:00"));
        assertThat(scorer.last.payment().releaseHourEt()).isEqualTo(21.5);
        assertThat(scorer.last.payment().hasDeadline()).isZero();
    }

    // ------------------------------------------------------------------ the answer
    @Test
    void eligibleRoutesAreRankedByModelScoreThenIneligibleOnesFollowWithTheirReasons() {
        // Make the economy route the model's favourite, then the payout partner, whatever the simulator says.
        Map<String, Double> favourite = Map.of("ECO", 9.0, "PAY", 5.0, "EXP", 1.0, "ASIA", 0.5, "LDN", 0.1, "GULF", -1.0);
        scorer.modelScore = f -> favourite.get(f.templateId());
        RankResponse r = ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, THURSDAY_AFTERNOON));

        assertThat(r.modelVersion()).isEqualTo("fake-model");
        assertThat(r.recommendedTemplateId()).isEqualTo("ECO");
        assertThat(r.routes()).extracting(RouteResult::templateId).containsExactly("ECO", "PAY", "EXP", "ASIA", "LDN", "GULF");
        assertThat(r.routes()).extracting(RouteResult::rank).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void ineligibleRoutesComeLastWithNoRankAndNoModelOutputs() {
        RankResponse r = ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 30000, THURSDAY_AFTERNOON));
        assertThat(r.routes()).hasSize(6);
        assertThat(r.routes().subList(0, 4)).allMatch(RouteResult::eligible);
        assertThat(r.routes().subList(0, 4)).extracting(RouteResult::rank).containsExactly(1, 2, 3, 4);

        List<RouteResult> out = r.routes().subList(4, 6);
        assertThat(out).extracting(RouteResult::templateId).containsExactly("PAY", "ECO");   // corridor order
        for (RouteResult x : out) {
            assertThat(x.eligible()).isFalse();
            assertThat(x.rank()).isNull();
            assertThat(x.modelScore()).isNull();
            assertThat(x.predictedHours()).isNull();
            assertThat(x.predictedCostUsd()).isNull();
            assertThat(x.heuristicScore()).isNull();
            assertThat(x.ineligibleReasons()).isNotEmpty();
            assertThat(x.estHours()).isPositive();   // the simulator's estimate is still shown
        }
        assertThat(route(r, "PAY").ineligibleReasons()).containsExactly("Above the $5,000 per-transfer cap");
        assertThat(route(r, "ECO").ineligibleReasons()).containsExactly("Above the $25,000 per-transfer cap");
    }

    @Test
    void theModelsOutputsAndTheSimulatorsEstimateAreBothInTheAnswer() {
        RankResponse r = ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, THURSDAY_AFTERNOON));
        RouteResult exp = route(r, "EXP");
        assertThat(exp.name()).isEqualTo("Express gpi");
        assertThat(exp.via()).isEqualTo("New York direct");
        assertThat(exp.estHours()).isEqualTo(8.0);
        assertThat(exp.predictedHours()).isCloseTo(8.0 * 1.1, within(1e-9));        // the fake scales est_hours by 1.1
        assertThat(exp.predictedCostUsd()).isCloseTo(75.2 * 1.05, within(1e-9));
        assertThat(exp.modelScore()).isEqualTo(-8.0);
        assertThat(exp.heuristicScore()).isEqualTo(-75.2);
        assertThat(exp.cutoffSlackHours()).isEqualTo(0.5);
        assertThat(exp.stpRateNominal()).isEqualTo(0.972);
    }

    @Test
    void tiesKeepTheCorridorsRouteOrder() {
        scorer.modelScore = f -> 1.0;
        RankResponse r = ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 4000, THURSDAY_AFTERNOON));
        assertThat(r.routes()).extracting(RouteResult::templateId).containsExactly("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO");
    }

    // ------------------------------------------------------------------ bad payments
    @Test
    void anUnknownCorridorIsRejectedWithoutCallingTheModels() {
        assertThatThrownBy(() -> ranker.rank(payment("USD-XXX", "JP_B1", "Retail", 100, THURSDAY_AFTERNOON)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getMessage()).contains("Unknown corridor USD-XXX").contains("USD-JPY");
                });
        assertThat(scorer.calls).isZero();
    }

    @Test
    void aBeneficiaryBankFromAnotherCorridorIsRejected() {
        assertThatThrownBy(() -> ranker.rank(payment("USD-INR", "JP_B1", "Retail", 100, THURSDAY_AFTERNOON)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getMessage()).contains("JP_B1 is not a beneficiary bank on USD-INR")
                            .contains("IN_B1", "IN_B2", "IN_B3");
                });
        assertThat(scorer.calls).isZero();
    }

    @Test
    void aReleaseDateOutsideTheBankingCalendarIsRejected() {
        for (String when : List.of("2031-04-01T13:01:00Z", "2020-04-01T13:01:00Z")) {
            assertThatThrownBy(() -> ranker.rank(payment("USD-INR", "IN_B2", "Retail", 100, when)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(e.getMessage()).isEqualTo("Banking calendar only covers 2023-2027");
                    });
        }
        assertThat(scorer.calls).isZero();
    }

    // ------------------------------------------------------------------ the scoring service misbehaving
    @Test
    void scoresForTheWrongRoutesAreABadGateway() {
        scorer.override = new ScoreResponse("fake-model", List.of(new RouteScore("EXP", 1, 1, 1, 1)));   // 3 of 4 missing
        assertThatThrownBy(() -> ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 30000, THURSDAY_AFTERNOON)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).contains("[EXP]").contains("were sent");
                });

        scorer.override = new ScoreResponse("fake-model", List.of(new RouteScore("EXP", 1, 1, 1, 1),
                new RouteScore("ASIA", 1, 1, 1, 1), new RouteScore("LDN", 1, 1, 1, 1), new RouteScore("PAY", 1, 1, 1, 1)));
        assertThatThrownBy(() -> ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 30000, THURSDAY_AFTERNOON)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        scorer.override = new ScoreResponse("fake-model", null);
        assertThatThrownBy(() -> ranker.rank(payment("USD-JPY", "JP_B1", "Retail", 30000, THURSDAY_AFTERNOON)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
