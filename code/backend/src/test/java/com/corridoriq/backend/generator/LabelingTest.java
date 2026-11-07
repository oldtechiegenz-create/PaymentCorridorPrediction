package com.corridoriq.backend.generator;

import static com.corridoriq.backend.generator.CandidateFactory.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class LabelingTest {

    private static Candidate byId(List<Candidate> all, String id) {
        return all.stream().filter(c -> c.templateId().equals(id)).findFirst().orElseThrow();
    }

    /** EXP is fastest but dearest, ECO slowest but cheapest, the others in between. */
    private static List<Candidate> routes() {
        return List.of(
                candidate("EXP", true, 10, 120, "Credited"),
                candidate("ASIA", true, 20, 80, "Credited"),
                candidate("LDN", true, 30, 60, "Credited"),
                candidate("GULF", true, 40, 40, "Credited"),
                candidate("ECO", true, 100, 15, "Credited"));
    }

    @Test
    void whenSpeedMattersMostTheFastestRouteIsBest() {
        List<Candidate> c = routes();
        Labeling.utilityLabels(c, 100, null);
        assertThat(byId(c, "EXP").relevance).isEqualTo(4);
        assertThat(byId(c, "EXP").utility).isEqualTo(0.0);
        assertThat(byId(c, "ECO").relevance).isZero();   // fifth place gets nothing
    }

    @Test
    void whenCostMattersMostTheCheapestRouteIsBest() {
        List<Candidate> c = routes();
        Labeling.utilityLabels(c, 0, null);
        assertThat(byId(c, "ECO").relevance).isEqualTo(4);
        assertThat(byId(c, "EXP").relevance).isZero();
    }

    @Test
    void relevanceStepsDownFromFourToOneThenZero() {
        List<Candidate> c = routes();
        Labeling.utilityLabels(c, 100, null);
        assertThat(c).extracting(x -> x.relevance).containsExactly(4, 3, 2, 1, 0);
    }

    @Test
    void utilityIsAWeightedMixOfScaledLogTimeAndLogCost() {
        List<Candidate> c = List.of(candidate("EXP", true, 10, 100, "Credited"), candidate("ECO", true, 1000, 10, "Credited"));
        Labeling.utilityLabels(c, 50, null);
        // EXP is best on time (0) and worst on cost (1): 0.5*0 + 0.5*1; ECO is the reverse.
        assertThat(byId(c, "EXP").utility).isEqualTo(0.5);
        assertThat(byId(c, "ECO").utility).isEqualTo(0.5);
        Labeling.utilityLabels(c, 80, null);
        assertThat(byId(c, "EXP").utility).isCloseTo(0.2, within(1e-9));    // 0.8*0 + 0.2*1
        assertThat(byId(c, "ECO").utility).isCloseTo(0.8, within(1e-9));    // 0.8*1 + 0.2*0
    }

    @Test
    void aReturnedPaymentIsPenalisedAndFallsBehindASlowerCreditedRoute() {
        List<Candidate> c = List.of(
                candidate("EXP", true, 10, 100, "Returned"),      // fastest, but it came back
                candidate("ASIA", true, 20, 100, "Credited"),
                candidate("ECO", true, 100, 100, "Credited"));
        Labeling.utilityLabels(c, 100, null);
        assertThat(byId(c, "EXP").utility).isEqualTo(1.0);       // 0 for time, +1.0 for the return
        assertThat(byId(c, "ASIA").utility).isLessThan(1.0);
        assertThat(byId(c, "ASIA").relevance).isEqualTo(4);
        assertThat(byId(c, "EXP").relevance).isLessThan(4);
    }

    @Test
    void aCreditAfterTheDeadlineIsPenalisedByHalf() {
        Instant deadline = Instant.parse("2026-04-24T00:00:00Z");   // 10 h after release
        Candidate onTime = candidate("EXP", true, 8, 100, 8, 100, "Credited", Instant.parse("2026-04-23T22:00:00Z"));
        Candidate late = candidate("ASIA", true, 8, 100, 8, 100, "Credited", Instant.parse("2026-04-24T01:00:00Z"));
        Labeling.utilityLabels(List.of(onTime, late), 100, deadline);
        assertThat(late.utility - onTime.utility).isCloseTo(0.5, within(1e-9));
        assertThat(onTime.relevance).isEqualTo(4);
        Labeling.utilityLabels(List.of(onTime, late), 100, null);   // no deadline, no penalty
        assertThat(late.utility).isEqualTo(onTime.utility);
    }

    @Test
    void ineligibleRoutesGetNoUtilityAndNoRelevance() {
        List<Candidate> c = List.of(
                candidate("EXP", true, 10, 100, "Credited"),
                candidate("PAY", false, 1, 5, "Credited"),    // would have been the best, but is not allowed
                candidate("ASIA", true, 20, 80, "Credited"));
        Labeling.utilityLabels(c, 100, null);
        assertThat(byId(c, "PAY").utility).isNull();
        assertThat(byId(c, "PAY").relevance).isZero();
        assertThat(byId(c, "EXP").relevance).isEqualTo(4);
    }

    @Test
    void aTieOnUtilityIsBrokenByTheCheaperRealisedCost() {
        // Same hours, and speed is all that matters, so utilities tie.
        List<Candidate> c = List.of(candidate("EXP", true, 10, 120, "Credited"), candidate("ASIA", true, 10, 90, "Credited"));
        Labeling.utilityLabels(c, 100, null);
        assertThat(byId(c, "ASIA").relevance).isEqualTo(4);
        assertThat(byId(c, "EXP").relevance).isEqualTo(3);
    }

    @Test
    void aSingleEligibleRouteIsAutomaticallyBestAndNoEligibleRouteIsHarmless() {
        List<Candidate> one = List.of(candidate("EXP", true, 10, 100, "Credited"));
        Labeling.utilityLabels(one, 60, null);
        assertThat(one.getFirst().relevance).isEqualTo(4);
        assertThat(one.getFirst().utility).isEqualTo(0.0);

        List<Candidate> none = List.of(candidate("PAY", false, 1, 5, "Credited"));
        Labeling.utilityLabels(none, 60, null);
        assertThat(none.getFirst().utility).isNull();
    }
}
