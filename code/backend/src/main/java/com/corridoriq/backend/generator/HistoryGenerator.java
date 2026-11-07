package com.corridoriq.backend.generator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import com.corridoriq.backend.engine.BankCalendar;
import com.corridoriq.backend.engine.BankCalendar.Closure;
import com.corridoriq.backend.engine.BankCalendar.HolidayRow;
import com.corridoriq.backend.engine.Dates;
import com.corridoriq.backend.engine.Reference;
import com.corridoriq.backend.engine.Reference.SeedIncident;
import com.corridoriq.backend.engine.Reference.Template;
import com.corridoriq.backend.engine.RouteEstimator;
import com.corridoriq.backend.engine.RouteEstimator.RouteEstimate;
import com.corridoriq.backend.engine.RouteSimulator;
import com.corridoriq.backend.engine.RouteSimulator.Hold;
import com.corridoriq.backend.engine.RouteSimulator.HoldKind;
import com.corridoriq.backend.engine.RouteSimulator.Hop;
import com.corridoriq.backend.engine.RouteSimulator.ProcessingTime;
import com.corridoriq.backend.engine.RouteSimulator.Repair;
import com.corridoriq.backend.engine.RouteSimulator.SimResult;
import com.corridoriq.backend.engine.RouteSimulator.Step;

/**
 * Generates a synthetic CorridorIQ payment history. Replaces the former Python generate_history.py.
 *
 * <p>For every payment it simulates EVERY route that was available: the estimate known at release and the
 * outcome that route would really have had, using the same luck (bank speeds, backlogs, repairs, returns) for
 * all routes of the payment. The route actually used is picked by a pre-CorridorIQ operator policy. The result
 * is a complete ranking dataset: features known at release, realised outcome and a label for every route.
 *
 * <p>Everything is synthetic and reproducible from the seed. Rows come back as plain arrays in table column
 * order, ready for {@link HistoryWriter}.
 */
public final class HistoryGenerator {

    public static final String VERSION = "1.0.0";

    /** payments is the total, including the portal's seeded incidents that fall inside the window. */
    public record Config(int payments, LocalDate start, LocalDate end, long seed) {
    }

    /** Rows for each table, in the column order of schema.sql. */
    public record Output(Config config, List<HolidayRow> holidays, List<Object[]> fxRates, List<Object[]> clients,
                         List<Object[]> payments, List<Object[]> hops, List<Object[]> candidates,
                         List<Object[]> incidents) {
    }

    /** Ad hoc closures of single banks, on top of the country calendars. */
    public static final Map<Closure, String> CLOSURES = Map.of(
            new Closure("SG", LocalDate.of(2025, 3, 14)), "Core banking upgrade",
            new Closure("BH", LocalDate.of(2026, 2, 10)), "Payment system outage",
            new Closure("IN_CLR", LocalDate.of(2025, 11, 21)), "Settlement system maintenance");

    private static final Map<String, Double> RETAIL_CORRIDOR_MIX =
            Rng.weights("USD-INR", .40, "USD-JPY", .18, "USD-AED", .17, "USD-EUR", .13, "USD-SAR", .12);
    private static final Map<String, Double> FI_CORRIDOR_MIX =
            Rng.weights("USD-JPY", .25, "USD-EUR", .25, "USD-AED", .20, "USD-INR", .20, "USD-SAR", .10);
    private static final Map<String, Double> RETAIL_MEDIAN =
            Map.of("USD-JPY", 3000.0, "USD-INR", 900.0, "USD-AED", 1500.0, "USD-SAR", 1500.0, "USD-EUR", 2500.0);
    private static final Map<String, Map<String, Double>> RETAIL_PURPOSE = Map.of(
            "USD-JPY", Rng.weights("EDUC", .45, "OTHR", .45, "SUPP", .10),
            "USD-INR", Rng.weights("OTHR", .70, "EDUC", .20, "SUPP", .10),
            "USD-AED", Rng.weights("OTHR", .60, "SUPP", .25, "EDUC", .15),
            "USD-SAR", Rng.weights("OTHR", .65, "SUPP", .25, "EDUC", .10),
            "USD-EUR", Rng.weights("EDUC", .35, "OTHR", .35, "SUPP", .30));
    private static final Map<String, Double> FI_PURPOSE = Rng.weights("SUPP", .65, "INTC", .20, "SALA", .15);

