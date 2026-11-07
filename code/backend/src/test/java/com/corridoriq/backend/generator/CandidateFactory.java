package com.corridoriq.backend.generator;

import java.time.Instant;
import java.util.List;

import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.engine.RouteSimulator;
import com.corridoriq.backend.engine.RouteSimulator.Hop;
import com.corridoriq.backend.engine.RouteSimulator.SimResult;

/** Builds {@link Candidate}s with chosen outcomes, for tests of the labels and the operator policy. */
final class CandidateFactory {

    static final Instant RELEASE = Instant.parse("2026-04-23T14:00:00Z");

    private CandidateFactory() {
    }

    /** A candidate whose estimate and realised outcome both use the given hours and cost. */
    static Candidate candidate(String templateId, boolean eligible, double hours, double cost, String outcome) {
        return candidate(templateId, eligible, hours, cost, hours, cost, outcome, RELEASE.plusSeconds((long) (hours * 3600)));
    }

    static Candidate candidate(String templateId, boolean eligible, double estHours, double estCost,
                               double realHours, double realCost, String outcome, Instant end) {
        return candidate(templateId, eligible, estHours, estHours, estCost, realHours, realCost, outcome, end);
    }

    /**
     * A candidate whose calendar-aware estimate (estHours) differs from the calendar-blind one (naiveHours),
     * as when a weekend delay at a foreign bank is invisible to the pre-CorridorIQ view.
     */
    static Candidate candidate(String templateId, boolean eligible, double estHours, double naiveHours, double estCost,
                               double realHours, double realCost, String outcome, Instant end) {
        List<Hop> hops = RouteSimulator.resolveHops(templateId, "USD-JPY", "JP_B1");
        RouteEstimate est = new RouteEstimate(templateId, eligible, eligible ? List.of() : List.of("not allowed"), hops,
                new double[hops.size()], hops.size(), hops.size() - 2, estCost, 0, 0, estCost, estHours, naiveHours, 0,
                false, 1.0, 1, 0.97);
        SimResult real = new SimResult(List.of(), end, realHours, 0);
        return new Candidate(est, new double[hops.size()], real, realHours, realCost, outcome,
                outcome.equals("Returned"), outcome.equals("Repaired"));
    }
}
