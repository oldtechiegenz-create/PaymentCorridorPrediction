package com.corridoriq.backend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.engine.RouteSimulator.Hold;
import com.corridoriq.backend.engine.RouteSimulator.HoldKind;
import com.corridoriq.backend.engine.RouteSimulator.Hop;
import com.corridoriq.backend.engine.RouteSimulator.Repair;
import com.corridoriq.backend.engine.RouteSimulator.SimResult;

class RouteSimulatorTest {

    private final BankCalendar cal = BankCalendar.bundled(null);

    /** A New York wall-clock time as an instant. */
    private static Instant ny(String date, String time) {
        return Dates.at(LocalDate.parse(date), LocalTime.parse(time), Reference.zone("US"));
    }

    private SimResult run(String template, String corridor, String bene, Instant t0) {
        List<Hop> hops = RouteSimulator.resolveHops(template, corridor, bene);
        return RouteSimulator.simulate(hops, template, t0, cal, RouteSimulator.fixed(Reference.template(template).procHours()));
    }

    private static List<Hold> holdsOf(SimResult r, HoldKind kind) {
        return r.steps().stream().flatMap(s -> s.holds().stream()).filter(h -> h.kind() == kind).toList();
    }

    // ------------------------------------------------------------------ routes
    @Test
    void placeholdersBecomeTheCorridorsClearingBankAndThePaymentsBeneficiaryBank() {
        List<Hop> hops = RouteSimulator.resolveHops("EXP", "USD-JPY", "JP_B2");
        assertThat(hops).extracting(Hop::bankId).containsExactly("ORIG", "NY1", "JP_CLR", "JP_B2");
        assertThat(hops).extracting(Hop::country).containsExactly("US", "US", "JP", "JP");
    }

    // ------------------------------------------------------------------ the happy path and the weekend
    @Test
    void aThursdayAfternoonPaymentToJapanIsCreditedTheNextJapaneseMorning() {
        SimResult r = run("EXP", "USD-JPY", "JP_B1", ny("2026-04-23", "15:30"));
        assertThat(r.hours()).isCloseTo(8.0, within(1e-6));
        assertThat(r.calendarDays()).isZero();
        assertThat(r.steps()).hasSize(4);
        assertThat(r.steps().stream().flatMap(s -> s.holds().stream())).isEmpty();
    }

    @Test
    void aFridayPaymentToJapanWaitsOutTheWeekend() {
        SimResult r = run("EXP", "USD-JPY", "JP_B1", ny("2026-04-24", "15:30"));
        assertThat(r.hours()).isCloseTo(56.0, within(1e-6));
        assertThat(r.calendarDays()).isEqualTo(2);
        List<Hold> holds = holdsOf(r, HoldKind.CALENDAR);
        assertThat(holds).extracting(Hold::reason).containsExactly("Saturday", "Sunday");
        assertThat(holds).extracting(Hold::hours).containsExactly(26.5, 24.0);
        // both holds happen at the Japanese clearing bank, the third hop
        assertThat(r.steps().get(2).holds()).hasSize(2);
    }

    @Test
    void theGulfRouteLosesTimeToTheBahrainWeekend() {
        SimResult r = run("GULF", "USD-JPY", "JP_B1", ny("2026-04-24", "15:30"));
        assertThat(r.hours()).isCloseTo(58.5, within(1e-6));
        Hold bahrain = r.steps().get(2).holds().getFirst();
        assertThat(bahrain.kind()).isEqualTo(HoldKind.CALENDAR);
        assertThat(bahrain.reason()).isEqualTo("Saturday · Gulf weekend");
        assertThat(bahrain.hours()).isEqualTo(31.0);
    }

    // ------------------------------------------------------------------ cut-offs, batch, instant payout
    @Test
    void aPaymentReleasedAfterTheOrderingBanksCutoffWaitsForTheNextMorning() {
        SimResult r = run("EXP", "USD-JPY", "JP_B1", ny("2026-04-23", "16:30"));
        Hold hold = r.steps().getFirst().holds().getFirst();
        assertThat(hold.kind()).isEqualTo(HoldKind.CUTOFF);
        assertThat(hold.reason()).isEqualTo("after 16:00 cut-off");
        assertThat(hold.hours()).isEqualTo(16.5);   // 16:30 to 09:00 the next day
    }

