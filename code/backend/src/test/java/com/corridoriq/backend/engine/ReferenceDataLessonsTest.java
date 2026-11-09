package com.corridoriq.backend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import com.corridoriq.backend.engine.BankCalendar.Closure;
import com.corridoriq.backend.engine.BankCalendar.HolidayRow;
import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.engine.RouteSimulator.Hold;
import com.corridoriq.backend.engine.RouteSimulator.SimResult;
import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.RouteRanker;
import com.corridoriq.backend.web.ApiException;

/**
 * "The engine creates the correct data — as long as its reference data is correct." Run it and read the output:
 *
 * <pre>
 *   cd code/backend
 *   ./mvnw test -Dtest=ReferenceDataLessonsTest
 * </pre>
 *
 * Lesson 1 shows what the engine guarantees: it gives the same numbers the models were trained on. Lessons 2 to
 * 5 take one input at a time (a missing holiday, a holiday on the wrong day, a bank outage nobody entered, a fee
 * the bank changed) and show that the engine's answer changes, or should have changed, while no test fails.
 * Lesson 6 shows the one guard that does exist: payments beyond the holiday calendar are refused.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)   // lesson1 ... lesson6, in order
class ReferenceDataLessonsTest {

    private static final List<HolidayRow> HOLIDAYS = BankCalendar.bundledHolidays();
    private static final BankCalendar CORRECT = new BankCalendar(HOLIDAYS, Map.of());

    private static Instant ny(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "-04:00").toInstant();   // New York summer time
    }

    private static RouteEstimate estimate(BankCalendar cal, String corridor, String bank, String client, double amount,
                                          Instant t0, String template) {
        return RouteEstimator.estimate(corridor, bank, client, amount, t0, cal).stream()
                .filter(e -> e.templateId().equals(template)).findFirst().orElseThrow().rounded();
    }

    private static List<String> holds(BankCalendar cal, String corridor, String bank, Instant t0, String template) {
        SimResult r = RouteSimulator.simulate(RouteSimulator.resolveHops(template, corridor, bank), template, t0, cal,
                RouteSimulator.fixed(Reference.template(template).procHours()));
        List<String> out = new ArrayList<>();
        r.steps().forEach(s -> s.holds().forEach(h -> out.add(s.hop().bankId() + " waits " + fmt(h))));
        return out;
    }

    private static String fmt(Hold h) {
        return String.format(Locale.ROOT, "%.1f h (%s: %s)", h.hours(), h.kind().dbValue(), h.reason());
    }

    private static void lesson(String title, String... lines) {
        StringBuilder b = new StringBuilder("\n").append("-".repeat(96)).append("\n").append(title).append("\n");
        for (String l : lines) {
            b.append("  ").append(l).append("\n");
        }
        System.out.print(b);
    }

    // ------------------------------------------------------------------ 1. what is guaranteed
    @Test
    void lesson1_theEngineGivesTheSameNumbersTheModelsWereTrainedOn() throws Exception {
        Path db = Path.of("../../Data/corridoriq_history/corridoriq_history.db").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(db), "needs the history database");
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + db);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            BankCalendar fromDb = BankCalendar.fromDatabase(c);
            // The seeded Showa Day case: a training row stored in the database
            ResultSet p = st.executeQuery("SELECT p.payment_id, p.corridor_id, p.bene_bank_id, p.client_type, p.amount_usd, "
                    + "p.released_at_utc, rc.est_hours, rc.naive_hours, rc.est_cost_usd, rc.est_nonbusiness_days "
                    + "FROM payment p JOIN route_candidate rc ON rc.payment_id = p.payment_id AND rc.template_id = 'EXP' "
                    + "WHERE p.value_date = '2026-04-28' AND p.template_id = 'EXP' AND p.amount_usd = 15000 "
                    + "AND p.corridor_id = 'USD-JPY' AND p.released_at_utc = '2026-04-28T18:00:00Z'");
            assertThat(p.next()).as("the seeded Showa Day payment").isTrue();
            RouteEstimate now = estimate(fromDb, p.getString("corridor_id"), p.getString("bene_bank_id"),
                    p.getString("client_type"), p.getDouble("amount_usd"), Instant.parse(p.getString("released_at_utc")), "EXP");
            lesson("LESSON 1  Guaranteed: the same numbers as the training data",
                    "Payment " + p.getLong("payment_id") + " (USD-JPY via EXP, released Tue 28 Apr 2026 14:00 New York), stored for training:",
                    String.format(Locale.ROOT, "  stored in the database : est_hours %.2f  naive %.2f  cost %.2f  closed days %d",
                            p.getDouble("est_hours"), p.getDouble("naive_hours"), p.getDouble("est_cost_usd"), p.getInt("est_nonbusiness_days")),
                    String.format(Locale.ROOT, "  computed now by Java   : est_hours %.2f  naive %.2f  cost %.2f  closed days %d",
                            now.estHours(), now.naiveHours(), now.estCostUsd(), now.estNonbusinessDays()),
                    "Identical. EngineParityTest checks the same for 2,000 payments, so a change to the engine's CODE",
                    "is caught at once. The lessons below change the engine's DATA instead.");
            assertThat(now.estHours()).isEqualTo(p.getDouble("est_hours"));
            assertThat(now.naiveHours()).isEqualTo(p.getDouble("naive_hours"));
            assertThat(now.estCostUsd()).isEqualTo(p.getDouble("est_cost_usd"));
            assertThat(now.estNonbusinessDays()).isEqualTo(p.getInt("est_nonbusiness_days"));
        }
    }

    // ------------------------------------------------------------------ 2. a holiday missing from the file
    @Test
    void lesson2_aHolidayMissingFromTheFileMakesTheEstimateTooOptimistic() {
        Instant t0 = ny("2026-04-28T15:30:00");   // Tuesday; Japan is closed on Wednesday 29 April (Showa Day)
        List<HolidayRow> withoutShowa = HOLIDAYS.stream()
                .filter(h -> !(h.country().equals("JP") && h.date().equals(LocalDate.of(2026, 4, 29)))).toList();
        BankCalendar stale = new BankCalendar(withoutShowa, Map.of());

        RouteEstimate right = estimate(CORRECT, "USD-JPY", "JP_B1", "Retail", 4000, t0, "EXP");
        RouteEstimate wrong = estimate(stale, "USD-JPY", "JP_B1", "Retail", 4000, t0, "EXP");
        lesson("LESSON 2  A holiday missing from holidays.csv",
                "USD-JPY via EXP, released Tue 28 Apr 2026 15:30 New York. Wednesday 29 April is Showa Day in Japan.",
                String.format(Locale.ROOT, "with the holiday   : %.1f h   %s", right.estHours(), holds(CORRECT, "USD-JPY", "JP_B1", t0, "EXP")),
                String.format(Locale.ROOT, "without the holiday: %.1f h   %s", wrong.estHours(), holds(stale, "USD-JPY", "JP_B1", t0, "EXP")),
                String.format(Locale.ROOT, "The engine would promise credit %.1f hours too early, for every payment that week.", right.estHours() - wrong.estHours()),
                "Nothing fails: the engine applied the calendar it was given. Only the real outcome would show it.");
        assertThat(right.estHours()).isEqualTo(32.0);
        assertThat(wrong.estHours()).isEqualTo(8.0);
        assertThat(holds(CORRECT, "USD-JPY", "JP_B1", t0, "EXP")).anyMatch(s -> s.contains("Showa Day"));
        assertThat(holds(stale, "USD-JPY", "JP_B1", t0, "EXP")).isEmpty();
    }

    // ------------------------------------------------------------------ 3. a holiday on the wrong day
    @Test
    void lesson3_anEstimatedHolidayOneDayOffMisleadsInBothDirections() {
        // Eid al-Adha follows the moon: holidays.csv marks the dates as "(estimated)". Suppose the real one is a day later.
        Instant t0 = ny("2026-05-29T14:00:00");   // Friday 29 May, during Eid in Saudi Arabia
        List<HolidayRow> shifted = new ArrayList<>();
        for (HolidayRow h : HOLIDAYS) {
            boolean eid = h.name().contains("Eid al-Adha") || h.name().contains("Arafat");
            shifted.add(eid && h.date().getYear() == 2026 ? new HolidayRow(h.country(), h.date().plusDays(1), h.name(), h.source()) : h);
        }
        BankCalendar dayLater = new BankCalendar(shifted, Map.of());

        RouteEstimate expFile = estimate(CORRECT, "USD-SAR", "SA_B2", "Retail", 3000, t0, "EXP");
        RouteEstimate expReal = estimate(dayLater, "USD-SAR", "SA_B2", "Retail", 3000, t0, "EXP");
        RouteEstimate gulfFile = estimate(CORRECT, "USD-SAR", "SA_B2", "Retail", 3000, t0, "GULF");
        RouteEstimate gulfReal = estimate(dayLater, "USD-SAR", "SA_B2", "Retail", 3000, t0, "GULF");
        lesson("LESSON 3  An estimated holiday one day off",
                "USD-SAR, released Fri 29 May 2026 14:00 New York, during Eid al-Adha. Suppose Eid really falls a day later.",
                String.format(Locale.ROOT, "EXP  : file says %.1f h, real %.1f h  -> estimate %.0f h too slow", expFile.estHours(), expReal.estHours(), expFile.estHours() - expReal.estHours()),
                "         file: " + holds(CORRECT, "USD-SAR", "SA_B2", t0, "EXP"),
                "         real: " + holds(dayLater, "USD-SAR", "SA_B2", t0, "EXP"),
                String.format(Locale.ROOT, "GULF : file says %.1f h, real %.1f h  -> estimate %.0f h too fast", gulfFile.estHours(), gulfReal.estHours(), gulfReal.estHours() - gulfFile.estHours()),
                "         file: " + holds(CORRECT, "USD-SAR", "SA_B2", t0, "GULF"),
                "         real: " + holds(dayLater, "USD-SAR", "SA_B2", t0, "GULF"),
                "One wrong date pushes different routes in opposite directions. Moving holidays (Eid, Easter)",
                "must be confirmed each year, or the engine is confidently wrong around them.");
        assertThat(expFile.estHours()).isEqualTo(63.5);
        assertThat(expReal.estHours()).isEqualTo(39.5);
        assertThat(gulfFile.estHours()).isEqualTo(66.0);
        assertThat(gulfReal.estHours()).isEqualTo(90.0);
    }

    // ------------------------------------------------------------------ 4. an outage nobody entered
    @Test
    void lesson4_aBankOutageCountsOnlyIfSomeoneEntersIt() {
        Instant t0 = ny("2026-04-22T10:00:00");   // Wednesday
        LocalDate day = LocalDate.of(2026, 4, 22);
        BankCalendar entered = new BankCalendar(HOLIDAYS, Map.of(new Closure("NY1", day), "Core system outage"));

        RouteEstimate unaware = estimate(CORRECT, "USD-EUR", "DE_B1", "Retail", 3000, t0, "EXP");
        RouteEstimate aware = estimate(entered, "USD-EUR", "DE_B1", "Retail", 3000, t0, "EXP");
        RouteEstimate other = estimate(entered, "USD-EUR", "DE_B1", "Retail", 3000, t0, "LDN");
        lesson("LESSON 4  A bank outage that nobody entered",
                "USD-EUR via EXP, released Wed 22 Apr 2026 10:00 New York. Hudson Meridian (NY1) is down all day.",
                String.format(Locale.ROOT, "outage not entered: %.1f h   %s", unaware.estHours(), holds(CORRECT, "USD-EUR", "DE_B1", t0, "EXP")),
                String.format(Locale.ROOT, "outage entered    : %.1f h   %s", aware.estHours(), holds(entered, "USD-EUR", "DE_B1", t0, "EXP")),
                String.format(Locale.ROOT, "LDN also goes through NY1: %.1f h. Only an entry in bank_closure lets the engine (and so the model) know.", other.estHours()));
        assertThat(aware.estHours()).isGreaterThan(unaware.estHours() + 20);
        assertThat(aware.calendarConflict()).isTrue();      // a hub is closed while New York is open
        assertThat(unaware.calendarConflict()).isFalse();
    }

    // ------------------------------------------------------------------ 5. a fee the bank changed
    @Test
    void lesson5_aFeeTheBankChangedIsNotKnownUntilReferenceIsUpdated() {
        Instant t0 = ny("2026-04-22T10:00:00");
        RouteEstimate exp = estimate(CORRECT, "USD-INR", "IN_B2", "Retail", 2000, t0, "EXP");
        double[] fees = Reference.template("EXP").fees();          // [ordering bank, NY1, clearing bank, beneficiary]
        double newNy1Fee = 25;                                     // say Hudson Meridian raised its fee from 18 to 25
        double realCost = fees[0] + newNy1Fee + fees[2] + fees[3] + 2000 * Reference.template("EXP").fxBpsStandard() / 1e4;
        lesson("LESSON 5  A fee the bank changed",
                String.format(Locale.ROOT, "EXP fees in Reference.java: %s; the engine quotes $%.2f for a $2,000 payment.",
                        java.util.Arrays.toString(fees), exp.estCostUsd()),
                String.format(Locale.ROOT, "If NY1 now charges $%.0f instead of $%.0f, the real cost is $%.2f.", newNy1Fee, fees[1], realCost),
                "Every EXP and LDN quote is $7 low, consistently, until Reference.java is updated.",
                "The cost model would slowly learn the gap from real outcomes; the quote itself stays wrong.");
        assertThat(exp.estCostUsd()).isEqualTo(30 + 18 + 12 + 2000 * 38 / 1e4);
        assertThat(realCost - exp.estCostUsd()).isEqualTo(7.0);
    }

    // ------------------------------------------------------------------ 6. the guard that exists
    @Test
    void lesson6_beyondTheHolidayFileTheEngineCannotKnowAndRankingRefuses() {
        String us2026 = CORRECT.nonBusiness("US", LocalDate.of(2026, 11, 26));
        String us2028 = CORRECT.nonBusiness("US", LocalDate.of(2028, 11, 23));   // Thanksgiving 2028
        RouteRanker ranker = new RouteRanker(r -> {
            throw new AssertionError("the model must not be called");
        }, CORRECT);
        String refusal = null;
        try {
            ranker.rank(new PaymentRequest("USD-JPY", "JP_B1", "Retail", 4000.0, 60,
                    OffsetDateTime.parse("2028-11-22T10:00:00-05:00"), false));
        } catch (ApiException e) {
            refusal = e.getMessage();
        }
        lesson("LESSON 6  Beyond the holiday file (2023-2027)",
                "Thanksgiving 2026: " + us2026,
                "Thanksgiving 2028: " + (us2028 == null ? "an ordinary business day, as far as the calendar knows" : us2028),
                "So POST /v1/routes/rank refuses payments outside the file's years: \"" + refusal + "\"",
                "Extending the file (holidays.csv) each year is the fix; the engine needs no change.");
        assertThat(us2026).contains("Thanksgiving");
        assertThat(us2028).isNull();
        assertThat(refusal).isEqualTo("Banking calendar only covers 2023-2027");
        assertThatThrownBy(() -> ranker.rank(new PaymentRequest("USD-JPY", "JP_B1", "Retail", 4000.0, 60,
                OffsetDateTime.parse("2028-11-22T10:00:00-05:00"), false))).isInstanceOf(ApiException.class);
    }
}