    private record Retail(String id, String home, String segment) {
    }

    private record Fi(String id, Map<String, Double> mix, String segment) {
    }

    private static final class Job {
        LocalDate date;
        SeedIncident seed;
        boolean monthEnd;
        double hour;
    }

    private final Config cfg;
    private final Rng rng;
    private final BankCalendar cal;
    private final List<HolidayRow> holidays;
    private FxSeries.Result fx;
    private final List<Retail> retailPool = new ArrayList<>();
    private final List<Fi> fiPool = new ArrayList<>();
    private double[] retailCum;
    private double[] fiCum;
    private final Map<String, Integer> sequenceByDay = new HashMap<>();

    private final List<Object[]> clients = new ArrayList<>();
    private final List<Object[]> payments = new ArrayList<>();
    private final List<Object[]> hopRows = new ArrayList<>();
    private final List<Object[]> candidateRows = new ArrayList<>();
    private final List<Object[]> incidents = new ArrayList<>();

    private HistoryGenerator(Config cfg, List<HolidayRow> holidays) {
        this.cfg = cfg;
        this.rng = new Rng(cfg.seed());
        this.holidays = holidays;
        this.cal = new BankCalendar(holidays, CLOSURES);
    }

    /**
     * @param holidays the holiday calendar to use, e.g. {@link BankCalendar#bundledHolidays()}
     * @param progress called with the number of payments done, every 5,000; may be null
     */
    public static Output generate(Config cfg, List<HolidayRow> holidays, IntConsumer progress) {
        return new HistoryGenerator(cfg, holidays).run(progress);
    }

    // ------------------------------------------------------------------ helpers (public for tests)
    /** Deterministic bank-day backlog: most days 1.0, about 7% of days between 1.5 and 3.5. */
    public static double congestionFactor(long seed, String bankId, LocalDate day) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("MD5").digest((seed + "|" + bankId + "|" + day).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        double u = (hash[0] & 0xFF) / 256.0;
        double u2 = (hash[1] & 0xFF) / 256.0;
        return u < 0.07 ? 1.5 + 2.0 * u2 : 1.0;
    }

    /** Rounds a payment amount the way people send money: to 10, 50, 100 or 1,000 by size. */
    public static double roundAmount(double a) {
        if (a < 1000) {
            return Math.max(10.0, Math.rint(a / 10) * 10);
        }
        if (a < 10000) {
            return Math.rint(a / 50) * 50;
        }
        if (a < 100000) {
            return Math.rint(a / 100) * 100;
        }
        return Math.rint(a / 1000) * 1000;
    }

    static String fmtHours(double h) {
        return h < 48 ? String.format(Locale.ROOT, "%.1f h", h) : String.format(Locale.ROOT, "%.1f days", h / 24);
    }

    private static String fmtLocal(Instant t, String country) {
        ZonedDateTime local = t.atZone(Reference.zone(country));
        String label = Dates.dayLabel(local.toLocalDate());
        return label.substring(0, label.length() - 5) + String.format(Locale.ROOT, " %02d:%02d", local.getHour(), local.getMinute());
    }

