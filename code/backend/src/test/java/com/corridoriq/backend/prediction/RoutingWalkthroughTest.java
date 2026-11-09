package com.corridoriq.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.corridoriq.backend.engine.BankCalendar;
import com.corridoriq.backend.engine.Dates;
import com.corridoriq.backend.engine.Reference;
import com.corridoriq.backend.engine.RouteEstimator;
import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.engine.RouteSimulator;
import com.corridoriq.backend.engine.RouteSimulator.Hold;
import com.corridoriq.backend.engine.RouteSimulator.SimResult;
import com.corridoriq.backend.engine.RouteSimulator.Step;
import com.corridoriq.backend.generator.HistoryGenerator;
import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.PredictionModels.RankResponse;
import com.corridoriq.backend.prediction.PredictionModels.RouteFeatures;
import com.corridoriq.backend.prediction.PredictionModels.RouteResult;
import com.corridoriq.backend.prediction.PredictionModels.RouteScore;
import com.corridoriq.backend.prediction.PredictionModels.ScoreRequest;
import com.corridoriq.backend.prediction.PredictionModels.ScoreResponse;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A walkthrough to run and read: how one payment becomes the data the model receives.
 *
 * <pre>
 *   cd code/backend
 *   ./mvnw test -Dtest=RoutingWalkthroughTest
 * </pre>
 *
 * The first test prints every step for one payment (a Friday afternoon transfer to Japan) and checks the key
 * numbers; the other two show how the same data changes on a Thursday, and for an amount over the caps.
 * The model is not called here: a stand-in returns scores, so the test needs no Python. To see the real model's
 * answer for the same payment, send it to the running backend (see the end of the printed report).
 */
class RoutingWalkthroughTest {

