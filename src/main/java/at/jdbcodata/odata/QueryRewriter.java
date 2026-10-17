package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Query-string rewrites applied before Olingo parses the request.
 *
 * <p>{@code $compute} (OData 4.01) is unknown to the Olingo 5 parser. It is equivalent to a trailing
 * {@code compute(...)} transformation of {@code $apply}, which Olingo does parse, so {@code $compute=X} becomes
 * {@code $apply=compute(X)} — or {@code $apply=<existing>/compute(X)} when the request already has an {@code $apply}.
 * The value stays URL-encoded; only the option name and the wrapping change.
 *
 * <p>Expression options ($filter, $orderby, $apply, $expand, $compute, $select and aliases) additionally pass
 * through {@link ExpressionRewriter} for syntax the parser rejects (lambda colon with blank, matchesPattern,
 * {@code Nav/$count($filter=...)}); they are decoded for that and re-encoded only when something changed.
 */
final class QueryRewriter {

    private QueryRewriter() {
    }

    /** Custom options that carry {@code $compute}/{@code $select} of a single-entity request to the processor. */
    static final String SINGLE_COMPUTE = "jdbcodata-compute";
    static final String SINGLE_SELECT = "jdbcodata-select";

    static String rewrite(String query) {
        return rewrite(query, false);
    }

    /**
     * @param singleEntity the path addresses one entity: Olingo forbids {@code $apply} there, so {@code $compute}
     *                     (and {@code $select}, which may name computed properties) travel as custom options
     */
    static String rewrite(String query, boolean singleEntity) {
        if (query == null || query.isEmpty()) return query;
        if (singleEntity) query = moveSingleEntityCompute(query);
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            if (!EXPRESSION_OPTIONS.contains(name) && !name.startsWith("@")) continue;
            String raw = value(parts.get(i));
            String decoded = decode(raw);
            if (decoded == null) continue;
            String rewritten = "$expand".equals(name) ? ExpressionRewriter.rewriteExpand(decoded) : ExpressionRewriter.rewrite(decoded);
            if (!rewritten.equals(decoded)) {
                parts.set(i, parts.get(i).substring(0, parts.get(i).indexOf('=') + 1) + encode(rewritten));
                changed = true;
            }
        }
        if (changed) query = String.join("&", parts);
        String compute = null;
        int applyIndex = -1;
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            if ("$compute".equals(name)) {
                compute = value(parts.get(i));
                parts.remove(i--);
            } else if ("$apply".equals(name)) {
                applyIndex = i;
            }
        }
        if (compute == null) return query;
        String transformation = "compute(" + compute + ")";
        if (applyIndex >= 0) {
            String existing = parts.get(applyIndex);
            parts.set(applyIndex, existing + "/" + transformation);
        } else {
            parts.add("$apply=" + transformation);
        }
        return String.join("&", parts);
    }

    private static String moveSingleEntityCompute(String query) {
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
        if (parts.stream().noneMatch(p -> "$compute".equals(optionName(p)))) return query;
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            if ("$compute".equals(name)) parts.set(i, SINGLE_COMPUTE + "=" + value(parts.get(i)));
            if ("$select".equals(name)) parts.set(i, SINGLE_SELECT + "=" + value(parts.get(i)));
        }
        return String.join("&", parts);
    }

    private static final java.util.Set<String> EXPRESSION_OPTIONS =
            java.util.Set.of("$filter", "$orderby", "$apply", "$expand", "$compute", "$select");

    /** Percent-decoding as Olingo does it ({@code +} stays a plus); {@code null} on malformed input. */
    static String decode(String raw) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) return null;
                try {
                    bytes.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                } catch (NumberFormatException e) {
                    return null;
                }
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
            }
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Encodes everything except unreserved characters and the delimiters OData expressions use literally. */
    static String encode(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~!$'()*,;:@/".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", (int) c));
            }
        }
        return out.toString();
    }

    private static String optionName(String part) {
        int eq = part.indexOf('=');
        String name = eq < 0 ? part : part.substring(0, eq);
        return name.toLowerCase(Locale.ROOT).replace("%24", "$");
    }

    private static String value(String part) {
        int eq = part.indexOf('=');
        return eq < 0 ? "" : part.substring(eq + 1);
    }
}