    // ------------------------------------------------------------------ generation
    private Output run(IntConsumer progress) {
        LocalDate start = cfg.start();
        LocalDate end = cfg.end();
        int[] years = cal.holidayYears("US");
        if (years == null || start.getYear() < years[0] || end.getYear() > years[1]) {
            throw new IllegalArgumentException("The holiday calendar covers " + (years == null ? "no years"
                    : years[0] + " to " + years[1]) + "; the window " + start + " to " + end + " needs more");
        }
        fx = FxSeries.generate(rng, start.minusDays(120), end);
        createClients();

        // business days and daily volume
        List<LocalDate> bdays = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (cal.nonBusiness("US", d) == null) {
                bdays.add(d);
            }
        }
        if (bdays.isEmpty()) {
            throw new IllegalArgumentException("No US business days between " + start + " and " + end);
        }
        boolean[] monthEnd = new boolean[bdays.size()];
        double[] weights = new double[bdays.size()];
        long span = Math.max(1, end.toEpochDay() - start.toEpochDay());
        for (int i = 0; i < bdays.size(); i++) {
            LocalDate d = bdays.get(i);
            double w = 0.8 + 0.45 * (d.toEpochDay() - start.toEpochDay()) / span;       // growth
            int dow = Dates.portalDow(d);
            w *= dow == 1 ? 1.15 : dow == 5 ? 1.05 : 1.0;                                // Monday, Friday
            int following = Math.min(2, bdays.size() - i - 1);
            monthEnd[i] = following < 2 || bdays.get(i + 2).getMonthValue() != d.getMonthValue();
            if (monthEnd[i]) {
                w *= 1.25;                                                               // month end
            }
            weights[i] = w;
        }
        int seeded = (int) Reference.SEEDS.stream().filter(s -> {
            LocalDate sd = LocalDate.parse(s.date());
            return !sd.isBefore(start) && !sd.isAfter(end);
        }).count();
        int[] counts = new int[bdays.size()];
        double[] cum = Rng.cumulative(weights);
        for (int k = 0; k < cfg.payments() - seeded; k++) {
            counts[rng.weightedIndex(cum)]++;
        }

        List<Job> jobs = new ArrayList<>();
        Map<LocalDate, Boolean> monthEndByDay = new HashMap<>();
        for (int i = 0; i < bdays.size(); i++) {
            monthEndByDay.put(bdays.get(i), monthEnd[i]);
            for (int n = 0; n < counts[i]; n++) {
                Job j = new Job();
                j.date = bdays.get(i);
                jobs.add(j);
            }
        }
        for (SeedIncident s : Reference.SEEDS) {
            LocalDate sd = LocalDate.parse(s.date());
            if (!sd.isBefore(start) && !sd.isAfter(end)) {
                Job j = new Job();
                j.date = sd;
                j.seed = s;
                jobs.add(j);
            }
        }
        for (Job j : jobs) {
            j.monthEnd = monthEndByDay.getOrDefault(j.date, false);
            double r = rng.random();
            j.hour = r < 0.62 ? rng.uniform(9, 13) : r < 0.93 ? rng.uniform(13, 16) : rng.uniform(16, 18);
            if (j.seed != null) {
                j.hour = 14.0;
            }
        }
        jobs.sort((a, b) -> a.date.equals(b.date) ? Double.compare(a.hour, b.hour) : a.date.compareTo(b.date));

