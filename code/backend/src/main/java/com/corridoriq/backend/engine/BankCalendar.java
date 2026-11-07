package com.corridoriq.backend.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Banking calendar: which days each country's banks are closed. (This replaced the Python engine.py's
 * {@code Calendar}.)
 *
 * <p>The holiday dates themselves come from data, not code: the CSV in resources/reference/holidays.csv
 * (exported from the history database, where the Python generator wrote them with the Fedwire and Japanese
 * bank-holiday rules already applied), or the {@code holiday} and {@code bank_closure} tables of a database.
 */
public final class BankCalendar {

    /** One row of the {@code holiday} table. */
    public record HolidayRow(String country, LocalDate date, String name, String source) {
    }

    public record Closure(String bankId, LocalDate date) {
    }

    private static final String HOLIDAY_RESOURCE = "/reference/holidays.csv";

    private final Map<String, Map<LocalDate, String>> holidays = new HashMap<>();
    private final Map<Closure, String> closures;

    public BankCalendar(List<HolidayRow> rows, Map<Closure, String> closures) {
        for (HolidayRow r : rows) {
            holidays.computeIfAbsent(r.country(), k -> new HashMap<>()).put(r.date(), r.name());
        }
        this.closures = closures == null ? Map.of() : Map.copyOf(closures);
    }

    // ------------------------------------------------------------------ loading
    /** Holiday rows from the CSV bundled with the backend. */
    public static List<HolidayRow> bundledHolidays() {
        try (InputStream in = BankCalendar.class.getResourceAsStream(HOLIDAY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + HOLIDAY_RESOURCE);
            }
            return readHolidayCsv(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BankCalendar bundled(Map<Closure, String> closures) {
        return new BankCalendar(bundledHolidays(), closures);
    }

    /** The calendar stored in a history database. */
    public static BankCalendar fromDatabase(Connection conn) throws SQLException {
        List<HolidayRow> rows = new ArrayList<>();
        Map<Closure, String> closures = new HashMap<>();
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT country_code, holiday_date, name, source FROM holiday")) {
                while (rs.next()) {
                    rows.add(new HolidayRow(rs.getString(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                            rs.getString(4)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT bank_id, closure_date, reason FROM bank_closure")) {
                while (rs.next()) {
                    closures.put(new Closure(rs.getString(1), LocalDate.parse(rs.getString(2))), rs.getString(3));
                }
            }
        }
        return new BankCalendar(rows, closures);
    }

    static List<HolidayRow> readHolidayCsv(BufferedReader reader) throws IOException {
        List<HolidayRow> rows = new ArrayList<>();
        String line = reader.readLine();   // header
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = splitCsv(line);
            rows.add(new HolidayRow(f.get(0), LocalDate.parse(f.get(1)), f.get(2), f.get(3)));
        }
        return rows;
    }

    /** Splits one CSV line; fields may be double-quoted, with "" for a quote inside. */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ queries
    /** The reason the day is closed for banks in {@code country}, or null if it is a business day. */
    public String nonBusiness(String country, LocalDate d) {
        String name = holidays.getOrDefault(country, Map.of()).get(d);
        if (name != null) {
            return name;
        }
        int dow = Dates.portalDow(d);
        if (Reference.country(country).weekendDays().contains(dow)) {
            if (country.equals("BH") || country.equals("SA")) {
                return (dow == 5 ? "Friday" : "Saturday") + " · Gulf weekend";
            }
            return dow == 0 ? "Sunday" : "Saturday";
        }
        if (country.equals("IN") && dow == 6) {
            int n = (d.getDayOfMonth() - 1) / 7 + 1;
            if (n == 2 || n == 4) {
                return (n == 2 ? "2nd" : "4th") + " Saturday · Indian bank holiday";
            }
        }
        return null;
    }

    /** The reason this bank is closed on the day (an ad hoc closure first), or null if it is open. */
    public String bankClosed(String bankId, String country, LocalDate d) {
        String closure = closures.get(new Closure(bankId, d));
        return closure != null ? closure : nonBusiness(country, d);
    }

    /** First and last year with holiday data for {@code country}; null if there is none. */
    public int[] holidayYears(String country) {
        Map<LocalDate, String> m = holidays.get(country);
        if (m == null || m.isEmpty()) {
            return null;
        }
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (LocalDate d : m.keySet()) {
            lo = Math.min(lo, d.getYear());
            hi = Math.max(hi, d.getYear());
        }
        return new int[] {lo, hi};
    }
}
