package com.corridoriq.backend.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.corridoriq.backend.engine.Reference;

class GeneratorHelpersTest {

    // ------------------------------------------------------------------ congestion
    @Test
    void congestionMatchesWhatTheCodeInPythonComputes() {
        // Reference values computed with Python's hashlib.md5 on the same text, over 2,000 days from 2025-01-01.
        int congested = 0;
        double total = 0;
        for (int i = 0; i < 2000; i++) {
            double f = HistoryGenerator.congestionFactor(42, "IN_B1", LocalDate.of(2025, 1, 1).plusDays(i));
            total += f;
            if (f > 1) {
                congested++;
                assertThat(f).isBetween(1.5, 3.5);
            }
        }
        assertThat(congested).isEqualTo(146);
        assertThat(total).isCloseTo(2218.1953125, within(1e-9));
    }

    @Test
    void congestionIsTheSameForTheSameBankAndDayAndRoughlySevenPercentOfDaysAreBusy() {
        LocalDate d = LocalDate.of(2026, 3, 3);
        assertThat(HistoryGenerator.congestionFactor(42, "SG", d)).isEqualTo(HistoryGenerator.congestionFactor(42, "SG", d));
        long busy = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            if (HistoryGenerator.congestionFactor(7, "NY1", d.plusDays(i)) > 1) {
                busy++;
            }
        }
        assertThat(busy / (double) n).isCloseTo(0.07, within(0.01));
    }

    // ------------------------------------------------------------------ amounts and text
    @Test
    void amountsAreRoundedLikePeopleSendMoney() {
        assertThat(HistoryGenerator.roundAmount(5)).isEqualTo(10.0);          // never below 10
        assertThat(HistoryGenerator.roundAmount(45)).isEqualTo(40.0);         // ties go to even, as in Python
        assertThat(HistoryGenerator.roundAmount(999)).isEqualTo(1000.0);
        assertThat(HistoryGenerator.roundAmount(1025)).isEqualTo(1000.0);
        assertThat(HistoryGenerator.roundAmount(1075)).isEqualTo(1100.0);
        assertThat(HistoryGenerator.roundAmount(12350)).isEqualTo(12400.0);
        assertThat(HistoryGenerator.roundAmount(250499)).isEqualTo(250000.0);
    }

    @Test
    void durationsReadAsHoursUnderTwoDaysAndDaysAfter() {
        assertThat(HistoryGenerator.fmtHours(26.5)).isEqualTo("26.5 h");
        assertThat(HistoryGenerator.fmtHours(47.9)).isEqualTo("47.9 h");
        assertThat(HistoryGenerator.fmtHours(72)).isEqualTo("3.0 days");
    }

    // ------------------------------------------------------------------ FX
    @Test
    void fxSeriesEndsAtThePortalRateAndPeggedCurrenciesBarelyMove() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        FxSeries.Result fx = FxSeries.generate(new Rng(1), start, end);
        assertThat(fx.rows()).hasSize(5 * ((int) (end.toEpochDay() - start.toEpochDay()) + 1));   // a rate every calendar day
        assertThat(fx.rate("JPY", end)).isEqualTo(147.85);
        assertThat(fx.rate("INR", end)).isEqualTo(88.40);
        assertThat(fx.rate("EUR", end)).isEqualTo(0.856);
        assertThat(fx.rate("AED", end)).isEqualTo(3.6725);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(30)) {
            assertThat(fx.rate("AED", d)).isCloseTo(3.6725, within(0.01));   // pegged
            assertThat(fx.rate("SAR", d)).isCloseTo(3.75, within(0.01));
        }
    }

    @Test
    void fxVolatilityIsAboutTheConfiguredAnnualFigure() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        FxSeries.Result fx = FxSeries.generate(new Rng(2), start, end);
        List<Double> returns = new ArrayList<>();
        for (LocalDate d = start.plusDays(1); !d.isAfter(end); d = d.plusDays(1)) {
            returns.add(Math.log(fx.rate("JPY", d) / fx.rate("JPY", d.minusDays(1))));
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double var = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / (returns.size() - 1);
        assertThat(Math.sqrt(var) * Math.sqrt(365)).isBetween(0.07, 0.12);   // configured: 9.5%
    }

    // ------------------------------------------------------------------ SQL script
    @Test
    void statementsSplitOnSemicolonsButNotInsideCommentsOrStrings() {
        String script = """
                -- a comment; with a semicolon and a quote's apostrophe
                PRAGMA foreign_keys = ON;
                CREATE TABLE t (a TEXT DEFAULT 'x;y', -- trailing; comment
                                b INTEGER);
                INSERT INTO t VALUES ('it''s -- not a comment', 1);
                """;
        List<String> s = SqlScript.statements(script);
        assertThat(s).hasSize(3);
        assertThat(s.get(0)).isEqualTo("PRAGMA foreign_keys = ON");
        assertThat(s.get(1)).contains("'x;y'").doesNotContain("trailing");
        assertThat(s.get(2)).contains("'it''s -- not a comment'");
    }

    @Test
    void theRealSchemaCreatesEveryTableAndViewTheBackendNeeds() throws Exception {
        Path schema = Path.of("../../Data/corridoriq_history/schema.sql");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(schema));
        Set<String> tables = new HashSet<>();
        Set<String> views = new HashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement st = c.createStatement()) {
            for (String sql : SqlScript.statements(Files.readString(schema))) {
                st.execute(sql);
            }
            try (ResultSet rs = st.executeQuery("SELECT type, name FROM sqlite_master WHERE type IN ('table','view')")) {
                while (rs.next()) {
                    (rs.getString(1).equals("table") ? tables : views).add(rs.getString(2));
                }
            }
        }
        assertThat(tables).contains("payment", "payment_hop", "route_candidate", "incident", "client", "fx_rate_daily",
                "holiday", "bank", "corridor", "route_template", "route_template_hop", "corridor_route", "meta");
        assertThat(views).containsExactlyInAnyOrder("v_case", "v_route_performance", "v_incident", "v_corridor_monthly",
                "v_history_window", "v_corridor_overview", "v_route_performance_18m", "v_fx_corridor", "v_ranking_training");
    }

    // ------------------------------------------------------------------ command line
    @Test
    void optionsCanBeGivenWithASpaceOrAnEqualsSignAndForceNeedsNoValue() {
        Map<String, String> o = GenerateHistory.parse(new String[] {"--payments", "500", "--seed=9", "--force", "--out", "x.db"});
        assertThat(o).containsEntry("payments", "500").containsEntry("seed", "9").containsEntry("out", "x.db")
                .containsEntry("force", "true");
    }

    @Test
    void badOptionsAreRejectedWithAClearMessage() {
        assertThatThrownBy(() -> GenerateHistory.parse(new String[] {"payments", "5"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unexpected argument");
        assertThatThrownBy(() -> GenerateHistory.parse(new String[] {"--seed"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing value for --seed");
    }

    // ------------------------------------------------------------------ reference data
    @Test
    void everyCorridorRouteExistsAndEveryHopNamesARealBank() {
        for (Reference.Corridor c : Reference.CORRIDORS.values()) {
            assertThat(Reference.BANKS).containsKey(c.clearingBank());
            assertThat(c.beneBanks().stream().mapToDouble(Reference.BeneShare::share).sum()).isCloseTo(1.0, within(1e-9));
            for (String t : c.templates()) {
                Reference.Template tpl = Reference.template(t);
                assertThat(tpl.fees()).hasSameSizeAs(tpl.hops());
                assertThat(tpl.procHours()).hasSameSizeAs(tpl.hops());
                for (String ref : tpl.hops()) {
                    assertThat(ref.equals("CLR") || ref.equals("BENE") || Reference.BANKS.containsKey(ref)).as(ref).isTrue();
                }
            }
        }
    }
}
