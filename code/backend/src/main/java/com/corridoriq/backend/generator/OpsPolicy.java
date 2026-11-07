package com.corridoriq.backend.generator;

import java.util.List;

import com.corridoriq.backend.engine.Reference;

/**
 * The route an operator chose before CorridorIQ: habits plus, 70% of the time, an estimate that ignores
 * foreign calendars. It is why the history contains avoidable mistakes for a ranking model to learn from.
 */
public final class OpsPolicy {

    private OpsPolicy() {
    }

    /** Picks among the eligible candidates; lower score wins. Needs at least one eligible candidate. */
    public static Candidate choose(Rng rng, List<Candidate> candidates, String clientType, double amount, int priority) {
        List<Candidate> eligible = candidates.stream().filter(Candidate::eligible).toList();
        boolean calendarAware = rng.random() < 0.30;
        double[] ts = eligible.stream().mapToDouble(c -> Math.log(Math.max(
                calendarAware ? c.estimate.estHours() : c.estimate.naiveHours(), 0.5))).toArray();
        double[] cs = eligible.stream().mapToDouble(c -> Math.log(c.estimate.estCostUsd())).toArray();
        double tMin = min(ts);
        double tMax = max(ts);
        double cMin = min(cs);
        double cMax = max(cs);
        double w = priority / 100.0;
        Candidate best = null;
        double bestScore = 0;
        for (int i = 0; i < eligible.size(); i++) {
            Candidate c = eligible.get(i);
            double tn = tMax > tMin ? (ts[i] - tMin) / (tMax - tMin) : 0.0;
            double cn = cMax > cMin ? (cs[i] - cMin) / (cMax - cMin) : 0.0;
            double s = w * tn + (1 - w) * cn;
            String t = c.templateId();
            if (t.equals("EXP")) {
                s -= 0.12;                                   // familiar default
            }
            if (t.equals("GULF") && clientType.equals("FI") && amount >= Reference.LARGE_TICKET_USD) {
                s -= 0.25;                                   // chases the tightest large-ticket FX
            }
            if (t.equals("PAY")) {
                s += 0.08;                                   // newer partner, used less
            }
            if (t.equals("ECO") && priority <= 20) {
                s -= 0.10;
            }
            if (c.banks().contains("NY1")) {
                s -= 0.04;                                   // preferred relationship
            }
            s += rng.gauss(0, 0.12);
            if (best == null || s < bestScore) {
                best = c;
                bestScore = s;
            }
        }
        return best;
    }

    private static double min(double[] a) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : a) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double max(double[] a) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : a) {
            m = Math.max(m, v);
        }
        return m;
    }
}
