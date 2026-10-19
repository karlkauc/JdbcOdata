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
        return rewrite(query, singleEntity, null);
    }

    /** @param target type of the addressed resource (for case result types), may be null */
    static String rewrite(String query, boolean singleEntity, org.apache.olingo.commons.api.edm.EdmStructuredType target) {
        if (query == null || query.isEmpty()) return query;
        query = selectOptions(query);
        query = ownSearches(query);
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
        boolean changed = false;
        ExpressionRewriter expressions = new ExpressionRewriter(target);
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            if (!EXPRESSION_OPTIONS.contains(name) && !name.startsWith("@")) continue;
            String raw = value(parts.get(i));
            String decoded = decode(raw);
            if (decoded == null) continue;
            String rewritten = "$expand".equals(name) ? expressions.expandOption(decoded) : expressions.expression(decoded);
            if (!rewritten.equals(decoded)) {
                parts.set(i, parts.get(i).substring(0, parts.get(i).indexOf('=') + 1) + encode(rewritten));
                changed = true;
            }
        }
        for (int n = 0; n < expressions.cases().size(); n++) {
            parts.add(ExpressionRewriter.CASE_OPTION + (n + 1) + "=" + encode(expressions.cases().get(n)));
            changed = true;
        }
        if (changed) query = String.join("&", parts);
        if (singleEntity) return moveSingleEntityCompute(query);
        return moveExtendedApply(mergeCompute(query, parts));
    }

    private static String mergeCompute(String query, List<String> parts) {
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

    // --- $select with options (4.01) ---------------------------------------------------------------------------

    /** Custom option prefix for {@code $select} items with options: value {@code Nav/…/Property(options)}. */
    static final String SELECT_OPTION = "jdbcodata-selectopt";
    private static final java.util.regex.Pattern SELECT_PATH = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * {@code $select=Tags($top=2),Positionen($filter=Betrag gt 100;$select=Artikel,Betrag)}: Olingo 5 does not parse
     * options of select items. Each such item becomes the plain property ({@code Tags}, or the sub-property paths
     * {@code Positionen/Artikel,Positionen/Betrag} for a nested $select) and a custom option {@link #SELECT_OPTION}N
     * with its options, prefixed with the navigation path when it sits in an $expand ({@code Umsaetze/Tags(…)}).
     */
    static String selectOptions(String query) {
        if (!query.toLowerCase(Locale.ROOT).contains("select")) return query;
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
        List<String> found = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            if (!"$select".equals(name) && !"$expand".equals(name)) continue;
            String decoded = decode(value(parts.get(i)));
            if (decoded == null || decoded.indexOf('(') < 0) continue;
            String rewritten = "$select".equals(name) ? selectItems(decoded, "", found) : expandItems(decoded, "", found);
            if (!rewritten.equals(decoded)) parts.set(i, parts.get(i).substring(0, parts.get(i).indexOf('=') + 1) + encode(rewritten));
        }
        if (found.isEmpty()) return query;
        for (int n = 0; n < found.size(); n++) parts.add(SELECT_OPTION + (n + 1) + "=" + encode(found.get(n)));
        return String.join("&", parts);
    }

    private static String selectItems(String select, String prefix, List<String> found) {
        List<String> out = new ArrayList<>();
        for (String raw : split(select, ',')) {
            String item = raw.strip();
            int open = indexOutsideQuotes(item, "(", 0);
            String head = open < 0 ? item : item.substring(0, open).strip();
            if (open < 0 || !item.endsWith(")") || closingParen(item, open) != item.length() - 1
                    || !SELECT_PATH.matcher(head).matches()) {
                out.add(raw); // plain path, *, or a bound function with parameters
                continue;
            }
            String options = item.substring(open + 1, item.length() - 1);
            found.add(prefix + head + "(" + options + ")");
            List<String> nested = null;
            for (String o : split(options, ';')) {
                String t = o.strip();
                if (t.startsWith("$select=")) nested = split(t.substring("$select=".length()), ',');
            }
            if (nested == null || nested.stream().anyMatch(n -> n.strip().equals("*"))) {
                out.add(head);
            } else {
                for (String n : nested) {
                    String sub = n.strip();
                    int p = indexOutsideQuotes(sub, "(", 0);
                    out.add(head + "/" + (p < 0 ? sub : sub.substring(0, p).strip()));
                }
            }
        }
        return String.join(",", out);
    }

    /** Closing parenthesis of the one at {@code open}, outside string literals and phrases; -1 if none. */
    private static int closingParen(String s, int open) {
        int depth = 0;
        boolean literal = false;
        boolean phrase = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (literal) {
                if (c == '\'') literal = false;
                continue;
            }
            if (phrase) {
                if (c == '\\') i++;
                else if (c == '"') phrase = false;
                continue;
            }
            if (c == '\'') literal = true;
            else if (c == '"') phrase = true;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static String expandItems(String expand, String prefix, List<String> found) {
        List<String> out = new ArrayList<>();
        for (String raw : split(expand, ',')) {
            String item = raw.strip();
            int open = indexOutsideQuotes(item, "(", 0);
            if (open < 0 || !item.endsWith(")") || closingParen(item, open) != item.length() - 1) {
                out.add(raw);
                continue;
            }
            String head = item.substring(0, open).strip();
            String nav = prefix + head + "/";
            List<String> options = new ArrayList<>();
            for (String o : split(item.substring(open + 1, item.length() - 1), ';')) {
                String t = o.strip();
                if (t.startsWith("$select=")) options.add("$select=" + selectItems(t.substring("$select=".length()), nav, found));
                else if (t.startsWith("$expand=")) options.add("$expand=" + expandItems(t.substring("$expand=".length()), nav, found));
                else options.add(o);
            }
            out.add(head + "(" + String.join(";", options) + ")");
        }
        return String.join(",", out);
    }

    // --- $search beyond Olingo's parser -----------------------------------------------------------------------

    /** Alias placeholder for a search Olingo cannot parse; the text travels as custom option {@link #SEARCH_OPTION}N. */
    static final String SEARCH_ALIAS = "@jdbcodata_search";
    static final String SEARCH_OPTION = "jdbcodata-search";

    /**
     * Searches {@link SearchSyntax} has to parse become a filter condition {@code cast(@jdbcodata_searchN,Edm.Boolean)}
     * (a search only restricts the rows, like a filter): {@code $search} is and-ed into {@code $filter},
     * {@code search(…)} in $apply becomes {@code filter(…)}, {@code $search} inside $expand options joins that
     * level's {@code $filter}.
     */
    private static String ownSearches(String query) {
        if (!query.contains("search") && !query.contains("Search")) return query;
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
        List<String> texts = new ArrayList<>();
        String searchToFilter = null;
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            String decoded = decode(value(parts.get(i)));
            if (decoded == null) continue;
            if ("$search".equals(name) && SearchSyntax.needsOwnParser(decoded, false)) {
                texts.add(decoded);
                searchToFilter = placeholder(texts.size());
                parts.remove(i--);
            } else if ("$apply".equals(name)) {
                String rewritten = applySearches(decoded, texts);
                if (!rewritten.equals(decoded)) parts.set(i, "$apply=" + encode(rewritten));
            } else if ("$expand".equals(name)) {
                String rewritten = expandSearches(decoded, texts);
                if (!rewritten.equals(decoded)) parts.set(i, "$expand=" + encode(rewritten));
            }
        }
        if (texts.isEmpty()) return query;
        if (searchToFilter != null) {
            int filter = -1;
            for (int i = 0; i < parts.size(); i++) if ("$filter".equals(optionName(parts.get(i)))) filter = i;
            if (filter < 0) {
                parts.add("$filter=" + encode(searchToFilter));
            } else {
                parts.set(filter, "$filter=" + encode("(" + decode(value(parts.get(filter))) + ") and " + searchToFilter));
            }
        }
        for (int n = 0; n < texts.size(); n++) parts.add(SEARCH_OPTION + (n + 1) + "=" + encode(texts.get(n)));
        return String.join("&", parts);
    }

    private static String placeholder(int n) {
        return "cast(" + SEARCH_ALIAS + n + ",Edm.Boolean)";
    }

    private static String applySearches(String apply, List<String> texts) {
        List<int[]> found = SearchSyntax.applySearches(apply);
        StringBuilder out = new StringBuilder(apply);
        for (int k = found.size() - 1; k >= 0; k--) {
            int[] f = found.get(k);
            String inner = apply.substring(f[1] + 1, f[2]);
            if (!SearchSyntax.needsOwnParser(inner, true)) continue;
            texts.add(inner);
            out.replace(f[0], f[2] + 1, "filter(" + placeholder(texts.size()) + ")");
        }
        return out.toString();
    }

    /** {@code Nav($search=…;$filter=…)}: the search joins the $filter of its option block. */
    private static String expandSearches(String expand, List<String> texts) {
        String s = expand;
        int from = 0;
        while (true) {
            int at = indexOutsideQuotes(s, "$search=", from);
            if (at < 0) return s;
            int open = enclosingParen(s, at);
            int close = open < 0 ? -1 : SearchSyntax.closing(s, open);
            if (close < 0) return s;
            List<String> options = splitOptions(s.substring(open + 1, close));
            int searchIndex = -1;
            int filterIndex = -1;
            for (int i = 0; i < options.size(); i++) {
                String o = options.get(i).strip();
                if (o.startsWith("$search=") && searchIndex < 0) searchIndex = i;
                if (o.startsWith("$filter=")) filterIndex = i;
            }
            String text = searchIndex < 0 ? null : options.get(searchIndex).strip().substring("$search=".length());
            if (text == null || !SearchSyntax.needsOwnParser(text, false)) {
                from = at + 1;
                continue;
            }
            texts.add(text);
            String condition = placeholder(texts.size());
            if (filterIndex >= 0) {
                String f = options.get(filterIndex).strip().substring("$filter=".length());
                options.set(filterIndex, "$filter=(" + f + ") and " + condition);
                options.remove(searchIndex);
            } else {
                options.set(searchIndex, "$filter=" + condition);
            }
            s = s.substring(0, open + 1) + String.join(";", options) + s.substring(close);
            from = open + 1;
        }
    }

    private static int indexOutsideQuotes(String s, String needle, int from) {
        boolean literal = false;
        boolean phrase = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (literal) {
                if (c == '\'') literal = false;
                continue;
            }
            if (phrase) {
                if (c == '\\') i++;
                else if (c == '"') phrase = false;
                continue;
            }
            if (c == '\'') literal = true;
            else if (c == '"') phrase = true;
            else if (i >= from && s.startsWith(needle, i)) return i;
        }
        return -1;
    }

    /** The '(' that opens the block containing position {@code at}, or -1. */
    private static int enclosingParen(String s, int at) {
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        boolean literal = false;
        boolean phrase = false;
        for (int i = 0; i < at; i++) {
            char c = s.charAt(i);
            if (literal) {
                if (c == '\'') literal = false;
                continue;
            }
            if (phrase) {
                if (c == '\\') i++;
                else if (c == '"') phrase = false;
                continue;
            }
            if (c == '\'') literal = true;
            else if (c == '"') phrase = true;
            else if (c == '(') stack.push(i);
            else if (c == ')' && !stack.isEmpty()) stack.pop();
        }
        return stack.isEmpty() ? -1 : stack.peek();
    }

    /** Options of an expand block, split at {@code ;} on level 0 outside literals and phrases. */
    private static List<String> splitOptions(String block) {
        return split(block, ';');
    }

    /** {@code block} split at {@code separator} on level 0 outside literals and phrases. */
    static List<String> split(String block, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean literal = false;
        boolean phrase = false;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (literal) {
                if (c == '\'') literal = false;
                continue;
            }
            if (phrase) {
                if (c == '\\') i++;
                else if (c == '"') phrase = false;
                continue;
            }
            if (c == '\'') literal = true;
            else if (c == '"') phrase = true;
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == separator && depth == 0) {
                out.add(block.substring(start, i));
                start = i + 1;
            }
        }
        out.add(block.substring(start));
        return out;
    }

    /** Custom option prefix for options evaluated by {@link ExtendedApply} instead of Olingo. */
    static final String EXTENDED_PREFIX = "jdbcodata-";
    /** $apply transformations Olingo 5 cannot parse; their presence routes $apply and the type-bound options. */
    private static final java.util.regex.Pattern EXTENDED_TRANSFORMATION =
            java.util.regex.Pattern.compile("(?<![A-Za-z0-9_/.$])(join|outerjoin|nest|ancestors|descendants|traverse)\\s*\\(");
    private static final java.util.Set<String> TYPE_BOUND_OPTIONS = java.util.Set.of("$apply", "$filter", "$orderby", "$select", "$expand");

    /**
     * {@code $apply} with join, outerjoin, nest, ancestors, descendants or traverse: $apply, and $filter, $orderby,
     * $select and $expand (which may refer to its aliases), become custom options {@code jdbcodata-apply} etc.
     */
    private static String moveExtendedApply(String query) {
        if (query == null) return null;
        List<String> parts = new ArrayList<>(List.of(query.split("&", -1)));
        boolean extended = false;
        for (String p : parts) {
            if (!"$apply".equals(optionName(p))) continue;
            String decoded = decode(value(p));
            if (decoded != null && EXTENDED_TRANSFORMATION.matcher(withoutStrings(decoded)).find()) extended = true;
        }
        if (!extended) return query;
        for (int i = 0; i < parts.size(); i++) {
            String name = optionName(parts.get(i));
            if (TYPE_BOUND_OPTIONS.contains(name)) {
                parts.set(i, EXTENDED_PREFIX + name.substring(1) + "=" + value(parts.get(i)));
            }
        }
        return String.join("&", parts);
    }

    private static String withoutStrings(String s) {
        return s.replaceAll("'(?:[^']|'')*'", "''");
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
