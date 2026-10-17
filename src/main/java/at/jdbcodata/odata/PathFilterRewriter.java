package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code Kunden/$filter(Region eq 'Nord')/$count} (OData 4.01 filter segment): Olingo 5 has no such segment, so
 * filter segments at the end of a collection path (followed only by further filter segments, {@code $count},
 * {@code $ref} or nothing) are removed and combined with the {@code $filter} option by {@code and}.
 */
final class PathFilterRewriter {

    /** The path without filter segments and the filter expressions found (decoded). */
    record Result(String path, List<String> filters) {
    }

    private PathFilterRewriter() {
    }

    static Result extract(String rawPath) {
        if (rawPath == null || !(rawPath.contains("$filter(") || rawPath.toLowerCase(Locale.ROOT).contains("%24filter("))) {
            return new Result(rawPath, List.of());
        }
        String path = QueryRewriter.decode(rawPath);
        if (path == null) return new Result(rawPath, List.of());
        List<String> filters = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            if (path.startsWith("/$filter(", i)) {
                int open = i + "/$filter".length();
                int close = closing(path, open);
                if (close < 0) return new Result(rawPath, List.of());
                String rest = path.substring(close + 1);
                if (!(rest.isEmpty() || rest.startsWith("/$filter(") || rest.equals("/$count") || rest.equals("/$ref"))) {
                    return new Result(rawPath, List.of()); // not at the end of the collection: let Olingo report it
                }
                filters.add(path.substring(open + 1, close));
                i = close + 1;
                continue;
            }
            char c = path.charAt(i);
            if (c == '\'') {
                int end = stringEnd(path, i);
                out.append(path, i, end);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return new Result(QueryRewriter.encode(out.toString()), filters);
    }

    /** The query string with the path filters and an existing $filter combined by {@code and}. */
    static String mergeIntoQuery(String query, List<String> filters) {
        if (filters.isEmpty()) return query;
        List<String> parts = new ArrayList<>();
        String existing = null;
        if (query != null && !query.isEmpty()) {
            for (String p : query.split("&", -1)) {
                int eq = p.indexOf('=');
                String name = (eq < 0 ? p : p.substring(0, eq)).toLowerCase(Locale.ROOT).replace("%24", "$");
                if ("$filter".equals(name)) existing = QueryRewriter.decode(eq < 0 ? "" : p.substring(eq + 1));
                else parts.add(p);
            }
        }
        List<String> all = new ArrayList<>(filters);
        if (existing != null && !existing.isBlank()) all.add(existing);
        String combined = all.size() == 1 ? all.get(0) : "(" + String.join(") and (", all) + ")";
        parts.add("$filter=" + QueryRewriter.encode(combined));
        return String.join("&", parts);
    }

    private static int closing(String s, int open) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                i = stringEnd(s, i);
                continue;
            }
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
            i++;
        }
        return -1;
    }

    private static int stringEnd(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            if (s.charAt(i) == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return s.length();
    }
}
