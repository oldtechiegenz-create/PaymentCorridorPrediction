package com.corridoriq.backend.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RngTest {

    @Test
    void theSameSeedGivesTheSameSequenceAndADifferentSeedDoesNot() {
        Rng a = new Rng(42);
        Rng b = new Rng(42);
        Rng c = new Rng(43);
        double first = a.random();
        assertThat(b.random()).isEqualTo(first);
        assertThat(c.random()).isNotEqualTo(first);
    }

    @Test
    void eachPaymentHasItsOwnReproducibleStream() {
        assertThat(Rng.forPayment(42, 7).random()).isEqualTo(Rng.forPayment(42, 7).random());
        assertThat(Rng.forPayment(42, 7).random()).isNotEqualTo(Rng.forPayment(42, 8).random());
        assertThat(Rng.forPayment(42, 7).random()).isNotEqualTo(Rng.forPayment(43, 7).random());
    }

    @Test
    void uniformAndRandintStayInsideTheirBounds() {
        Rng r = new Rng(1);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            double u = r.uniform(9, 13);
            assertThat(u).isBetween(9.0, 13.0);
            int k = r.randint(2, 6);
            assertThat(k).isBetween(2, 6);
            seen.add(k);
        }
        assertThat(seen).containsExactlyInAnyOrder(2, 3, 4, 5, 6);   // both ends are reachable
    }

    @Test
    void gaussHasTheRequestedMeanAndSpread() {
        Rng r = new Rng(2);
        int n = 40000;
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double g = r.gauss(50, 15);
            sum += g;
            sumSq += g * g;
        }
        double mean = sum / n;
        double sd = Math.sqrt(sumSq / n - mean * mean);
        assertThat(mean).isCloseTo(50, within(0.5));
        assertThat(sd).isCloseTo(15, within(0.5));
    }

    @Test
    void paretoAndLognormalDrawsArePositiveAndParetoHasAHeavyTail() {
        Rng r = new Rng(3);
        double max = 0;
        for (int i = 0; i < 20000; i++) {
            double p = r.pareto(1.4);
            assertThat(p).isGreaterThanOrEqualTo(1.0);
            assertThat(r.lognormal(0, 0.3)).isPositive();
            max = Math.max(max, p);
        }
        assertThat(max).as("a few very large weights").isGreaterThan(50);
    }

    @Test
    void pickFollowsTheWeights() {
        Rng r = new Rng(4);
        Map<String, Double> w = Rng.weights("A", .6, "B", .3, "C", .1);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 30000; i++) {
            counts.merge(r.pick(w), 1, Integer::sum);
        }
        assertThat(counts.get("A") / 30000.0).isCloseTo(0.6, within(0.02));
        assertThat(counts.get("B") / 30000.0).isCloseTo(0.3, within(0.02));
        assertThat(counts.get("C") / 30000.0).isCloseTo(0.1, within(0.02));
    }

    @Test
    void weightedIndexFollowsCumulativeWeightsAndNeverOverruns() {
        Rng r = new Rng(5);
        double[] cum = Rng.cumulative(new double[] {1, 0, 3});   // the zero-weight middle item must never be drawn
        int[] counts = new int[3];
        for (int i = 0; i < 20000; i++) {
            counts[r.weightedIndex(cum)]++;
        }
        assertThat(counts[1]).isZero();
        assertThat(counts[0] / 20000.0).isCloseTo(0.25, within(0.02));
        assertThat(counts[2] / 20000.0).isCloseTo(0.75, within(0.02));
    }

    @Test
    void weightsKeepTheirOrder() {
        assertThat(Rng.weights("Z", 1, "A", 2, "M", 3).keySet()).containsExactly("Z", "A", "M");
    }
}