        for (int pid = 1; pid <= jobs.size(); pid++) {
            payment(pid, jobs.get(pid - 1));
            if (progress != null && pid % 5000 == 0) {
                progress.accept(pid);
            }
        }
        return new Output(cfg, holidays, fx.rows(), clients, payments, hopRows, candidateRows, incidents);
    }

    private void createClients() {
        int nRetail = Math.max(400, (int) (cfg.payments() * 0.12));
        double[] retailWeights = new double[nRetail];
        for (int i = 0; i < nRetail; i++) {
            String id = String.format(Locale.ROOT, "R%06d", i + 1);
            String home = rng.pick(RETAIL_CORRIDOR_MIX);
            String segment = rng.random() < 0.12 ? "Small business" : "Personal";
            String name = rng.choice(Reference.SENDER_FIRST) + " " + rng.choice(Reference.SENDER_LAST);
            clients.add(new Object[] {id, "Retail", name, segment, home});
            retailPool.add(new Retail(id, home, segment));
            retailWeights[i] = rng.pareto(1.4);
        }
        double[] fiWeights = new double[Reference.FI_CLIENTS.size()];
        for (int i = 0; i < Reference.FI_CLIENTS.size(); i++) {
            String name = Reference.FI_CLIENTS.get(i);
            String id = String.format(Locale.ROOT, "F%04d", i + 1);
            String segment = name.contains("Bank") || name.contains("Credit Union") ? "Financial institution" : "Corporate";
            Map<String, Double> mix = new java.util.LinkedHashMap<>();
            String home = null;
            double best = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, Double> e : FI_CORRIDOR_MIX.entrySet()) {
                double v = e.getValue() * rng.uniform(0.3, 1.7);
                mix.put(e.getKey(), v);
                if (v > best) {
                    best = v;
                    home = e.getKey();
                }
            }
            clients.add(new Object[] {id, "FI", name, segment, home});
            fiPool.add(new Fi(id, mix, segment));
            fiWeights[i] = rng.pareto(1.6);
        }
        retailCum = Rng.cumulative(retailWeights);
        fiCum = Rng.cumulative(fiWeights);
    }

    /** The luck of one payment, drawn once and shared by every candidate route. */
    private static final class Luck {
        private final Rng prng;
        private final Map<String, Double> noise = new HashMap<>();
        private final Map<String, Double> extraFee = new HashMap<>();

        Luck(Rng prng) {
            this.prng = prng;
        }

        /** A bank's speed multiplier for this payment; drawn on first use, with a small chance of a surprise fee. */
        double bankNoise(String bankId) {
            Double n = noise.get(bankId);
            if (n == null) {
                n = prng.lognormal(0, 0.30);
                noise.put(bankId, n);
                extraFee.put(bankId, prng.random() < 0.06 ? prng.uniform(5, 15) : 0.0);
            }
            return n;
        }

        double extraFee(String bankId) {
            return extraFee.get(bankId);
        }
    }

    private void payment(int pid, Job j) {
        Rng prng = Rng.forPayment(cfg.seed(), pid);
        LocalDate vd = j.date;
        SeedIncident s = j.seed;
        double h = j.hour;
        ZoneId us = Reference.zone("US");
        Instant t0 = Dates.at(vd, LocalTime.of((int) h, (int) ((h % 1) * 60)), us);

        // --- who, where, how much
        String ctype;
        String corridor;
        String cid;
        String seg;
        if (s != null) {
            ctype = s.client();
            corridor = s.corridor();
            if (ctype.equals("FI")) {
                cid = fiPool.getFirst().id();
                seg = fiPool.getFirst().segment();
            } else {
                Retail r = retailPool.getFirst();
                for (Retail c : retailPool) {
                    if (c.home().equals(corridor)) {
                        r = c;
                        break;
                    }
                }
                cid = r.id();
                seg = r.segment();
            }
        } else if (prng.random() < 0.72) {
            ctype = "Retail";
            Retail r = retailPool.get(rng.weightedIndex(retailCum));
            cid = r.id();
            seg = r.segment();
            corridor = prng.random() < 0.9 ? r.home() : prng.pick(RETAIL_CORRIDOR_MIX);
        } else {
            ctype = "FI";
            Fi f = fiPool.get(rng.weightedIndex(fiCum));
            cid = f.id();
            seg = f.segment();
            corridor = prng.pick(f.mix());
        }
        String destCountry = Reference.corridor(corridor).destCountry();
        String ccy = Reference.corridor(corridor).currency();

        String purpose;
        double amount;
        String charges;
        int priority;
        LocalDate deadline;
        String beneName;
        if (ctype.equals("Retail")) {
            purpose = seg.equals("Small business") && prng.random() < 0.7 ? "SUPP" : prng.pick(RETAIL_PURPOSE.get(corridor));
            double amt = prng.lognormal(Math.log(RETAIL_MEDIAN.get(corridor)), 0.95) * (purpose.equals("EDUC") ? 2.5 : 1.0);
            amount = roundAmount(Math.min(60000, Math.max(50, amt)));
            charges = prng.pick(Rng.weights("OUR", .55, "SHA", .40, "BEN", .05));
            double r = prng.random();
            if (purpose.equals("EDUC") || r < 0.35) {
                priority = 80 + 5 * prng.randint(0, 4);       // wants it fast: 80 to 100
            } else if (r < 0.75) {
                priority = 50 + 5 * prng.randint(0, 4);       // balanced: 50 to 70
            } else {
                priority = 5 * prng.randint(0, 5);            // wants it cheap: 0 to 25
            }
            boolean hasDeadline = prng.random() < (purpose.equals("EDUC") || priority >= 80 ? 0.35 : 0.08);
            deadline = hasDeadline ? vd.plusDays(prng.randint(2, 6)) : null;
            beneName = prng.choice(Reference.BENE_PERSON.get(corridor).get(0)) + " "
                    + prng.choice(Reference.BENE_PERSON.get(corridor).get(1));
        } else {
            purpose = prng.pick(FI_PURPOSE);
            amount = roundAmount(Math.min(5_000_000, Math.max(5000, prng.lognormal(Math.log(90000), 1.25))));
            charges = prng.pick(Rng.weights("SHA", .70, "OUR", .25, "BEN", .05));
            priority = (int) Math.max(0, Math.min(100, Math.rint(Math.rint(prng.gauss(50, 15) / 5) * 5)));
            deadline = prng.random() < 0.15 ? vd.plusDays(prng.randint(1, 3)) : null;
            beneName = prng.choice(Reference.BENE_COMPANY.get(corridor));
        }
        if (s != null) {
            amount = s.amount();
            if (s.template().equals("ECO")) {
                priority = 10;
            }
        }
        List<Reference.BeneShare> beneShares = Reference.corridor(corridor).beneBanks();
        double[] beneCum = Rng.cumulative(beneShares.stream().mapToDouble(Reference.BeneShare::share).toArray());
        String beneBank = beneShares.get(prng.weightedIndex(beneCum)).bankId();
        Instant deadlineEnd = deadline == null ? null
                : Dates.at(deadline.plusDays(1), 0, Reference.zone(destCountry));

        // --- shared randomness across candidate routes (consistent counterfactuals)
        Luck luck = new Luck(prng);
        final double largeFactor = amount > 50000 ? 1.0 + 0.25 * Math.max(0.0, Math.log10(amount / 50000)) : 1.0;
        final double monthEndFactor = j.monthEnd ? 1.3 : 1.0;
        double uIssue = prng.random();
        double uRet = prng.random();
        String rcause = ctype.equals("FI")
                ? prng.pick(Rng.weights("RFI_PURPOSE", .35, "RFI_ADDRESS", .35, "RFI_NAME", .30))
                : prng.pick(Rng.weights("RFI_DOCS", .35, "RFI_PURPOSE", .30, "RFI_NAME", .20, "RFI_ADDRESS", .15));
        double rhours = Math.min(48, Math.max(1.5, prng.lognormal(Math.log(6), 0.6)));
        String retCause = prng.pick(Rng.weights("RET_ACCOUNT_CLOSED", .40, "RET_NAME_MISMATCH", .35, "RET_INVALID_ACCOUNT", .25));
        double retExtra = prng.uniform(48, 120);
        if (s != null) {
            // A seeded payment reproduces a specific portal case, so luck must not change its story:
            // it is never returned, and it is repaired only if the seed asks for it. (The Python generator
            // left the repair to chance, which could replace a seeded calendar case with a random repair.)
            uRet = 1.0;
            if (s.repairCause() != null) {
                uIssue = -1.0;
                rcause = s.repairCause();
                rhours = s.repairHours();
            } else {
                uIssue = 1.0;
            }
        }

        // --- every candidate route
        List<Candidate> cands = new ArrayList<>();
        for (RouteEstimate est : RouteEstimator.estimate(corridor, beneBank, ctype, amount, t0, cal)) {
            String tid = est.templateId();
            Template tpl = Reference.template(tid);
            double[] nominal = tpl.procHours();
            final long seed = cfg.seed();
            ProcessingTime realProc = (i, hp, st) -> {
                double f = hp.speed() * luck.bankNoise(hp.bankId())
                        * congestionFactor(seed, hp.bankId(), st.atZone(Reference.zone(hp.country())).toLocalDate());
                if (hp.intermediary() && !hp.nonbank()) {
                    f *= largeFactor;                         // extra screening on large tickets
                }
                return nominal[i] * f * monthEndFactor;
            };
            double pRepair = (1 - tpl.stpRate()) * (amount >= 100000 ? 1.5 : 1.0) * (est.maxRisk() >= 2 ? 1.25 : 1.0);
            boolean repaired = uIssue < pRepair;
            Repair rep = null;
            if (repaired) {
                int ri = Math.min(Reference.REPAIR_HOP.get(tid), est.hops().size() - 1);
                rep = new Repair(ri, rhours, Reference.causeLabel(rcause));
            }
            SimResult real = RouteSimulator.simulate(est.hops(), tid, t0, cal, realProc, rep);
            for (Hop hp : est.hops()) {
                luck.bankNoise(hp.bankId());
            }
            double[] realFees = new double[est.hops().size()];
            for (int i = 0; i < realFees.length; i++) {
                Hop hp = est.hops().get(i);
                realFees[i] = est.fees()[i] + (hp.intermediary() && !hp.nonbank() ? luck.extraFee(hp.bankId()) : 0.0);
            }
            double pRet = 0.006 * (tpl.nonBank() ? 1.5 : 1.0) * (corridor.equals("USD-INR") ? 1.3 : 1.0);
            boolean returned = uRet < pRet;
            double realCost = sum(realFees) + est.fxCostUsd();
            String outcome;
            double realizedHours;
            if (returned) {
                outcome = "Returned";
                realizedHours = real.hours() + retExtra + 72;
                realCost += 25 + est.fxCostUsd();
            } else {
                outcome = repaired ? "Repaired" : "Credited";
                realizedHours = real.hours();
            }
            cands.add(new Candidate(est, realFees, real, realizedHours, realCost, outcome, returned, repaired));
        }
        Labeling.utilityLabels(cands, priority, deadlineEnd);
        Candidate chosen = null;
        if (s != null) {
            for (Candidate c : cands) {
                if (c.templateId().equals(s.template())) {
                    chosen = c;
                }
            }
        } else {
            chosen = OpsPolicy.choose(prng, cands, ctype, amount, priority);
        }

        recordPayment(pid, j, prng, chosen, cid, ctype, corridor, ccy, amount, charges, purpose, priority, deadline,
                deadlineEnd, beneName, beneBank, retExtra);
        recordHops(pid, chosen);
        for (Candidate c : cands) {
            candidateRows.add(candidateRow(pid, c, c == chosen));
        }
        annotate(pid, s, prng, chosen, destCountry, rcause, rhours, retCause, retExtra);
    }

    private void recordPayment(int pid, Job j, Rng prng, Candidate chosen, String cid, String ctype, String corridor,
                               String ccy, double amount, String charges, String purpose, int priority,
                               LocalDate deadline, Instant deadlineEnd, String beneName, String beneBank,
                               double retExtra) {
        LocalDate vd = j.date;
        SimResult real = chosen.real;
        double feeTotal = sum(chosen.realFees);
        double mid = fx.rate(ccy, vd);
        double bps = chosen.estimate.fxBps();
        double rate = mid * (1 - bps / 1e4);
        double intermediaryFees = feeTotal - chosen.realFees[0];
        double converted = charges.equals("OUR") ? amount : charges.equals("SHA") ? amount - intermediaryFees : amount - feeTotal;
        int dp = Reference.currencyDecimals(ccy);
        Instant credited = chosen.returned ? null : real.end();
        Instant returnedAt = chosen.returned ? Dates.plusHours(real.end(), retExtra) : null;
        Integer deadlineMet = null;
        if (deadlineEnd != null) {
            deadlineMet = credited != null && !credited.isAfter(deadlineEnd) ? 1 : 0;
        }
        String ymd = String.format(Locale.ROOT, "%02d%02d%02d", vd.getYear() % 100, vd.getMonthValue(), vd.getDayOfMonth());
        int seq = sequenceByDay.merge(ymd, 1, Integer::sum);
        double fxCost = chosen.estimate.fxCostUsd();
        Instant t0 = Dates.at(vd, LocalTime.of((int) j.hour, (int) ((j.hour % 1) * 60)), Reference.zone("US"));
        payments.add(new Object[] {
                pid, uuid(prng), String.format(Locale.ROOT, "LKSB%s%05d", ymd, seq), cid, ctype, corridor,
                amount, charges, purpose, priority, vd.toString(), deadline == null ? null : deadline.toString(),
                Dates.iso(t0), Dates.round(j.hour, 2), beneName, beneBank, chosen.templateId(), "ops",
                Dates.round(chosen.estimate.estHours(), 2), Dates.round(feeTotal, 2), bps, Dates.round(fxCost, 2),
                Dates.round(feeTotal + fxCost, 2), mid, Dates.round(rate, dp != 0 ? 6 : 4), Dates.round(converted, 2),
                Dates.round(converted * rate, dp), chosen.realizedOutcome, chosen.realizedOutcome.equals("Credited") ? 1 : 0,
                credited == null ? null : Dates.iso(credited), returnedAt == null ? null : Dates.iso(returnedAt),
                credited == null ? null : Dates.round(real.hours(), 2), real.calendarDays(), deadlineMet});
    }

    private void recordHops(int pid, Candidate chosen) {
        List<Step> steps = chosen.real.steps();
        int last = steps.size() - 1;
        for (int i = 0; i <= last; i++) {
            Step st = steps.get(i);
            String status = i == last ? (chosen.returned ? "RJCT" : "ACCC") : "ACSP";
            String kind = null;
            String reason = null;
            double hours = 0.0;
            if (!st.holds().isEmpty()) {
                Hold biggest = st.holds().getFirst();
                List<String> parts = new ArrayList<>();
                double total = 0;
                for (Hold hd : st.holds()) {
                    if (hd.hours() > biggest.hours()) {
                        biggest = hd;
                    }
                    total += hd.hours();
                    parts.add(switch (hd.kind()) {
                        case CALENDAR -> "Closed " + Dates.dayLabel(hd.day()) + ": " + hd.reason();
                        case CUTOFF -> "Held " + Dates.dayLabel(hd.day()) + ": " + hd.reason();
                        case BATCH -> "Held for Thursday netting batch";
                        case REPAIR -> "Information request: " + hd.reason();
                    });
                }
                kind = biggest.kind().dbValue();
                reason = String.join("; ", parts);
                hours = Dates.round(total, 2);
            }
            hopRows.add(new Object[] {pid, i, st.hop().bankId(), Dates.iso(st.arrive()), Dates.iso(st.start()),
                    Dates.iso(st.leave()), Dates.round(chosen.realFees[i], 2), status, kind, reason, hours});
        }
    }

    private static Object[] candidateRow(int pid, Candidate c, boolean chosen) {
        RouteEstimate e = c.estimate;
        return new Object[] {pid, e.templateId(), e.eligible() ? 1 : 0,
                e.ineligibleReasons().isEmpty() ? null : String.join("; ", e.ineligibleReasons()), e.hopCount(),
                e.intermediaries(), Dates.round(e.feeTotalUsd(), 2), e.fxBps(), Dates.round(e.estCostUsd(), 2),
                Dates.round(e.estHours(), 2), Dates.round(e.naiveHours(), 2), e.estNonbusinessDays(),
                e.calendarConflict() ? 1 : 0, Dates.round(e.cutoffSlackHours(), 2), e.maxRisk(), e.stpRateNominal(),
                Dates.round(c.realizedHours, 2), Dates.round(c.realizedCost, 2), c.realizedOutcome, c.utility,
                c.relevance, chosen ? 1 : 0};
    }

    /** Ops notes on payments that were returned, repaired, or held up for long enough to be remarked on. */
    private void annotate(int pid, SeedIncident s, Rng prng, Candidate chosen, String destCountry, String rcause,
                          double rhours, String retCause, double retExtra) {
        SimResult real = chosen.real;
        String note = null;
        String cause = null;
        Instant credited = chosen.returned ? null : real.end();
        Instant returnedAt = chosen.returned ? Dates.plusHours(real.end(), retExtra) : null;
        Instant endT = credited != null ? credited : returnedAt;
        if (chosen.returned) {
            cause = retCause;
            Step lastStep = real.steps().getLast();
            note = lastStep.hop().name() + " returned the payment: " + Reference.causeLabel(cause) + ". Funds back "
                    + fmtHours(real.hours() + retExtra) + " after release.";
        } else if (chosen.repaired) {
            cause = rcause;
            Step st = real.steps().stream().filter(x -> x.holds().stream().anyMatch(hd -> hd.kind() == HoldKind.REPAIR))
                    .findFirst().orElseThrow();
            note = st.hop().name() + " raised an information request (" + Reference.causeLabel(cause).toLowerCase(Locale.ROOT)
                    + "). Repaired after " + String.format(Locale.ROOT, "%.0f", rhours) + " h; credited after "
                    + fmtHours(real.hours()) + ".";
        } else {
            Step bestStep = null;
            Hold best = null;
            for (Step st : real.steps().subList(1, real.steps().size())) {
                for (Hold hd : st.holds()) {
                    boolean routine = hd.kind() == HoldKind.CALENDAR
                            && (hd.reason().equals("Saturday") || hd.reason().equals("Sunday")
                            || hd.reason().equals("Saturday · Gulf weekend"));   // closures the US shares are not news
                    if (!routine && (best == null || hd.hours() > best.hours())) {
                        best = hd;
                        bestStep = st;
                    }
                }
            }
            if (best != null) {
                double pNote = s != null ? 1.0 : switch (best.kind()) {
                    case CALENDAR -> 0.35;
                    case CUTOFF -> 0.12;
                    case BATCH -> 0.30;
                    default -> 0.0;
                };
                double minHours = switch (best.kind()) {
                    case CALENDAR -> 20;
                    case CUTOFF -> 12;
                    case BATCH -> 96;
                    default -> 1e9;
                };
                boolean bigEnough = best.hours() >= minHours;
                if ((s != null || bigEnough) && prng.random() < pNote) {
                    Hop hop = bestStep.hop();
                    switch (best.kind()) {
                        case CALENDAR -> {
                            boolean weekend = best.reason().equals("Saturday") || best.reason().equals("Sunday")
                                    || best.reason().contains("Gulf weekend");
                            cause = weekend ? "CAL_WEEKEND" : "CAL_HOLIDAY";
                            note = hop.name() + ", " + Reference.bank(hop.bankId()).city() + ", closed "
                                    + Dates.dayLabel(best.day()) + " (" + best.reason() + "). Credited "
                                    + fmtLocal(real.end(), destCountry) + " local, " + fmtHours(real.hours()) + " after release.";
                        }
                        case CUTOFF -> {
                            cause = "CUTOFF_MISSED";
                            note = "Arrived at " + hop.name() + " after its " + String.format(Locale.ROOT, "%02d", hop.cutoff())
                                    + ":00 cut-off; processed the next business day. Credited after " + fmtHours(real.hours()) + ".";
                        }
                        case BATCH -> {
                            cause = "BATCH_WAIT";
                            note = "Missed the Thursday netting batch at " + hop.name() + "; waited "
                                    + String.format(Locale.ROOT, "%.1f", best.hours() / 24) + " days for the next run.";
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        if (note != null) {
            Instant annotated = Dates.plusHours(endT, prng.uniform(2, 60));
            incidents.add(new Object[] {pid, cause, note, prng.choice(Reference.ANNOTATORS), Dates.iso(annotated)});
        }
    }

    /** A random version-4 UUID drawn from the payment's own generator. */
    private static String uuid(Rng prng) {
        long msb = (prng.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long lsb = (prng.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private static double sum(double[] a) {
        double t = 0;
        for (double v : a) {
            t += v;
        }
        return t;
    }
}
