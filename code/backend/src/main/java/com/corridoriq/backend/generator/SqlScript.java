package com.corridoriq.backend.generator;

import java.util.ArrayList;
import java.util.List;

/** Splits a SQL script into statements, so a script such as schema.sql can be run through plain JDBC. */
public final class SqlScript {

    private SqlScript() {
    }

    /**
     * Statements in order, without their semicolons. Semicolons and quotes inside {@code -- line comments}
     * or inside single-quoted strings do not end or open a statement. Comments are dropped.
     */
    public static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        int n = script.length();
        for (int i = 0; i < n; i++) {
            char c = script.charAt(i);
            if (inString) {
                cur.append(c);
                if (c == '\'') {
                    if (i + 1 < n && script.charAt(i + 1) == '\'') {   // '' is an escaped quote
                        cur.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
                cur.append(c);
            } else if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                while (i < n && script.charAt(i) != '\n') {   // skip to the end of the line
                    i++;
                }
                cur.append('\n');
            } else if (c == ';') {
                add(out, cur);
            } else {
                cur.append(c);
            }
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder cur) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) {
            out.add(s);
        }
        cur.setLength(0);
    }
}