    /** The calendar the backend uses: the bundled holidays plus the ad hoc bank closures of the history database. */
    private static final BankCalendar CALENDAR = BankCalendar.bundled(HistoryGenerator.CLOSURES);

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)   // as the backend sends it
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** Records what would be sent to Python and answers with stand-in scores (faster route = higher score). */
    private static final class CapturingScorer implements RouteScorer {
        ScoreRequest sent;

        @Override
        public ScoreResponse score(ScoreRequest request) {
            sent = request;
            List<RouteScore> scores = new ArrayList<>();
            for (RouteFeatures f : request.routes()) {
                scores.add(new RouteScore(f.templateId(), -f.estHours(), f.estHours(), f.estCostUsd(), 0));
            }
            return new ScoreResponse("stand-in (not the real model)", scores);
        }
    }

    /** Friday 24 April 2026, 15:30 in New York: $4,000 from a retail client to Shirakawa Shinkin Bank, Kyoto. */
    private static final PaymentRequest FRIDAY = new PaymentRequest("USD-JPY", "JP_B1", "Retail", 4000.0, 60,
            OffsetDateTime.parse("2026-04-24T15:30:00-04:00"), false);

    @Test
    void aFridayPaymentToJapanStepByStep() throws Exception {
        CapturingScorer scorer = new CapturingScorer();
        RankResponse answer = new RouteRanker(scorer, CALENDAR).rank(FRIDAY);
        Instant t0 = FRIDAY.releasedAt().toInstant();
        List<RouteEstimate> estimates = RouteEstimator.estimate(FRIDAY.corridorId(), FRIDAY.beneBankId(),
                FRIDAY.clientType(), FRIDAY.amountUsd(), t0, CALENDAR).stream().map(RouteEstimate::rounded).toList();

        StringBuilder out = new StringBuilder();
        line(out, "=".repeat(100));
        line(out, "STEP 1  The payment, as a caller sends it to POST /v1/routes/rank");
        line(out, "=".repeat(100));
        line(out, "  %s, to %s (%s), %s client, $%,.0f, priority %d (0 = cheapest, 100 = fastest)", FRIDAY.corridorId(),
                FRIDAY.beneBankId(), Reference.bank(FRIDAY.beneBankId()).name(), FRIDAY.clientType(), FRIDAY.amountUsd(),
                FRIDAY.priority());
        line(out, "  released %s New York time", local(t0, "US"));
        line(out, "  The corridor is fixed by the payment. The question is which ROUTE (chain of banks) to use:");
        line(out, "  %s offers %s", FRIDAY.corridorId(), Reference.corridor(FRIDAY.corridorId()).templates());

        line(out, "");
        line(out, "STEP 2  Rules: which routes this payment may use (decided by the engine, never by the model)");
        for (RouteEstimate e : estimates) {
            line(out, "  %-5s %-16s %s", e.templateId(), Reference.template(e.templateId()).name(),
                    e.eligible() ? "allowed" : "NOT allowed: " + String.join("; ", e.ineligibleReasons()));
        }

        line(out, "");
        line(out, "STEP 3  The engine walks each route bank by bank, in each bank's own time zone and calendar");
        for (RouteEstimate e : estimates) {
            SimResult sim = RouteSimulator.simulate(e.hops(), e.templateId(), t0, CALENDAR,
                    RouteSimulator.fixed(Reference.template(e.templateId()).procHours()));
            line(out, "  %s via %s: %.1f h to credit", e.templateId(), Reference.template(e.templateId()).via(), sim.hours());
            for (Step s : sim.steps()) {
                line(out, "     %-8s %-26s arrives %s  starts %s  leaves %s", s.hop().bankId(), s.hop().name(),
                        local(s.arrive(), s.hop().country()), local(s.start(), s.hop().country()),
                        local(s.leave(), s.hop().country()));
                for (Hold h : s.holds()) {
                    line(out, "              waits %5.1f h  (%s: %s)", h.hours(), h.kind().dbValue(), h.reason());
                }
            }
        }

        line(out, "");
        line(out, "STEP 4  The numbers the engine hands over for each allowed route (one row each)");
        line(out, "  %-5s %8s %8s %9s %9s %10s %7s %7s %6s %5s %5s %6s", "route", "est h", "naive h", "closed d",
                "conflict", "cut-off h", "fees $", "fx bps", "cost $", "hops", "risk", "stp");
        for (RouteFeatures f : scorer.sent.routes()) {
            line(out, "  %-5s %8.2f %8.2f %9d %9d %10.2f %7.2f %7.1f %6.2f %5d %5d %6.3f", f.templateId(), f.estHours(),
                    f.naiveHours(), f.estNonbusinessDays(), f.calendarConflict(), f.cutoffSlackHours(), f.feeTotalUsd(),
                    f.fxBps(), f.estCostUsd(), f.hopCount(), f.maxRisk(), f.stpRateNominal());
        }
        line(out, "  est h = calendar-aware estimate; naive h = processing only, the 'before CorridorIQ' view;");
        line(out, "  closed d = closed days on the way; conflict = a hub closed today or tomorrow while New York is open;");
        line(out, "  cut-off h = hours left before the ordering bank's 16:00 cut-off; cost = fees + amount x fx bps.");

        line(out, "");
        line(out, "STEP 5  The payment-level numbers (once per payment), on the US value date");
        var p = scorer.sent.payment();
        line(out, "  release_hour_et=%.2f  release_weekday=%d (0=Sun..6=Sat)  day_of_month=%d  has_deadline=%d",
                p.releaseHourEt(), p.releaseWeekday(), p.dayOfMonth(), p.hasDeadline());

        line(out, "");
        line(out, "STEP 6  Exactly what the Python model receives: POST /v1/routes/score");
        line(out, JSON.writeValueAsString(scorer.sent).indent(2).stripTrailing());

        line(out, "");
        line(out, "STEP 7  Python returns one score per route; Java sorts by it and answers the caller");
        line(out, "  (Here the scores come from a stand-in: fastest route first. The real model weighs time and cost.)");
        for (RouteResult r : answer.routes()) {
            line(out, "  %-4s %-5s %s", r.rank() == null ? "-" : "#" + r.rank(), r.templateId(),
                    r.eligible() ? String.format(Locale.ROOT, "score %.2f", r.modelScore()) : "not allowed: " + r.ineligibleReasons());
        }
        line(out, "");
        line(out, "  To see the REAL model's answer, start both services and send the same payment:");
        line(out, "    curl -s localhost:8000/v1/routes/rank -H 'Content-Type: application/json' -d '{\"corridor_id\": \"USD-JPY\",");
        line(out, "      \"bene_bank_id\": \"JP_B1\", \"client_type\": \"Retail\", \"amount_usd\": 4000, \"priority\": 60,");
        line(out, "      \"released_at\": \"2026-04-24T15:30:00-04:00\", \"has_deadline\": false}'");
        line(out, "=".repeat(100));
        System.out.println(out);

        // ---- the checks: if the engine's behaviour changes, this walkthrough fails and must be re-read
        assertThat(scorer.sent.routes()).extracting(RouteFeatures::templateId)
                .containsExactly("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO");      // $4,000 retail: every route allowed
        RouteFeatures exp = sent(scorer, "EXP");
        assertThat(exp.estHours()).isEqualTo(56.0);              // Japan is shut Saturday and Sunday
        assertThat(exp.naiveHours()).isEqualTo(5.5);             // the old view sees no weekend at all
        assertThat(exp.estNonbusinessDays()).isEqualTo(2);
        assertThat(exp.cutoffSlackHours()).isEqualTo(0.5);       // 15:30, cut-off at 16:00
        assertThat(exp.estCostUsd()).isEqualTo(30 + 18 + 12 + 4000 * 38 / 1e4);
        RouteFeatures gulf = sent(scorer, "GULF");
        assertThat(gulf.estHours()).isEqualTo(58.5);             // Bahrain's Friday-Saturday weekend, then Japan's Sunday
        assertThat(gulf.calendarConflict()).isEqualTo(1);        // Bahrain is shut today while New York is open
        assertThat(sent(scorer, "PAY").estHours()).isCloseTo(1.5, within(1e-9));   // instant payout ignores the weekend
        assertThat(p.releaseHourEt()).isEqualTo(15.5);
        assertThat(p.releaseWeekday()).isEqualTo(5);             // Friday
        assertThat(p.dayOfMonth()).isEqualTo(24);
        String json = JSON.writeValueAsString(scorer.sent);
        assertThat(json).contains("\"est_hours\"", "\"release_hour_et\"", "\"calendar_conflict\"");   // the names Python expects
    }

    @Test
    void theSamePaymentOnAThursdayGetsVeryDifferentNumbers() {
        CapturingScorer scorer = new CapturingScorer();
        new RouteRanker(scorer, CALENDAR).rank(new PaymentRequest("USD-JPY", "JP_B1", "Retail", 4000.0, 60,
                OffsetDateTime.parse("2026-04-23T15:30:00-04:00"), false));
        System.out.printf("%nThursday instead of Friday: EXP %.1f h (Friday 56.0 h), GULF %.1f h (Friday 58.5 h)%n%n",
                sent(scorer, "EXP").estHours(), sent(scorer, "GULF").estHours());
        assertThat(sent(scorer, "EXP").estHours()).isEqualTo(8.0);           // credited Friday morning in Japan
        assertThat(sent(scorer, "EXP").naiveHours()).isEqualTo(5.5);         // the naive view cannot tell the two days apart
        assertThat(sent(scorer, "EXP").estNonbusinessDays()).isZero();
    }

    @Test
    void anAmountOverTheCapsLeavesThoseRoutesOutOfTheModelsInput() {
        CapturingScorer scorer = new CapturingScorer();
        RankResponse answer = new RouteRanker(scorer, CALENDAR).rank(new PaymentRequest("USD-JPY", "JP_B1", "Retail",
                30000.0, 60, FRIDAY.releasedAt(), false));
        System.out.printf("%n$30,000 instead of $4,000: sent to the model %s; listed as not allowed %s%n%n",
                scorer.sent.routes().stream().map(RouteFeatures::templateId).toList(),
                answer.routes().stream().filter(r -> !r.eligible()).map(r -> r.templateId() + " (" + r.ineligibleReasons().getFirst() + ")").toList());
        assertThat(scorer.sent.routes()).extracting(RouteFeatures::templateId).containsExactly("EXP", "ASIA", "LDN", "GULF");
        assertThat(answer.routes().subList(4, 6)).extracting(RouteResult::templateId).containsExactly("PAY", "ECO");
    }

    // ------------------------------------------------------------------ helpers
    private static RouteFeatures sent(CapturingScorer s, String templateId) {
        return s.sent.routes().stream().filter(f -> f.templateId().equals(templateId)).findFirst().orElseThrow();
    }

    private static String local(Instant t, String country) {
        ZonedDateTime z = t.atZone(Reference.zone(country));
        return Dates.dayLabel(z.toLocalDate()).substring(0, 6).strip() + String.format(Locale.ROOT, " %02d:%02d", z.getHour(), z.getMinute());
    }

    private static void line(StringBuilder out, String format, Object... args) {
        out.append(args.length == 0 ? format : String.format(Locale.ROOT, format, args)).append('\n');
    }
}
