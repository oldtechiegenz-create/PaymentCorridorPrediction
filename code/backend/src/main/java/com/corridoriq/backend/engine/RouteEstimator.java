package com.corridoriq.backend.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.corridoriq.backend.engine.Reference.Corridor;
import com.corridoriq.backend.engine.Reference.Template;
import com.corridoriq.backend.engine.RouteSimulator.Hop;

/**
 * The simulator's estimate for every route of a corridor, as known at release (nominal processing times, no
 * hidden bank speed). One version serves both purposes: the generator writes these numbers into the history
 * database for training, and {@link com.corridoriq.backend.prediction.RouteRanker} computes them again when it
 * ranks a payment, so the models see the same kind of input in training and in use. (Python used to hold two
 * copies, in generate_history.py and features.py.)
 */
public final class RouteEstimator {

    private RouteEstimator() {
    }

    /**
     * Values are unrounded; {@link #rounded()} gives the 2-decimal values stored in route_candidate.
     *
     * @param hops           banks on the route, with the beneficiary bank filled in
     * @param fees           quoted fee at each hop (the first hop is capped for FI clients)
     * @param fxCostUsd      quoted FX margin in USD
     * @param ineligibleReasons empty when the route can be used for this payment
     */
    public record RouteEstimate(String templateId, boolean eligible, List<String> ineligibleReasons, List<Hop> hops,
                                double[] fees, int hopCount, int intermediaries, double feeTotalUsd, double fxBps,
                                double fxCostUsd, double estCostUsd, double estHours, double naiveHours,
                                int estNonbusinessDays, boolean calendarConflict, double cutoffSlackHours,
                                int maxRisk, double stpRateNominal) {

        /** The same estimate with the numbers rounded to 2 decimals, as the database stores them. */
        public RouteEstimate rounded() {
            return new RouteEstimate(templateId, eligible, ineligibleReasons, hops, fees, hopCount, intermediaries,
                    Dates.round(feeTotalUsd, 2), fxBps, Dates.round(fxCostUsd, 2), Dates.round(estCostUsd, 2),
                    Dates.round(estHours, 2), Dates.round(naiveHours, 2), estNonbusinessDays, calendarConflict,
                    Dates.round(cutoffSlackHours, 2), maxRisk, stpRateNominal);
        }
    }

    /** Estimates every route the corridor offers, in the corridor's template order. */
    public static List<RouteEstimate> estimate(String corridorId, String beneBankId, String clientType,
                                               double amountUsd, Instant releasedAt, BankCalendar cal) {
        LocalDate valueDate = releasedAt.atZone(Reference.zone("US")).toLocalDate();
        Corridor corridor = Reference.corridor(corridorId);
        List<RouteEstimate> out = new ArrayList<>();
        for (String tid : corridor.templates()) {
            Template tpl = Reference.template(tid);
            List<Hop> hops = RouteSimulator.resolveHops(tid, corridorId, beneBankId);
            double[] fees = tpl.fees().clone();
            if (clientType.equals("FI")) {
                fees[0] = Math.min(fees[0], Reference.FI_FIRST_HOP_FEE_CAP);
            }
            double bps = amountUsd >= Reference.LARGE_TICKET_USD ? tpl.fxBpsLarge() : tpl.fxBpsStandard();
            double fxCost = amountUsd * bps / 1e4;
            double feeTotal = 0;
            for (double f : fees) {
                feeTotal += f;
            }
            double[] nominal = tpl.procHours();
            RouteSimulator.SimResult est = RouteSimulator.simulate(hops, tid, releasedAt, cal, RouteSimulator.fixed(nominal));

            List<String> reasons = new ArrayList<>();
            if (tpl.cap() != null && amountUsd > tpl.cap()) {
                reasons.add(String.format(Locale.US, "Above the $%,.0f per-transfer cap", tpl.cap()));
            }
            if (tpl.retailOnly() && clientType.equals("FI")) {
                reasons.add("Available to retail clients only");
            }
            int maxRisk = corridor.countryRisk();
            for (Hop h : hops) {
                maxRisk = Math.max(maxRisk, h.risk());
            }
            Hop first = hops.getFirst();
            Instant cutoff = Dates.at(valueDate, LocalTime.of(first.cutoff(), 0), Reference.zone(first.country()));
            out.add(new RouteEstimate(tid, reasons.isEmpty(), List.copyOf(reasons), List.copyOf(hops), fees, hops.size(),
                    hops.size() - 2, feeTotal, bps, fxCost, feeTotal + fxCost, est.hours(),
                    RouteSimulator.naiveHours(releasedAt, cal, nominal), est.calendarDays(),
                    RouteSimulator.calendarConflict(hops, valueDate, cal), Dates.hoursBetween(releasedAt, cutoff),
                    maxRisk, tpl.stpRate()));
        }
        return out;
    }
}
