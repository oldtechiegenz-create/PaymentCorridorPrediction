package com.corridoriq.backend.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Date and time helpers shared by the engine and the generator. Instants are always UTC. */
public final class Dates {

    private static final String[] DOW = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Dates() {
    }

    /** Weekday number as the portal and the database use it: 0 = Sunday ... 6 = Saturday. */
    public static int portalDow(LocalDate d) {
        return d.getDayOfWeek().getValue() % 7;   // java.time: Monday = 1 ... Sunday = 7
    }

    /** For example "Thu 9 Oct 2025". */
    public static String dayLabel(LocalDate d) {
        return DOW[portalDow(d)] + " " + d.getDayOfMonth() + " " + MON[d.getMonthValue() - 1] + " " + d.getYear();
    }

    /** The instant at which it is {@code time} on {@code day} in {@code zone}. */
    public static Instant at(LocalDate day, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(day, time, zone).toInstant();
    }

    public static Instant at(LocalDate day, int hour, ZoneId zone) {
        return at(day, LocalTime.of(hour, 0), zone);
    }

    /** Adds fractional hours, to the microsecond like Python's timedelta. */
    public static Instant plusHours(Instant t, double hours) {
        return t.plus(Duration.ofNanos(Math.round(hours * 3_600_000_000.0) * 1000L));
    }

    public static double hoursBetween(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        return (d.getSeconds() + d.getNano() / 1e9) / 3600.0;
    }

    /** Local hour with minutes as a fraction, e.g. 13.5 for 13:30. */
    public static double localHour(ZonedDateTime t) {
        return t.getHour() + t.getMinute() / 60.0;
    }

    /** "2026-04-01T13:01:00Z": whole seconds, UTC, as stored in the history database. */
    public static String iso(Instant t) {
        return ISO_UTC.format(t);
    }

    /** Rounds to {@code places} decimals, ties to even on the double's exact value, like Python's round(). */
    public static double round(double x, int places) {
        return new BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }
}
