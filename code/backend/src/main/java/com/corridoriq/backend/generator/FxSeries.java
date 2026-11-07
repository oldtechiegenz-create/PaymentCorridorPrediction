package com.corridoriq.backend.generator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.corridoriq.backend.engine.Dates;
import com.corridoriq.backend.engine.Reference;
import com.corridoriq.backend.engine.Reference.FxAnchor;

/** Daily FX mid rates: a random walk per currency that ends at the portal's rate on the last day. */
public final class FxSeries {

    /** The rates by "CCY|date", and the same as {@code fx_rate_daily} rows (currency, date, rate, source). */
    public record Result(Map<String, Double> byCurrencyAndDate, List<Object[]> rows) {
        public double rate(String currency, LocalDate day) {
            return byCurrencyAndDate.get(currency + "|" + day);
        }
    }

    private FxSeries() {
    }

    public static Result generate(Rng rng, LocalDate start, LocalDate end) {
        Map<String, Double> table = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        for (Map.Entry<String, FxAnchor> e : Reference.FX_ANCHOR.entrySet()) {
            String ccy = e.getKey();
            double anchor = e.getValue().mid();
            double vol = e.getValue().volatility();
            double sig = vol != 0 ? vol / Math.sqrt(365) : 0.00004;   // pegged currencies barely move
            double v = anchor;
            List<Double> vals = new ArrayList<>(days);
            for (int i = 0; i < days; i++) {
                vals.add(v);
                v = vol != 0 ? v * Math.exp(rng.gauss(0, sig)) : anchor * (1 + rng.gauss(0, sig));
            }
            Collections.reverse(vals);   // walk backwards from the anchor, so the series ends at the portal's rate
            int places = ccy.equals("JPY") || ccy.equals("INR") ? 2 : 4;
            for (int i = 0; i < days; i++) {
                LocalDate d = start.plusDays(i);
                double rate = Dates.round(vals.get(i), places);
                table.put(ccy + "|" + d, rate);
                rows.add(new Object[] {ccy, d.toString(), rate, "synthetic random walk"});
            }
        }
        return new Result(table, rows);
    }
}
