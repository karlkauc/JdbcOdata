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
 */
final class QueryRewriter {

    private QueryRewriter() {
    }

    static String rewrite(String query) {
        if (query == null || query.isEmpty()) return query;
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
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
