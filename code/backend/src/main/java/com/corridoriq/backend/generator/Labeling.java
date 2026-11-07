package com.corridoriq.backend.generator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.corridoriq.backend.engine.Dates;

/** The ranking labels written to route_candidate: utility (lower is better) and relevance (4 is best). */
public final class Labeling {

    private Labeling() {
    }

    /**
     * Scores each eligible route: log realised hours and log realised cost, each scaled 0 to 1 within the
     * payment and weighted by the priority (0 = cost matters, 100 = speed matters). A return adds 1.0 and a
     * credit after the deadline adds 0.5. The best four routes get relevance 4, 3, 2 and 1; the rest get 0.
     *
     * @param deadlineEnd the client's arrive-by instant, or null for no deadline
     */
    public static void utilityLabels(List<Candidate> candidates, int priority, Instant deadlineEnd) {
        List<Candidate> eligible = candidates.stream().filter(Candidate::eligible).toList();
        if (eligible.isEmpty()) {
            return;
        }
        double[] logHours = eligible.stream().mapToDouble(c -> Math.log(Math.max(c.realizedHours, 0.5))).toArray();
        double[] logCost = eligible.stream().mapToDouble(c -> Math.log(Math.max(c.realizedCost, 0.5))).toArray();
        double tMin = min(logHours);
        double tMax = max(logHours);
        double cMin = min(logCost);
        double cMax = max(logCost);
        double w = priority / 100.0;
        for (int i = 0; i < eligible.size(); i++) {
            Candidate c = eligible.get(i);
            double tn = tMax > tMin ? (logHours[i] - tMin) / (tMax - tMin) : 0.0;
            double cn = cMax > cMin ? (logCost[i] - cMin) / (cMax - cMin) : 0.0;
            double u = w * tn + (1 - w) * cn;
            if (c.realizedOutcome.equals("Returned")) {
                u += 1.0;
            } else if (deadlineEnd != null && c.realizedEnd.isAfter(deadlineEnd)) {
                u += 0.5;
            }
            c.utility = Dates.round(u, 4);
        }
        List<Candidate> ranked = new ArrayList<>(eligible);
        ranked.sort(Comparator.comparingDouble((Candidate c) -> c.utility).thenComparingDouble(c -> c.realizedCost));
        for (int rank = 0; rank < ranked.size(); rank++) {
            ranked.get(rank).relevance = Math.max(0, 4 - rank);
        }
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
