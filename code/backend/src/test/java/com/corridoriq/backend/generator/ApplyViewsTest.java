package com.corridoriq.backend.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corridoriq.backend.engine.BankCalendar;
import com.corridoriq.backend.generator.HistoryGenerator.Config;

class ApplyViewsTest {

    private static final Path SCHEMA = Path.of("../../Data/corridoriq_history/schema.sql");
    private static final List<String> ALL_VIEWS = List.of("v_case", "v_route_performance", "v_incident",
            "v_corridor_monthly", "v_history_window", "v_corridor_overview", "v_route_performance_18m", "v_fx_corridor",
            "v_ranking_training");

    @TempDir
    Path dir;
    Path db;

    @BeforeEach
    void smallDatabase() throws Exception {
        db = dir.resolve("history.db");
        HistoryWriter.write(db, SCHEMA, HistoryGenerator.generate(
                new Config(60, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30), 4), BankCalendar.bundledHolidays(), null), false);
    }

    private List<String> query(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void theSchemaListsEveryViewInDependencyOrder() throws IOException {
        List<String> names = ApplyViews.views(Files.readString(SCHEMA)).stream().map(ApplyViews.View::name).toList();
        assertThat(names).containsExactlyInAnyOrderElementsOf(ALL_VIEWS);
        assertThat(names.indexOf("v_history_window")).isLessThan(names.indexOf("v_route_performance_18m"));
        assertThat(ApplyViews.views(Files.readString(SCHEMA))).allMatch(v -> v.sql().toUpperCase().startsWith("CREATE VIEW"));
    }

    @Test
    void missingAndOutdatedViewsAreRecreatedAndTheDataIsUntouched() throws Exception {
        String payments = query("SELECT COUNT(*) FROM payment").getFirst();
        String candidates = query("SELECT COUNT(*) FROM route_candidate").getFirst();
        execute("DROP VIEW v_case");
        execute("DROP VIEW v_incident");
        execute("CREATE VIEW v_incident AS SELECT 1 AS outdated");   // an old version of a view

        List<String> recreated = ApplyViews.apply(db, SCHEMA);

        assertThat(recreated).containsExactlyInAnyOrderElementsOf(ALL_VIEWS);
        assertThat(query("SELECT name FROM sqlite_master WHERE type = 'view'")).containsExactlyInAnyOrderElementsOf(ALL_VIEWS);
        assertThat(query("SELECT COUNT(*) FROM v_case")).containsExactly(payments);
        assertThat(query("SELECT COUNT(*) FROM v_ranking_training")).containsExactly(candidates);
        query("SELECT note, cause FROM v_incident");   // the current definition, not the outdated one
        assertThat(query("SELECT COUNT(*) FROM payment")).containsExactly(payments);
        assertThat(query("SELECT COUNT(*) FROM route_candidate")).containsExactly(candidates);
    }

    @Test
    void runningItTwiceIsHarmless() throws Exception {
        ApplyViews.apply(db, SCHEMA);
        assertThat(ApplyViews.apply(db, SCHEMA)).hasSize(ALL_VIEWS.size());
        assertThat(query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'view'")).containsExactly(String.valueOf(ALL_VIEWS.size()));
    }

    @Test
    void aBrokenViewLeavesTheDatabaseAsItWas() throws Exception {
        Path broken = dir.resolve("broken.sql");
        Files.writeString(broken, Files.readString(SCHEMA) + "\nCREATE VIEW v_zzz AS SELECT no_such_column FROM payment;\n");
        assertThatThrownBy(() -> ApplyViews.apply(db, broken)).isInstanceOf(SQLException.class);
        assertThat(query("SELECT name FROM sqlite_master WHERE type = 'view'")).containsExactlyInAnyOrderElementsOf(ALL_VIEWS);
    }

    @Test
    void aMissingDatabaseIsReportedAndNotCreated() {
        Path nothing = dir.resolve("absent.db");
        assertThatThrownBy(() -> ApplyViews.apply(nothing, SCHEMA)).isInstanceOf(IOException.class).hasMessageContaining("No database");
        assertThat(nothing).doesNotExist();
    }
}
