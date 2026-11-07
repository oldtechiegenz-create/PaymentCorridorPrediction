package com.corridoriq.backend.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.corridoriq.backend.engine.BankCalendar.HolidayRow;
import com.corridoriq.backend.engine.Dates;
import com.corridoriq.backend.engine.Reference;
import com.corridoriq.backend.engine.Reference.Bank;
import com.corridoriq.backend.engine.Reference.Corridor;
import com.corridoriq.backend.engine.Reference.Template;

/** Writes a generated history to a new SQLite database: the schema, the reference data, then the generated rows. */
public final class HistoryWriter {

    private HistoryWriter() {
    }

    public static final String LABEL_DEFINITION =
            "route_candidate.utility = w*norm(log realized_hours) + (1-w)*norm(log realized_cost) within the payment, "
                    + "w = priority/100; +1.0 if returned; +0.5 if credited after the deadline. "
                    + "relevance = 4,3,2,1 by ascending utility, else 0.";

    /**
     * Creates the database at {@code out}. Fails if the file exists, unless {@code overwrite} is set, because
     * the backend and the saved models depend on the database that is already there.
     */
    public static void write(Path out, Path schema, HistoryGenerator.Output data, boolean overwrite)
            throws IOException, SQLException {
        if (Files.exists(out)) {
            if (!overwrite) {
                throw new IOException(out + " already exists; choose another --out or pass --force to replace it");
            }
            Files.delete(out);
        }
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        List<String> ddl = SqlScript.statements(Files.readString(schema));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + out.toAbsolutePath())) {
            try (Statement st = conn.createStatement()) {
                for (String sql : ddl) {
                    st.execute(sql);
                }
            }
            conn.setAutoCommit(false);
            insertAll(conn, data);
            conn.commit();
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("ANALYZE");
                st.execute("VACUUM");
            }
        }
    }

    private static void insertAll(Connection c, HistoryGenerator.Output data) throws SQLException {
        insert(c, "currency", 4, Reference.CURRENCIES.stream()
                .map(x -> new Object[] {x.code(), x.name(), x.decimals(), x.peggedToUsd()}).toList());
        insert(c, "country", 6, Reference.COUNTRIES.values().stream()
                .map(x -> new Object[] {x.code(), x.name(), x.tzName(),
                        x.weekendDays().stream().sorted().map(String::valueOf).collect(Collectors.joining(",")),
                        x.currency(), x.extraRule()}).toList());
        insert(c, "holiday", 4, data.holidays().stream().map(HistoryWriter::holidayRow).toList());
        insert(c, "bank", 12, Reference.BANKS.values().stream().map(HistoryWriter::bankRow).toList());
        insert(c, "bank_closure", 3, HistoryGenerator.CLOSURES.entrySet().stream()
                .map(e -> new Object[] {e.getKey().bankId(), e.getKey().date().toString(), e.getValue()}).toList());
        insert(c, "corridor", 7, Reference.CORRIDORS.values().stream()
                .map(x -> new Object[] {x.id(), "US", x.destCountry(), x.currency(), x.localRail(), x.countryRisk(),
                        x.clearingBank()}).toList());
        List<Object[]> bene = new ArrayList<>();
        List<Object[]> corridorRoute = new ArrayList<>();
        for (Corridor x : Reference.CORRIDORS.values()) {
            x.beneBanks().forEach(b -> bene.add(new Object[] {x.id(), b.bankId(), b.share()}));
            x.templates().forEach(t -> corridorRoute.add(new Object[] {x.id(), t}));
        }
        insert(c, "beneficiary_bank", 3, bene);
        List<Object[]> templates = new ArrayList<>();
        List<Object[]> templateHops = new ArrayList<>();
        for (Template t : Reference.TEMPLATES.values()) {
            templates.add(new Object[] {t.id(), t.name(), t.via(), t.gpi() ? 1 : 0, t.stpRate(), t.cap(),
                    t.retailOnly() ? 1 : 0, t.nonBank() ? 1 : 0, t.instantPayout() ? 1 : 0, t.batchHop(),
                    t.fxBpsStandard(), t.fxBpsLarge(), t.note()});
            for (int i = 0; i < t.hops().size(); i++) {
                templateHops.add(new Object[] {t.id(), i, t.hops().get(i), t.fees()[i], t.procHours()[i]});
            }
        }
        insert(c, "route_template", 13, templates);
        insert(c, "route_template_hop", 5, templateHops);
        insert(c, "corridor_route", 2, corridorRoute);
        insert(c, "fx_rate_daily", 4, data.fxRates());
        insert(c, "cause_code", 3, Reference.CAUSE_CODES.stream()
                .map(x -> new Object[] {x.code(), x.category(), x.label()}).toList());
        insert(c, "client", 5, data.clients());
        insert(c, "payment", 34, data.payments());
        insert(c, "payment_hop", 11, data.hops());
        insert(c, "route_candidate", 22, data.candidates());
        insert(c, "incident (payment_id, cause_code, note, annotated_by, annotated_at_utc)", 5, data.incidents());
        insert(c, "meta", 2, meta(data).entrySet().stream().map(e -> new Object[] {e.getKey(), e.getValue()}).toList());
    }

    private static Object[] holidayRow(HolidayRow r) {
        return new Object[] {r.country(), r.date().toString(), r.name(), r.source()};
    }

    private static Object[] bankRow(Bank b) {
        return new Object[] {b.id(), b.name(), b.bic(), b.city(), b.country(), b.role(), "09:00",
                b.cutoff() == null ? null : String.format(Locale.ROOT, "%02d:00", b.cutoff()), b.allDay() ? 1 : 0,
                b.intermediary() ? 1 : 0, b.nonbank() ? 1 : 0, b.risk()};
    }

    private static Map<String, String> meta(HistoryGenerator.Output data) {
        HistoryGenerator.Config cfg = data.config();
        String source = data.holidays().stream().map(HolidayRow::source).filter(s -> !s.equals("rule")).findFirst()
                .orElse("holidays ?");
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("generator_version", HistoryGenerator.VERSION);
        m.put("generator", "java: com.corridoriq.backend.generator.GenerateHistory");
        m.put("seed", String.valueOf(cfg.seed()));
        m.put("window_start", cfg.start().toString());
        m.put("window_end", cfg.end().toString());
        m.put("payments", String.valueOf(data.payments().size()));
        m.put("synthetic", "yes - all banks, clients, rates and outcomes are test data");
        m.put("holidays_library", source + " (Islamic holidays are estimated)");
        m.put("large_ticket_usd", String.valueOf((long) Reference.LARGE_TICKET_USD));
        m.put("ordering_bank", Reference.ORDERING_BANK);
        m.put("label_definition", LABEL_DEFINITION);
        m.put("created_at_utc", Dates.iso(Instant.now()));
        return m;
    }

    private static void insert(Connection c, String target, int columns, Collection<Object[]> rows) throws SQLException {
        String sql = "INSERT INTO " + target + " VALUES (" + "?,".repeat(columns - 1) + "?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (Object[] row : rows) {
                for (int i = 0; i < columns; i++) {
                    ps.setObject(i + 1, row[i]);
                }
                ps.addBatch();
                if (++pending == 5000) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }
}
