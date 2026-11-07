package com.corridoriq.backend.generator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeded random numbers, with the draws the generator needs (the ones Python's {@code random} module offers).
 * The same seed always gives the same sequence. It is not the same sequence as Python's, so a database
 * generated here is a different (statistically equivalent) synthetic history from the Python one.
 */
public final class Rng {

    private final Random random;

    public Rng(long seed) {
        this.random = new Random(seed);
    }

    /** An independent generator for one payment, so a payment's draws do not depend on the others. */
    public static Rng forPayment(long seed, long paymentId) {
        return new Rng(mix(seed, paymentId));
    }

    /** SplitMix64-style hash of the two numbers. */
    static long mix(long seed, long paymentId) {
        long z = seed * 0x9E3779B97F4A7C15L + paymentId * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Ordered weights, e.g. {@code Rng.weights("OUR", .55, "SHA", .40)}; the order is part of the result. */
    public static Map<String, Double> weights(Object... keyAndWeight) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < keyAndWeight.length; i += 2) {
            m.put((String) keyAndWeight[i], ((Number) keyAndWeight[i + 1]).doubleValue());
        }
        return m;
    }

    /** Uniform in [0, 1). */
    public double random() {
        return random.nextDouble();
    }

    public double uniform(double a, double b) {
        return a + (b - a) * random();
    }

    public double gauss(double mu, double sigma) {
        return mu + sigma * random.nextGaussian();
    }

    public double lognormal(double mu, double sigma) {
        return Math.exp(gauss(mu, sigma));
    }

    /** Heavy-tailed positive draw: a few very large values, mostly small. */
    public double pareto(double alpha) {
        return 1.0 / Math.pow(1.0 - random(), 1.0 / alpha);
    }

    /** Integer in [a, b], both included. */
    public int randint(int a, int b) {
        return a + (int) (random() * (b - a + 1));
    }

    public <T> T choice(List<T> items) {
        return items.get((int) (random() * items.size()));
    }

    public long nextLong() {
        return random.nextLong();
    }

    /** Index drawn by cumulative weights: the first position whose running total exceeds the draw. */
    public int weightedIndex(double[] cumulative) {
        double r = random() * cumulative[cumulative.length - 1];
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] > r) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** A key drawn in proportion to its weight. */
    public String pick(Map<String, Double> weights) {
        double total = 0;
        for (double w : weights.values()) {
            total += w;
        }
        double r = random() * total;
        double running = 0;
        String last = null;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            running += e.getValue();
            last = e.getKey();
            if (running > r) {
                return last;
            }
        }
        return last;
    }

    public static double[] cumulative(double[] weights) {
        double[] cum = new double[weights.length];
        double run = 0;
        for (int i = 0; i < weights.length; i++) {
            run += weights[i];
            cum[i] = run;
        }
        return cum;
    }
}
