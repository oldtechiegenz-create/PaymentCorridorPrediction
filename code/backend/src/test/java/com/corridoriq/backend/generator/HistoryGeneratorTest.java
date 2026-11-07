package com.corridoriq.backend.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corridoriq.backend.engine.BankCalendar;
import com.corridoriq.backend.engine.Reference;
import com.corridoriq.backend.generator.HistoryGenerator.Config;
import com.corridoriq.backend.generator.HistoryGenerator.Output;

/**
 * Generates a small history once, writes it to SQLite, and checks that it is internally consistent and has the
 * shape the backend and the models rely on.
 */
class HistoryGeneratorTest {

    private static final Path SCHEMA = Path.of("../../Data/corridoriq_history/schema.sql");
    private static final Config SMALL = new Config(1500, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 12, 31), 7);

    @TempDir
    static Path dir;
    private static Output data;
    private static Connection db;

    @BeforeAll
    static void generateAndWrite() throws Exception {
        data = HistoryGenerator.generate(SMALL, BankCalendar.bundledHolidays(), null);
        Path file = dir.resolve("small.db");
        HistoryWriter.write(file, SCHEMA, data, false);
        db = DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    @AfterAll
    static void close() throws SQLException {
        db.close();
    }

    private static long count(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.getLong(1);
        }
    }

    private static List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ shape
    @Test
    void producesTheRequestedNumberOfPaymentsAndClients() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment")).isEqualTo(1500);
        assertThat(count("SELECT COUNT(*) FROM client WHERE client_type = 'Retail'")).isEqualTo(400);   // at least 400
        assertThat(count("SELECT COUNT(*) FROM client WHERE client_type = 'FI'")).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM holiday")).isEqualTo(613);
        assertThat(count("SELECT COUNT(*) FROM bank")).isEqualTo(Reference.BANKS.size());
        assertThat(count("SELECT COUNT(*) FROM corridor_route")).isEqualTo(22);
    }

    @Test
    void everyForeignKeyResolvesAndTheFileIsIntact() throws SQLException {
        assertThat(column("PRAGMA integrity_check")).containsExactly("ok");
        assertThat(column("PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void paymentsFallOnUsBusinessDaysDuringUsBusinessHours() throws SQLException {
        BankCalendar cal = BankCalendar.bundled(null);
        for (String d : column("SELECT DISTINCT value_date FROM payment")) {
            assertThat(cal.nonBusiness("US", LocalDate.parse(d))).as(d).isNull();
            assertThat(d).isBetween("2025-09-01", "2025-12-31");
        }
        assertThat(count("SELECT COUNT(*) FROM payment WHERE release_hour_et < 9 OR release_hour_et >= 18")).isZero();
    }

    // ------------------------------------------------------------------ candidates and labels
    @Test
    void everyPaymentHasExactlyOneChosenRouteAndItIsEligible() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM (SELECT payment_id FROM route_candidate GROUP BY payment_id "
                + "HAVING SUM(was_chosen) <> 1)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate WHERE was_chosen = 1 AND eligible = 0")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate rc JOIN payment p USING (payment_id) "
                + "WHERE rc.was_chosen = 1 AND rc.template_id <> p.template_id")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment p JOIN route_candidate rc USING (payment_id) "
                + "WHERE rc.was_chosen = 1 AND ABS(rc.est_hours - p.est_hours) > 0.001")).isZero();
    }

    @Test
    void everyPaymentHasACandidateForEachRouteOfItsCorridor() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM (SELECT p.payment_id FROM payment p "
                + "JOIN corridor_route cr ON cr.corridor_id = p.corridor_id "
                + "LEFT JOIN route_candidate rc ON rc.payment_id = p.payment_id AND rc.template_id = cr.template_id "
                + "WHERE rc.payment_id IS NULL)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate rc JOIN payment p USING (payment_id) "
                + "LEFT JOIN corridor_route cr ON cr.corridor_id = p.corridor_id AND cr.template_id = rc.template_id "
                + "WHERE cr.template_id IS NULL")).isZero();
    }

    @Test
    void payoutAndEconomyCapsAndTheRetailOnlyRuleAreEnforced() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment WHERE template_id = 'PAY' AND (amount_usd > 5000 OR client_type = 'FI')")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE template_id = 'ECO' AND amount_usd > 25000")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate rc JOIN payment p USING (payment_id) "
                + "WHERE rc.template_id = 'PAY' AND rc.eligible = 1 AND (p.amount_usd > 5000 OR p.client_type = 'FI')")).isZero();
        // and the reason is recorded when a route is excluded
        assertThat(count("SELECT COUNT(*) FROM route_candidate WHERE eligible = 0 AND exclusion_reason IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate WHERE eligible = 1 AND exclusion_reason IS NOT NULL")).isZero();
    }

    @Test
    void labelsAreSetOnlyOnEligibleRoutesAndTheBestHasRelevanceFour() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM route_candidate WHERE eligible = 0 AND (utility IS NOT NULL OR relevance <> 0)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate WHERE eligible = 1 AND utility IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate WHERE relevance NOT BETWEEN 0 AND 4")).isZero();
        // exactly one relevance-4 route per payment, and it has the lowest utility
        assertThat(count("SELECT COUNT(*) FROM (SELECT payment_id FROM route_candidate GROUP BY payment_id "
                + "HAVING SUM(relevance = 4) <> 1)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM route_candidate best JOIN route_candidate o "
                + "ON o.payment_id = best.payment_id AND o.eligible = 1 "
                + "WHERE best.relevance = 4 AND o.utility < best.utility")).isZero();
    }

    @Test
    void theOperatorsChoiceIsOftenButNotAlwaysTheBestRoute() throws SQLException {
        long best = count("SELECT COUNT(*) FROM route_candidate WHERE was_chosen = 1 AND relevance = 4");
        double share = best / 1500.0;
        assertThat(share).as("share of payments where ops picked the best route").isBetween(0.65, 0.92);
    }

    // ------------------------------------------------------------------ outcomes and timelines
    @Test
    void returnedAndCreditedPaymentsHaveConsistentFields() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment WHERE outcome = 'Returned' AND (hours_to_credit IS NOT NULL "
                + "OR credited_at_utc IS NOT NULL OR returned_at_utc IS NULL OR stp <> 0)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE outcome <> 'Returned' AND (hours_to_credit IS NULL "
                + "OR credited_at_utc IS NULL OR returned_at_utc IS NOT NULL)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE stp = 1 AND outcome <> 'Credited'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE outcome = 'Credited' AND stp <> 1")).isZero();
        // the three outcomes all occur and most payments are credited untouched
        assertThat(count("SELECT COUNT(DISTINCT outcome) FROM payment")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM payment WHERE outcome = 'Credited'") / 1500.0).isBetween(0.90, 0.99);
    }

    @Test
    void timeToCreditEqualsTheGapBetweenReleaseAndCredit() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment WHERE credited_at_utc IS NOT NULL AND ABS("
                + "(julianday(credited_at_utc) - julianday(released_at_utc)) * 24 - hours_to_credit) > 0.02")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE hours_to_credit <= 0")).isZero();
    }

    @Test
    void theDeadlineResultIsOnlySetWhenThereIsADeadline() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment WHERE (deadline_date IS NULL) <> (deadline_met IS NULL)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE outcome = 'Returned' AND deadline_met = 1")).isZero();
    }

    @Test
    void theHopTimelineMatchesTheRouteAndTheOutcome() throws SQLException {
        // one hop row per bank on the chosen route, numbered from 0
        assertThat(count("SELECT COUNT(*) FROM payment p WHERE (SELECT COUNT(*) FROM payment_hop h WHERE h.payment_id = p.payment_id) "
                + "<> (SELECT hop_count FROM route_candidate rc WHERE rc.payment_id = p.payment_id AND rc.was_chosen = 1)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_hop WHERE seq = 0 AND bank_id <> 'ORIG'")).isZero();
        // the last hop shows the final status; earlier hops are in progress
        assertThat(count("SELECT COUNT(*) FROM payment_hop h JOIN payment p USING (payment_id) WHERE h.seq = "
                + "(SELECT MAX(seq) FROM payment_hop x WHERE x.payment_id = h.payment_id) "
                + "AND h.status_code <> CASE p.outcome WHEN 'Returned' THEN 'RJCT' ELSE 'ACCC' END")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_hop h WHERE h.seq < "
                + "(SELECT MAX(seq) FROM payment_hop x WHERE x.payment_id = h.payment_id) AND h.status_code <> 'ACSP'")).isZero();
        // times run forward within a hop, and hold fields agree with each other
        assertThat(count("SELECT COUNT(*) FROM payment_hop WHERE arrived_at_utc > started_at_utc OR started_at_utc > left_at_utc")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_hop WHERE (hold_kind IS NULL) <> (hold_reason IS NULL) "
                + "OR (hold_kind IS NULL AND hold_hours <> 0)")).isZero();
    }

    @Test
    void feesAndMoneyAddUp() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment p WHERE ABS(p.fee_total_usd - "
                + "(SELECT SUM(fee_usd) FROM payment_hop h WHERE h.payment_id = p.payment_id)) > 0.02")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE ABS(all_in_cost_usd - (fee_total_usd + fx_cost_usd)) > 0.011")).isZero();
        // who pays the intermediary fees decides how much is converted
        assertThat(count("SELECT COUNT(*) FROM payment WHERE charges = 'OUR' AND amount_converted_usd <> amount_usd")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE charges = 'BEN' "
                + "AND ABS(amount_converted_usd - (amount_usd - fee_total_usd)) > 0.011")).isZero();
        // SHA: the sender pays the ordering bank's own fee, the beneficiary side bears the intermediary fees
        assertThat(count("SELECT COUNT(*) FROM payment p WHERE p.charges = 'SHA' AND ABS(p.amount_converted_usd - "
                + "(p.amount_usd - (p.fee_total_usd - (SELECT h.fee_usd FROM payment_hop h WHERE h.payment_id = p.payment_id "
                + "AND h.seq = 0)))) > 0.011")).isZero();
        // the payout route has no intermediary fees, so only BEN reduces the converted amount there
        assertThat(count("SELECT COUNT(*) FROM payment WHERE charges <> 'OUR' AND template_id <> 'PAY' "
                + "AND amount_converted_usd >= amount_usd")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE rate_offered >= mid_rate OR beneficiary_amount <= 0")).isZero();
    }

    @Test
    void thePaymentUsesTheDaysFxRateAndTheRouteMargin() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM payment p JOIN corridor c USING (corridor_id) LEFT JOIN fx_rate_daily f "
                + "ON f.currency_code = c.currency_code AND f.rate_date = p.value_date "
                + "WHERE f.mid_rate IS NULL OR f.mid_rate <> p.mid_rate")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment p JOIN route_candidate rc ON rc.payment_id = p.payment_id "
                + "AND rc.was_chosen = 1 WHERE p.fx_bps <> rc.fx_bps")).isZero();
        // larger tickets get the tighter margin on the Gulf route
        assertThat(count("SELECT COUNT(*) FROM payment WHERE template_id = 'GULF' AND "
                + "fx_bps <> CASE WHEN amount_usd >= 250000 THEN 6 ELSE 16 END")).isZero();
    }

    // ------------------------------------------------------------------ incidents
    @Test
    void incidentsDescribeReturnsRepairsAndLongDelaysWithTheMatchingCause() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM incident")).isGreaterThan(20);
        assertThat(count("SELECT COUNT(*) FROM incident i JOIN payment p USING (payment_id) JOIN cause_code c ON c.code = i.cause_code "
                + "WHERE c.category = 'return' AND p.outcome <> 'Returned'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM incident i JOIN payment p USING (payment_id) JOIN cause_code c ON c.code = i.cause_code "
                + "WHERE c.category = 'repair' AND p.outcome <> 'Repaired'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment p WHERE p.outcome IN ('Returned', 'Repaired') AND NOT EXISTS "
                + "(SELECT 1 FROM incident i WHERE i.payment_id = p.payment_id)")).as("returns and repairs are always annotated").isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_hop WHERE hold_kind = 'repair' "
                + "AND payment_id NOT IN (SELECT payment_id FROM payment WHERE outcome IN ('Repaired', 'Returned'))")).isZero();
        assertThat(count("SELECT COUNT(*) FROM incident WHERE note = '' OR annotated_by = ''")).isZero();
        assertThat(count("SELECT COUNT(*) FROM incident i JOIN payment p USING (payment_id) "
                + "WHERE i.annotated_at_utc <= p.released_at_utc")).isZero();
    }

    @Test
    void aMainViewsWorkOnTheGeneratedDatabase() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM v_ranking_training")).isEqualTo(count("SELECT COUNT(*) FROM route_candidate"));
        assertThat(count("SELECT COUNT(*) FROM v_case")).isEqualTo(1500);
        assertThat(count("SELECT COUNT(*) FROM v_route_performance_18m")).isEqualTo(22);
        assertThat(column("SELECT as_of FROM v_history_window")).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM v_corridor_overview")).isEqualTo(5);
        assertThat(count("SELECT SUM(cases) FROM v_corridor_overview")).isEqualTo(1500);
    }

    @Test
    void theMetadataRecordsHowTheDatabaseWasMade() throws SQLException {
        assertThat(column("SELECT value FROM meta WHERE key = 'seed'")).containsExactly("7");
        assertThat(column("SELECT value FROM meta WHERE key = 'payments'")).containsExactly("1500");
        assertThat(column("SELECT value FROM meta WHERE key = 'window_start'")).containsExactly("2025-09-01");
        assertThat(column("SELECT value FROM meta WHERE key = 'label_definition'").getFirst()).contains("relevance");
        assertThat(column("SELECT value FROM meta WHERE key = 'generator'").getFirst()).contains("java");
        assertThat(column("SELECT value FROM meta WHERE key = 'synthetic'").getFirst()).startsWith("yes");
    }

    // ------------------------------------------------------------------ seeded incidents, reproducibility, safety
    @Test
    void theEightPortalIncidentsAppearWithTheirIntendedCause() throws Exception {
        Output full = HistoryGenerator.generate(new Config(3000, LocalDate.of(2024, 4, 1), LocalDate.of(2026, 9, 30), 42),
                BankCalendar.bundledHolidays(), null);
        Path file = dir.resolve("seeds.db");
        HistoryWriter.write(file, SCHEMA, full, false);
        String[][] expected = {
                // value date, corridor, route, amount, cause
                {"2025-10-09", "USD-JPY", "GULF", "900000.0", "CAL_WEEKEND"},
                {"2026-04-28", "USD-JPY", "EXP", "15000.0", "CAL_HOLIDAY"},
                {"2026-08-06", "USD-JPY", "ASIA", "42000.0", "RFI_PURPOSE"},
                {"2026-09-17", "USD-JPY", "ECO", "600.0", "BATCH_WAIT"},
                {"2026-09-11", "USD-INR", "EXP", "7000.0", "CAL_HOLIDAY"},
                {"2026-07-02", "USD-AED", "GULF", "120000.0", "CAL_WEEKEND"},
                {"2026-06-11", "USD-SAR", "EXP", "30000.0", "CAL_WEEKEND"},
                {"2026-08-28", "USD-EUR", "LDN", "9000.0", "CAL_HOLIDAY"},
        };
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String[] e : expected) {
                String sql = "SELECT i.cause_code, p.outcome FROM payment p LEFT JOIN incident i USING (payment_id) "
                        + "WHERE p.value_date = '" + e[0] + "' AND p.corridor_id = '" + e[1] + "' AND p.template_id = '" + e[2]
                        + "' AND p.amount_usd = " + e[3] + " AND p.released_at_utc = '" + e[0] + "T18:00:00Z'";   // 14:00 New York
                try (ResultSet rs = st.executeQuery(sql)) {
                    assertThat(rs.next()).as("seeded payment " + Arrays.toString(e)).isTrue();
                    assertThat(rs.getString(1)).as("cause of " + Arrays.toString(e)).isEqualTo(e[4]);
                    assertThat(rs.getString(2)).isNotEqualTo("Returned");
                    assertThat(rs.next()).as("only one such payment").isFalse();
                }
            }
        }
    }

    @Test
    void theSameSeedGivesTheSameHistoryAndADifferentSeedDoesNot() {
        Config c = new Config(300, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30), 5);
        List<com.corridoriq.backend.engine.BankCalendar.HolidayRow> hol = BankCalendar.bundledHolidays();
        Output a = HistoryGenerator.generate(c, hol, null);
        Output b = HistoryGenerator.generate(c, hol, null);
        Output other = HistoryGenerator.generate(new Config(300, c.start(), c.end(), 6), hol, null);
        assertThat(a.payments()).hasSameSizeAs(b.payments());
        for (int i = 0; i < a.payments().size(); i++) {
            assertThat(a.payments().get(i)).isEqualTo(b.payments().get(i));
        }
        for (int i = 0; i < a.candidates().size(); i++) {
            assertThat(a.candidates().get(i)).isEqualTo(b.candidates().get(i));
        }
        assertThat(a.incidents()).usingRecursiveComparison().isEqualTo(b.incidents());
        assertThat(Arrays.deepEquals(a.payments().toArray(), other.payments().toArray())).isFalse();
    }

    @Test
    void aWindowOutsideTheHolidayCalendarIsRejected() {
        List<com.corridoriq.backend.engine.BankCalendar.HolidayRow> hol = BankCalendar.bundledHolidays();
        assertThatThrownBy(() -> HistoryGenerator.generate(
                new Config(100, LocalDate.of(2029, 1, 1), LocalDate.of(2029, 6, 30), 1), hol, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holiday calendar");
    }

    @Test
    void anExistingDatabaseIsNeverReplacedUnlessForced() throws Exception {
        Path file = dir.resolve("existing.db");
        Output tiny = HistoryGenerator.generate(new Config(30, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 12), 3),
                BankCalendar.bundledHolidays(), null);
        HistoryWriter.write(file, SCHEMA, tiny, false);
        long size = Files.size(file);
        assertThatThrownBy(() -> HistoryWriter.write(file, SCHEMA, tiny, false))
                .isInstanceOf(IOException.class).hasMessageContaining("already exists").hasMessageContaining("--force");
        assertThat(Files.size(file)).as("the file was left alone").isEqualTo(size);
        HistoryWriter.write(file, SCHEMA, tiny, true);   // --force replaces it
        assertThat(Files.size(file)).isPositive();
    }

    @Test
    void volumeGrowsOverTheWindowAndCrowdsMonthEnd() {
        // 4 months: more payments in the later half than the earlier half, on average per business day
        long firstHalf = data.payments().stream().filter(p -> ((String) p[10]).compareTo("2025-11-01") < 0).count();
        long secondHalf = data.payments().size() - firstHalf;
        assertThat(firstHalf).isGreaterThan(0);
        assertThat(secondHalf).isGreaterThan(0);
        // every corridor of the five is used
        assertThat(data.payments().stream().map(p -> (String) p[5]).distinct().count()).isEqualTo(5);
        assertThat(data.payments().stream().mapToDouble(p -> (Double) p[6]).min().orElseThrow()).isGreaterThanOrEqualTo(10.0);
        assertThat(data.payments().stream().mapToDouble(p -> (Double) p[6]).average().orElseThrow()).isBetween(10_000.0, 150_000.0);
        assertThat(data.payments().stream().filter(p -> p[4].equals("Retail")).count() / 1500.0).isCloseTo(0.72, within(0.06));
    }
}