    @Test
    void theEconomyRouteWaitsForThursdaysNettingBatch() {
        SimResult r = run("ECO", "USD-JPY", "JP_B1", ny("2026-04-20", "15:00"));   // a Monday
        Hold batch = holdsOf(r, HoldKind.BATCH).getFirst();
        assertThat(batch.day()).isEqualTo(LocalDate.of(2026, 4, 23));                 // the Thursday after
        assertThat(batch.hours()).isCloseTo(55.0, within(1e-6));                     // Tuesday 09:00 to Thursday 16:00 Singapore
        assertThat(r.steps().get(2).hop().bankId()).isEqualTo("SG");
        assertThat(r.steps().get(2).holds()).contains(batch);
        assertThat(r.hours()).isGreaterThan(72 + 55);                                // the 72 h processing follows the batch
    }

    @Test
    void aPaymentReachingSingaporeBeforeThursdaysRunJoinsItTheSameDay() {
        // Wednesday 15:00 ET reaches Singapore at 06:00 Thursday, opens at 09:00, and the run is at 16:00.
        SimResult r = run("ECO", "USD-JPY", "JP_B1", ny("2026-04-22", "15:00"));
        Hold batch = holdsOf(r, HoldKind.BATCH).getFirst();
        assertThat(batch.day()).isEqualTo(LocalDate.of(2026, 4, 23));
        assertThat(batch.hours()).isCloseTo(7.0, within(1e-6));
    }

