package com.corridoriq.backend.generator;

import static com.corridoriq.backend.generator.CandidateFactory.candidate;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OpsPolicyTest {

    /** Routes that look identical on time and cost, so only the operator's habits and noise decide. */
    private static List<Candidate> equalRoutes() {
        return List.of(
                candidate("EXP", true, 20, 100, "Credited"),      // via NY1: familiar default
                candidate("GULF", true, 20, 100, "Credited"),     // via NY2
                candidate("PAY", true, 20, 100, "Credited"));     // newer partner
    }

    private static Map<String, Integer> tally(String clientType, double amount, int priority, int draws) {
        Map<String, Integer> counts = new HashMap<>();
        Rng rng = new Rng(11);
        List<Candidate> routes = equalRoutes();
        for (int i = 0; i < draws; i++) {
            counts.merge(OpsPolicy.choose(rng, routes, clientType, amount, priority).templateId(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void operatorsFavourTheFamiliarExpressRouteAndAvoidTheNewPartner() {
        Map<String, Integer> counts = tally("Retail", 2000, 50, 6000);
        assertThat(counts.get("EXP")).isGreaterThan(counts.get("GULF"));
        assertThat(counts.get("GULF")).isGreaterThan(counts.get("PAY"));
    }

    @Test
    void largeFinancialInstitutionTicketsGoToTheGulfHubForTheTightestFx() {
        Map<String, Integer> counts = tally("FI", 300_000, 50, 6000);
        assertThat(counts.get("GULF")).isGreaterThan(counts.get("EXP"));
        // The same amount from a retail client does not trigger the habit.
        Map<String, Integer> retail = tally("Retail", 300_000, 50, 6000);
        assertThat(retail.get("EXP")).isGreaterThan(retail.get("GULF"));
    }

    @Test
    void neverPicksAnIneligibleRoute() {
        List<Candidate> routes = List.of(
                candidate("PAY", false, 1, 1, "Credited"),         // far better on paper, but not allowed
                candidate("EXP", true, 20, 100, "Credited"),
                candidate("ASIA", true, 25, 90, "Credited"));
        Rng rng = new Rng(5);
        for (int i = 0; i < 2000; i++) {
            assertThat(OpsPolicy.choose(rng, routes, "Retail", 3000, 50).templateId()).isNotEqualTo("PAY");
        }
    }

    @Test
    void ignoringForeignCalendarsMakesAWeekendDelayInvisibleMostOfTheTime() {
        // Express is really 56 h (a weekend in Japan) but looks like 5.5 h to a calendar-blind operator.
        Candidate express = candidate("EXP", true, 56, 5.5, 100, 56, 100, "Credited", Instant.parse("2026-04-27T00:00:00Z"));
        Candidate asia = candidate("ASIA", true, 30, 30, 100, 30, 100, "Credited", Instant.parse("2026-04-24T00:00:00Z"));
        Rng rng = new Rng(9);
        int expressPicks = 0;
        for (int i = 0; i < 4000; i++) {
            if (OpsPolicy.choose(rng, List.of(express, asia), "Retail", 3000, 100).templateId().equals("EXP")) {
                expressPicks++;
            }
        }
        // Calendar-blind 70% of the time, so most picks are the express route that is really slower.
        assertThat(expressPicks / 4000.0).isGreaterThan(0.6);
    }

    @Test
    void theSameSeedMakesTheSameChoices() {
        List<Candidate> routes = equalRoutes();
        Rng a = new Rng(21);
        Rng b = new Rng(21);
        for (int i = 0; i < 200; i++) {
            assertThat(OpsPolicy.choose(a, routes, "Retail", 2000, 50).templateId())
                    .isEqualTo(OpsPolicy.choose(b, routes, "Retail", 2000, 50).templateId());
        }
    }
}
