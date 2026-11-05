package com.corridoriq.backend.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL in Data/corridoriq_history/historical_cases.sql, split on its {@code -- name: <name>} lines.
 * That file is the single source of the history SQL.
 * Its {@code :param} placeholders are bound by Spring's NamedParameterJdbcTemplate.
 */
public final class NamedQueries {

    private static final Pattern NAME = Pattern.compile("^--\\s*name:\\s*(\\w+)\\s*$", Pattern.MULTILINE);
    /** Views and tables a query reads: every name after FROM or JOIN, outside comments. */
    private static final Pattern SOURCE = Pattern.compile("\\b(?:FROM|JOIN)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    private final Map<String, String> queries;
    private final Map<String, List<String>> sources = new LinkedHashMap<>();

    private NamedQueries(Map<String, String> queries) {
        this.queries = Collections.unmodifiableMap(queries);
        queries.forEach((name, sql) -> {
            Set<String> found = new LinkedHashSet<>();
            Matcher m = SOURCE.matcher(LINE_COMMENT.matcher(sql).replaceAll(""));
            while (m.find()) {
                found.add(m.group(1));
            }
            sources.put(name, List.copyOf(found));
        });
    }

    public static NamedQueries load(Path sqlFile) {
        try {
            return parse(Files.readString(sqlFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read named queries from " + sqlFile, e);
        }
    }

    public static NamedQueries parse(String text) {
        Matcher m = NAME.matcher(text);
        List<int[]> marks = new ArrayList<>();   // {start, end} of each '-- name:' line
        List<String> names = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[] {m.start(), m.end()});
            names.add(m.group(1));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < marks.size(); i++) {
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            String body = text.substring(marks.get(i)[1], end).strip();
            while (body.endsWith(";")) {
                body = body.substring(0, body.length() - 1);
            }
            out.put(names.get(i), body);
        }
        return new NamedQueries(out);
    }

    public String get(String name) {
        String sql = queries.get(name);
        if (sql == null) {
            throw new IllegalStateException("No query named " + name + " in historical_cases.sql");
        }
        return sql;
    }

    /** The views and tables the query reads, in order of appearance, e.g. [v_case, v_history_window]. */
    public List<String> sources(String name) {
        return sources.getOrDefault(name, List.of());
    }

    public Set<String> names() {
        return queries.keySet();
    }
}
