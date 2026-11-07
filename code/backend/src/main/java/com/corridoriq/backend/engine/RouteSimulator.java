package com.corridoriq.backend.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.corridoriq.backend.engine.Reference.Bank;
import com.corridoriq.backend.engine.Reference.Template;

/**
 * Hop-by-hop route simulation: how long a payment takes through each bank on a route, waiting for open days,
 * cut-offs, opening hours and the netting batch. (This replaced the Python engine.py.)
 *
 * <p>The same simulation gives the deterministic estimate shown to operators (nominal processing times) and,
 * in the generator, the realised history (noisy processing, repairs, returns). All instants are UTC.
 */
public final class RouteSimulator {

    private RouteSimulator() {
    }

    /** One bank on a route, with the facts the simulation needs. cutoff is null for a bank that runs all day. */
    public record Hop(String bankId, String name, String country, Integer cutoff, boolean allDay,
                      boolean intermediary, boolean nonbank, int risk, double speed) {
    }

    public enum HoldKind {
        CALENDAR, CUTOFF, BATCH, REPAIR;

        /** The value stored in the database: calendar, cutoff, batch or repair. */
        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why a payment waited at a hop, on which local day, and for how many hours. */
    public record Hold(HoldKind kind, String reason, LocalDate day, double hours) {
    }

    public record Step(Hop hop, Instant arrive, Instant start, Instant leave, List<Hold> holds) {
    }

    /** end is the credit time; hours counts from release; calendarDays is the distinct closed days that held it. */
    public record SimResult(List<Step> steps, Instant end, double hours, int calendarDays) {
    }

    /** An information request raised at a hop. */
    public record Repair(int hopIndex, double hours, String reason) {
    }

    /** Processing hours at a hop; may depend on the hop and the moment it starts (noisy realisations). */
    @FunctionalInterface
    public interface ProcessingTime {
        double hours(int hopIndex, Hop hop, Instant start);
    }

    /** The same hours for every payment: the template's nominal times. */
    public static ProcessingTime fixed(double[] hours) {
        return (i, hop, start) -> hours[i];
    }

    // ------------------------------------------------------------------ routes
    public static Hop hopOf(String bankId) {
        Bank b = Reference.bank(bankId);
        return new Hop(b.id(), b.name(), b.country(), b.cutoff(), b.allDay(), b.intermediary(), b.nonbank(),
                b.risk(), b.speed());
    }

    /** The template's banks, with CLR and BENE replaced by the corridor's clearing bank and the payment's bank. */
    public static List<Hop> resolveHops(String templateId, String corridorId, String beneBankId) {
        String clearing = Reference.corridor(corridorId).clearingBank();
        List<Hop> out = new ArrayList<>();
        for (String ref : Reference.template(templateId).hops()) {
            out.add(hopOf(ref.equals("CLR") ? clearing : ref.equals("BENE") ? beneBankId : ref));
        }
        return out;
    }

    private static Instant nextOpen(LocalDate day, ZoneId tz) {
        return Dates.at(day.plusDays(1), 9, tz);
    }

