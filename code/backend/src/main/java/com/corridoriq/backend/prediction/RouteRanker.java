package com.corridoriq.backend.prediction;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.corridoriq.backend.engine.BankCalendar;
import com.corridoriq.backend.engine.Dates;
import com.corridoriq.backend.engine.Reference;
import com.corridoriq.backend.engine.Reference.BeneShare;
import com.corridoriq.backend.engine.Reference.Corridor;
import com.corridoriq.backend.engine.Reference.Template;
import com.corridoriq.backend.engine.RouteEstimator;
import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.PredictionModels.RankResponse;
import com.corridoriq.backend.prediction.PredictionModels.RouteFeatures;
import com.corridoriq.backend.prediction.PredictionModels.RouteResult;
import com.corridoriq.backend.prediction.PredictionModels.RouteScore;
import com.corridoriq.backend.prediction.PredictionModels.ScorePayment;
import com.corridoriq.backend.prediction.PredictionModels.ScoreRequest;
import com.corridoriq.backend.prediction.PredictionModels.ScoreResponse;
import com.corridoriq.backend.web.ApiException;

/**
 * Ranks the routes of a payment. The backend validates the payment, simulates every route of the corridor
 * ({@link RouteEstimator}) to get the features the models read, asks the Python service to score the eligible
 * ones, and orders the routes by that score. Python holds the models and nothing else.
 */
@Component
public class RouteRanker {

    private static final ZoneId US = Reference.zone("US");

    private final RouteScorer scorer;
    private final BankCalendar calendar;

    public RouteRanker(RouteScorer scorer, BankCalendar calendar) {
        this.scorer = scorer;
        this.calendar = calendar;
    }

    /** Every route of the corridor: eligible ones ranked best first, then ineligible ones with their reasons. */
    public RankResponse rank(PaymentRequest payment) {
        Instant releasedAt = validate(payment);

        List<RouteEstimate> estimates = RouteEstimator.estimate(payment.corridorId(), payment.beneBankId(),
                payment.clientType(), payment.amountUsd(), releasedAt, calendar).stream()
                .map(RouteEstimate::rounded).toList();   // rounded as route_candidate stores them, which is what the models saw
        List<RouteEstimate> eligible = estimates.stream().filter(RouteEstimate::eligible).toList();
        if (eligible.isEmpty()) {
            throw ApiException.invalid("No route is available for this payment");
        }

        ScoreResponse scored = scorer.score(new ScoreRequest(context(payment, releasedAt),
                eligible.stream().map(RouteRanker::features).toList()));
        Map<String, RouteScore> scores = scoresByTemplate(scored, eligible);

        List<RouteEstimate> ranked = new ArrayList<>(eligible);
        ranked.sort(Comparator.comparingDouble((RouteEstimate e) -> scores.get(e.templateId()).modelScore()).reversed());

        List<RouteResult> routes = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            routes.add(result(ranked.get(i), i + 1, scores.get(ranked.get(i).templateId())));
        }
        for (RouteEstimate e : estimates) {
            if (!e.eligible()) {
                routes.add(result(e, null, null));
            }
        }
        return new RankResponse(scored.modelVersion(), routes.getFirst().templateId(), routes);
    }

    // ------------------------------------------------------------------ validation
    /** Checks what the models cannot: that the corridor and bank exist together and the calendar covers the date. */
    private Instant validate(PaymentRequest p) {
        Corridor corridor = Reference.corridor(p.corridorId());
        if (corridor == null) {
            throw ApiException.invalid("Unknown corridor " + p.corridorId() + "; expected one of "
                    + Reference.CORRIDORS.keySet());
        }
        List<String> banks = corridor.beneBanks().stream().map(BeneShare::bankId).toList();
        if (!banks.contains(p.beneBankId())) {
            throw ApiException.invalid(p.beneBankId() + " is not a beneficiary bank on " + p.corridorId()
                    + "; expected one of " + banks);
        }
        Instant releasedAt = p.releasedAt().toInstant();
        int year = releasedAt.atZone(US).getYear();
        int[] covered = calendar.holidayYears("US");
        if (covered == null || year < covered[0] || year > covered[1]) {
            throw ApiException.invalid("Banking calendar only covers "
                    + (covered == null ? "no years" : covered[0] + "-" + covered[1]));
        }
        return releasedAt;
    }

    // ------------------------------------------------------------------ what the models read
    /** The payment-level inputs, as the training view v_ranking_training defines them (US value date and hour). */
    static ScorePayment context(PaymentRequest p, Instant releasedAt) {
        ZonedDateTime local = releasedAt.atZone(US);
        return new ScorePayment(p.corridorId(), p.beneBankId(), p.clientType(), p.amountUsd(), p.priority(),
                Dates.round(local.getHour() + local.getMinute() / 60.0, 2), Dates.portalDow(local.toLocalDate()),
                local.getDayOfMonth(), Boolean.TRUE.equals(p.hasDeadline()) ? 1 : 0);
    }

    static RouteFeatures features(RouteEstimate e) {
        return new RouteFeatures(e.templateId(), e.estHours(), e.naiveHours(), e.estCostUsd(), e.feeTotalUsd(), e.fxBps(),
                e.hopCount(), e.intermediaries(), e.estNonbusinessDays(), e.calendarConflict() ? 1 : 0,
                e.cutoffSlackHours(), e.maxRisk(), e.stpRateNominal());
    }

    /** One score per eligible route and nothing else; anything different means the two services disagree. */
    private static Map<String, RouteScore> scoresByTemplate(ScoreResponse scored, List<RouteEstimate> eligible) {
        Map<String, RouteScore> byTemplate = new HashMap<>();
        if (scored != null && scored.scores() != null) {
            scored.scores().forEach(s -> byTemplate.put(s.templateId(), s));
        }
        Set<String> expected = eligible.stream().map(RouteEstimate::templateId).collect(Collectors.toSet());
        if (scored == null || scored.scores() == null || scored.scores().size() != expected.size()
                || !byTemplate.keySet().equals(expected)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The prediction service scored "
                    + (byTemplate.isEmpty() ? "no routes" : byTemplate.keySet()) + " but " + expected + " were sent");
        }
        return byTemplate;
    }

    private static RouteResult result(RouteEstimate e, Integer rank, RouteScore s) {
        Template tpl = Reference.template(e.templateId());
        return new RouteResult(rank, e.templateId(), tpl.name(), tpl.via(), e.eligible(), e.ineligibleReasons(),
                e.estHours(), e.naiveHours(), e.estCostUsd(), e.feeTotalUsd(), e.fxBps(), e.hopCount(),
                e.intermediaries(), e.estNonbusinessDays(), e.calendarConflict(), e.cutoffSlackHours(), e.maxRisk(),
                e.stpRateNominal(), s == null ? null : s.predictedHours(), s == null ? null : s.predictedCostUsd(),
                s == null ? null : s.modelScore(), s == null ? null : s.heuristicScore());
    }
}