    @Test
    void aPaymentReachingSingaporeAfterThursdaysRunWaitsForNextWeeks() {
        // Thursday 10:00 ET reaches Singapore on Friday, so the next run is Thursday 30 April.
        SimResult r = run("ECO", "USD-JPY", "JP_B1", ny("2026-04-23", "10:00"));
        Hold batch = holdsOf(r, HoldKind.BATCH).getFirst();
        assertThat(batch.day()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(batch.hours()).isCloseTo(6 * 24 + 7, within(1e-6));   // Friday 09:00 to Thursday 16:00
    }

    @Test
    void thePayoutPartnerCreditsAtOnceEvenOverAWeekend() {
        SimResult r = run("PAY", "USD-JPY", "JP_B1", ny("2026-04-24", "15:30"));   // Friday
        assertThat(r.hours()).isCloseTo(1.5, within(1e-6));                         // 0.3 + 0.7 + 0.5, no waiting
        assertThat(r.steps().stream().flatMap(s -> s.holds().stream())).isEmpty();
    }

    // ------------------------------------------------------------------ repairs and realised processing
    @Test
    void aRepairAddsItsHoursAndIsRecordedAtThatHop() {
        List<Hop> hops = RouteSimulator.resolveHops("EXP", "USD-JPY", "JP_B1");
        Instant t0 = ny("2026-04-23", "09:00");
        double[] nominal = Reference.template("EXP").procHours();
        SimResult base = RouteSimulator.simulate(hops, "EXP", t0, cal, RouteSimulator.fixed(nominal));
        SimResult repaired = RouteSimulator.simulate(hops, "EXP", t0, cal, RouteSimulator.fixed(nominal),
                new Repair(2, 6.0, "purpose of payment"));
        Hold repair = holdsOf(repaired, HoldKind.REPAIR).getFirst();
        assertThat(repair.hours()).isEqualTo(6.0);
        assertThat(repair.reason()).isEqualTo("purpose of payment");
        assertThat(repaired.steps().get(2).holds()).contains(repair);
        assertThat(repaired.hours()).isGreaterThanOrEqualTo(base.hours() + 6.0 - 1e-9);
    }

    @Test
    void realisedProcessingTimesReplaceTheNominalOnes() {
        List<Hop> hops = RouteSimulator.resolveHops("EXP", "USD-JPY", "JP_B1");
        Instant t0 = ny("2026-04-23", "09:00");
        SimResult nominal = RouteSimulator.simulate(hops, "EXP", t0, cal, RouteSimulator.fixed(new double[] {0.5, 1.5, 2, 1.5}));
        SimResult slow = RouteSimulator.simulate(hops, "EXP", t0, cal, (i, hop, start) -> 2.0);
        assertThat(slow.hours()).isGreaterThan(nominal.hours());
    }

    // ------------------------------------------------------------------ estimates
    @Test
    void theNaiveEstimateOnlyKnowsTheUsCutoffAndUsHolidays() {
        double[] nominal = Reference.template("EXP").procHours();   // sums to 5.5
        assertThat(RouteSimulator.naiveHours(ny("2026-04-23", "15:30"), cal, nominal)).isCloseTo(5.5, within(1e-6));
        // Friday looks as fast as Thursday to the naive view: the Japanese weekend is invisible to it.
        assertThat(RouteSimulator.naiveHours(ny("2026-04-24", "15:30"), cal, nominal)).isCloseTo(5.5, within(1e-6));
        // After the cut-off it waits for 09:00 the next US business day.
        assertThat(RouteSimulator.naiveHours(ny("2026-04-23", "16:30"), cal, nominal)).isCloseTo(16.5 + 5.5, within(1e-6));
    }

    @Test
    void aCalendarConflictIsAnIntermediaryClosedWhileTheUsIsOpen() {
        List<Hop> gulf = RouteSimulator.resolveHops("GULF", "USD-JPY", "JP_B1");
        List<Hop> express = RouteSimulator.resolveHops("EXP", "USD-JPY", "JP_B1");
        LocalDate thursday = LocalDate.of(2026, 4, 23);   // Bahrain is shut the next day, Friday
        assertThat(RouteSimulator.calendarConflict(gulf, thursday, cal)).isTrue();
        assertThat(RouteSimulator.calendarConflict(express, thursday, cal)).isFalse();
        assertThat(RouteSimulator.calendarConflict(gulf, LocalDate.of(2026, 4, 20), cal)).isFalse();   // Monday, Tuesday
    }

    @Test
    void estimatesApplyCapsRetailOnlyRulesAndTheFiFeeCap() {
        Instant t0 = ny("2026-04-23", "10:00");
        List<RouteEstimate> big = RouteEstimator.estimate("USD-JPY", "JP_B1", "Retail", 30000, t0, cal);
        assertThat(big).extracting(RouteEstimate::templateId).containsExactly("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO");
        assertThat(byId(big, "PAY").ineligibleReasons()).containsExactly("Above the $5,000 per-transfer cap");
        assertThat(byId(big, "ECO").ineligibleReasons()).containsExactly("Above the $25,000 per-transfer cap");
        assertThat(byId(big, "EXP").eligible()).isTrue();

        List<RouteEstimate> fi = RouteEstimator.estimate("USD-JPY", "JP_B1", "FI", 4000, t0, cal);
        assertThat(byId(fi, "PAY").ineligibleReasons()).containsExactly("Available to retail clients only");
        assertThat(byId(fi, "EXP").fees()[0]).isEqualTo(15.0);          // first-hop fee capped at 15 (was 30)
        assertThat(byId(fi, "EXP").feeTotalUsd()).isEqualTo(15 + 18 + 12);
        assertThat(byId(big, "EXP").fees()[0]).isEqualTo(30.0);         // retail pays the full fee
    }

    @Test
    void largeTicketsGetTheTighterFxMargin() {
        Instant t0 = ny("2026-04-23", "10:00");
        RouteEstimate small = byId(RouteEstimator.estimate("USD-JPY", "JP_B1", "FI", 249_999, t0, cal), "GULF");
        RouteEstimate large = byId(RouteEstimator.estimate("USD-JPY", "JP_B1", "FI", 250_000, t0, cal), "GULF");
        assertThat(small.fxBps()).isEqualTo(16.0);
        assertThat(large.fxBps()).isEqualTo(6.0);
        assertThat(large.estCostUsd()).isCloseTo(large.feeTotalUsd() + 250_000 * 6 / 1e4, within(1e-9));
    }

    @Test
    void cutoffSlackIsNegativeAfterTheOrderingBanksCutoff() {
        RouteEstimate before = RouteEstimator.estimate("USD-EUR", "DE_B1", "Retail", 1000, ny("2026-04-23", "14:00"), cal).getFirst();
        RouteEstimate after = RouteEstimator.estimate("USD-EUR", "DE_B1", "Retail", 1000, ny("2026-04-23", "17:00"), cal).getFirst();
        assertThat(before.cutoffSlackHours()).isCloseTo(2.0, within(1e-6));
        assertThat(after.cutoffSlackHours()).isCloseTo(-1.0, within(1e-6));
    }

    @Test
    void anEstimateIsRoundedToTwoDecimalsForStorage() {
        RouteEstimate e = RouteEstimator.estimate("USD-INR", "IN_B1", "Retail", 1234.56, ny("2026-04-23", "10:13"), cal).getFirst();
        RouteEstimate r = e.rounded();
        assertThat(r.estHours()).isEqualTo(Dates.round(e.estHours(), 2));
        assertThat(r.estCostUsd()).isEqualTo(Dates.round(e.estCostUsd(), 2));
    }

    private static RouteEstimate byId(List<RouteEstimate> all, String templateId) {
        return all.stream().filter(e -> e.templateId().equals(templateId)).findFirst().orElseThrow();
    }
}
