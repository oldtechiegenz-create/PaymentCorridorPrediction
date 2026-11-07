package com.corridoriq.backend.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recreates the views from schema.sql in an existing history database, leaving the tables untouched.
 * Views hold no data, so this is safe to rerun. Use it after changing a view, or to bring a database loaded
 * from a bank's exports up to date without regenerating it. (This replaced the Python apply_views.py.)
 *
 * <pre>
 *   cd code/backend
 *   ./mvnw -q compile spring-boot:run \
 *       -Dspring-boot.run.main-class=com.corridoriq.backend.generator.ApplyViews
 *   # options: --db path/to.db  --schema path/to/schema.sql
 * </pre>
 */
public final class ApplyViews {

    private static final String DATA_DIR = "../../Data/corridoriq_history/";
    private static final Pattern VIEW_NAME =
            Pattern.compile("^\\s*CREATE\\s+VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);

    /** A view's name and its CREATE VIEW statement. */
    public record View(String name, String sql) {
    }

    private ApplyViews() {
    }

    /** Every view in the schema, in schema order, which respects their dependencies on each other. */
    public static List<View> views(String schemaSql) {
        List<View> out = new ArrayList<>();
        for (String statement : SqlScript.statements(schemaSql)) {
            Matcher m = VIEW_NAME.matcher(statement);
            if (m.find()) {
                out.add(new View(m.group(1), statement));
            }
        }
        return out;
    }

    /** Drops every view of the schema (dependents first) and creates them again, in one transaction. */
    public static List<String> apply(Path db, Path schema) throws IOException, SQLException {
        if (!Files.isRegularFile(db)) {
            throw new IOException("No database at " + db);
        }
        List<View> views = views(Files.readString(schema));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                for (int i = views.size() - 1; i >= 0; i--) {
                    st.execute("DROP VIEW IF EXISTS " + views.get(i).name());
                }
                for (View v : views) {
                    st.execute(v.sql());
                }
                // SQLite accepts a view that names a missing column and only fails when it is read,
                // so read each one now and keep nothing if any of them is broken.
                for (View v : views) {
                    st.executeQuery("SELECT * FROM " + v.name() + " LIMIT 0").close();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();   // a broken view leaves the database as it was
                throw e;
            }
        }
        return views.stream().map(View::name).toList();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = GenerateHistory.parse(args);
        Path db = Path.of(opt.getOrDefault("db", DATA_DIR + "corridoriq_history.db"));
        Path schema = Path.of(opt.getOrDefault("schema", DATA_DIR + "schema.sql"));
        List<String> names = apply(db, schema);
        System.out.printf("Recreated %d views in %s: %s%n", names.size(), db, String.join(", ", names));
    }
}
