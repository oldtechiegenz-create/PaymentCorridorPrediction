package com.corridoriq.backend.generator;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import com.corridoriq.backend.engine.BankCalendar;

/**
 * Command line: generates a synthetic payment history into a new SQLite database.
 *
 * <pre>
 *   cd code/backend
 *   ./mvnw -q compile spring-boot:run \
 *       -Dspring-boot.run.main-class=com.corridoriq.backend.generator.GenerateHistory \
 *       -Dspring-boot.run.arguments="--payments 40000 --seed 42 --out ../../Data/corridoriq_history/new.db"
 * </pre>
 *
 * Options (each as {@code --name value} or {@code --name=value}): {@code --payments} (default 40000),
 * {@code --start} (2024-04-01), {@code --end} (2026-09-30), {@code --seed} (42), {@code --out}, {@code --schema},
 * and {@code --force} to replace an existing output file. It does not start the web application.
 */
public final class GenerateHistory {

    private static final String DATA_DIR = "../../Data/corridoriq_history/";

    private GenerateHistory() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        int payments = Integer.parseInt(opt.getOrDefault("payments", "40000"));
        LocalDate start = LocalDate.parse(opt.getOrDefault("start", "2024-04-01"));
        LocalDate end = LocalDate.parse(opt.getOrDefault("end", "2026-09-30"));
        long seed = Long.parseLong(opt.getOrDefault("seed", "42"));
        Path out = Path.of(opt.getOrDefault("out", DATA_DIR + "corridoriq_history.db"));
        Path schema = Path.of(opt.getOrDefault("schema", DATA_DIR + "schema.sql"));
        boolean force = opt.containsKey("force");

        long t = System.nanoTime();
        System.out.printf("Generating %,d payments, %s to %s, seed %d%n", payments, start, end, seed);
        HistoryGenerator.Output data = HistoryGenerator.generate(new HistoryGenerator.Config(payments, start, end, seed),
                BankCalendar.bundledHolidays(), n -> System.out.printf("  %,d payments  (%d s)%n", n, (System.nanoTime() - t) / 1_000_000_000L));
        HistoryWriter.write(out, schema, data, force);
        System.out.printf("Wrote %s: %,d payments, %,d hops, %,d candidate routes, %,d incidents in %d s%n", out,
                data.payments().size(), data.hops().size(), data.candidates().size(), data.incidents().size(),
                (System.nanoTime() - t) / 1_000_000_000L);
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument " + args[i]);
            }
            String a = args[i].substring(2);
            int eq = a.indexOf('=');
            if (eq >= 0) {
                opt.put(a.substring(0, eq), a.substring(eq + 1));
            } else if (a.equals("force")) {
                opt.put("force", "true");
            } else if (i + 1 < args.length) {
                opt.put(a, args[++i]);
            } else {
                throw new IllegalArgumentException("Missing value for --" + a);
            }
        }
        return opt;
    }
}