    // ------------------------------------------------------------------ simulation
    /** Walks the payment through each hop, waiting for open days, cut-offs, opening hours and the batch run. */
    public static SimResult simulate(List<Hop> hops, String templateId, Instant t0, BankCalendar cal,
                                     ProcessingTime proc, Repair repair) {
        Template tpl = Reference.template(templateId);
        boolean instant = tpl.instantPayout();
        Integer batchHop = tpl.batchHop();
        Instant t = t0;
        List<Step> steps = new ArrayList<>();
        Set<String> calDays = new HashSet<>();
        int last = hops.size() - 1;
        for (int i = 0; i <= last; i++) {
            Hop h = hops.get(i);
            ZoneId tz = Reference.zone(h.country());
            Instant arrive = t;
            List<Hold> holds = new ArrayList<>();
            boolean allDay = h.allDay() || (instant && i == last);
            if (!allDay) {
                for (int guard = 0; guard < 60; guard++) {   // bounded, in case of a very long closure
                    ZonedDateTime local = t.atZone(tz);
                    LocalDate d = local.toLocalDate();
                    double hr = Dates.localHour(local);
                    String closed = cal.bankClosed(h.bankId(), h.country(), d);
                    if (closed != null) {
                        Instant next = nextOpen(d, tz);
                        holds.add(new Hold(HoldKind.CALENDAR, closed, d, Dates.hoursBetween(t, next)));
                        calDays.add(h.country() + "|" + d);
                        t = next;
                        continue;
                    }
                    if (h.cutoff() != null && hr >= h.cutoff()) {
                        Instant next = nextOpen(d, tz);
                        holds.add(new Hold(HoldKind.CUTOFF, String.format(Locale.ROOT, "after %02d:00 cut-off", h.cutoff()),
                                d, Dates.hoursBetween(t, next)));
                        t = next;
                        continue;
                    }
                    if (hr < 9) {
                        t = Dates.at(d, 9, tz);
                        continue;
                    }
                    break;
                }
            }
            if (batchHop != null && batchHop == i) {
                ZonedDateTime local = t.atZone(tz);
                int add = (4 - Dates.portalDow(local.toLocalDate()) + 7) % 7;   // next Thursday
                if (add == 0 && local.getHour() >= 16) {
                    add = 7;
                }
                Instant batch = Dates.at(local.toLocalDate().plusDays(add), 16, tz);
                if (batch.isAfter(t)) {
                    holds.add(new Hold(HoldKind.BATCH, "held for Thursday netting batch",
                            batch.atZone(tz).toLocalDate(), Dates.hoursBetween(t, batch)));
                    t = batch;
                }
            }
            Instant start = t;
            t = Dates.plusHours(t, proc.hours(i, h, start));
            if (repair != null && repair.hopIndex() == i) {
                holds.add(new Hold(HoldKind.REPAIR, repair.reason(), t.atZone(tz).toLocalDate(), repair.hours()));
                t = Dates.plusHours(t, repair.hours());
            }
            steps.add(new Step(h, arrive, start, t, holds));
        }
        return new SimResult(steps, t, Dates.hoursBetween(t0, t), calDays.size());
    }

    public static SimResult simulate(List<Hop> hops, String templateId, Instant t0, BankCalendar cal, ProcessingTime proc) {
        return simulate(hops, templateId, t0, cal, proc, null);
    }

    /** What operators estimated before CorridorIQ: processing time plus the US cut-off only. */
    public static double naiveHours(Instant t0, BankCalendar cal, double[] nominal) {
        ZoneId tz = Reference.zone("US");
        ZonedDateTime local = t0.atZone(tz);
        double wait = 0.0;
        int cutoff = Reference.bank(Reference.ORDERING_BANK).cutoff();
        if (Dates.localHour(local) >= cutoff || cal.nonBusiness("US", local.toLocalDate()) != null) {
            LocalDate d = local.toLocalDate().plusDays(1);
            while (cal.nonBusiness("US", d) != null) {
                d = d.plusDays(1);
            }
            wait = Dates.hoursBetween(t0, Dates.at(d, LocalTime.of(9, 0), tz));
        }
        double sum = 0;
        for (double n : nominal) {
            sum += n;
        }
        return wait + sum;
    }

    /** True when an intermediary is closed on the value date or the next day while the US is open. */
    public static boolean calendarConflict(List<Hop> hops, LocalDate valueDate, BankCalendar cal) {
        for (Hop h : hops) {
            if (!h.intermediary() || h.allDay()) {
                continue;
            }
            for (int offset = 0; offset <= 1; offset++) {
                LocalDate d = valueDate.plusDays(offset);
                if (cal.nonBusiness("US", d) == null && cal.bankClosed(h.bankId(), h.country(), d) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
