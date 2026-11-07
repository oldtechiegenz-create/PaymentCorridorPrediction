package com.corridoriq.backend.generator;

import java.time.Instant;
import java.util.List;

import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.engine.RouteSimulator.SimResult;

/**
 * One route that was available for a payment: the estimate known at release, what that route would really
 * have done (the realised half, which becomes the ranking label), and the label itself once it is set.
 */
public final class Candidate {

    public final RouteEstimate estimate;
    /** Fees including any unexpected intermediary fee, per hop. */
    public final double[] realFees;
    public final SimResult real;
    /** Realised hours to credit; for a return, the time to the refund plus a 72 h penalty. */
    public final double realizedHours;
    public final double realizedCost;
    /** Credited, Repaired or Returned. */
    public final String realizedOutcome;
    public final Instant realizedEnd;
    public final boolean returned;
    public final boolean repaired;

    /** Lower is better; null when the route is not eligible. */
    public Double utility;
    /** 4 for the best realised route, then 3, 2, 1; 0 for the rest. */
    public int relevance;

    public Candidate(RouteEstimate estimate, double[] realFees, SimResult real, double realizedHours,
                     double realizedCost, String realizedOutcome, boolean returned, boolean repaired) {
        this.estimate = estimate;
        this.realFees = realFees;
        this.real = real;
        this.realizedHours = realizedHours;
        this.realizedCost = realizedCost;
        this.realizedOutcome = realizedOutcome;
        this.realizedEnd = real.end();
        this.returned = returned;
        this.repaired = repaired;
    }

    public String templateId() {
        return estimate.templateId();
    }

    public boolean eligible() {
        return estimate.eligible();
    }

    public List<String> banks() {
        return estimate.hops().stream().map(h -> h.bankId()).toList();
    }
}
