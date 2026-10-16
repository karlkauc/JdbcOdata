package at.jdbcodata.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites {@code :name} placeholders to JDBC {@code ?} markers. String literals, quoted identifiers, comments and
 * PostgreSQL {@code ::type} casts are left untouched.
 */
public record NamedParameterSql(String jdbcSql, List<String> parameterNames) {

    public static NamedParameterSql parse(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i, c);
                out.append(sql, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(sql, i, end);
                i = end;
            } else if (c == ':') {
                if (i + 1 < n && sql.charAt(i + 1) == ':') {
                    out.append("::");
                    i += 2;
                } else if (i + 1 < n && isNameStart(sql.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < n && isNamePart(sql.charAt(j))) j++;
                    names.add(sql.substring(i + 1, j));
                    out.append('?');
                    i = j;
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return new NamedParameterSql(out.toString(), List.copyOf(names));
    }

    public Set<String> distinctNames() {
        return new LinkedHashSet<>(parameterNames);
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int j = start + 1;
        int n = sql.length();
        while (j < n) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < n && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
