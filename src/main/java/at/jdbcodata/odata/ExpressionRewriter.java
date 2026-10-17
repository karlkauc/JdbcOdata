package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text rewrites of OData expressions (decoded option values) for syntax the Olingo 5 parser rejects. String
 * literals are never touched.
 *
 * <ul>
 *   <li>Whitespace after the colon of a lambda ({@code any(u: u/Betrag gt 1)}) is removed.</li>
 *   <li>{@code matchesPattern(A,P)} becomes {@code contains(A,concat('<marker>',P))}; the evaluator recognises the
 *       marker and applies the regular expression ({@link #PATTERN_MARKER}).</li>
 *   <li>{@code Nav/$count($filter=E)} becomes {@code cast(Nav/any(jdbcodata_cN:E'),Edm.Int64)}, where {@code E'}
 *       has its member paths prefixed with the lambda variable; a lambda variable starting with
 *       {@link #COUNT_VARIABLE} yields the number of matching elements instead of a boolean.</li>
 * </ul>
 */
final class ExpressionRewriter {

    static final String PATTERN_MARKER = "~jdbcodata:matchesPattern~";
    static final String COUNT_VARIABLE = "jdbcodata_c";

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "eq", "ne", "gt", "ge", "lt", "le", "add",
            "sub", "mul", "div", "divby", "mod", "has", "in", "true", "false", "null", "inf", "nan", "asc", "desc");

    /** Alias placeholder for {@code case(...)}; its branches travel as custom option {@link #CASE_OPTION}N. */
    static final String CASE_ALIAS = "@jdbcodata_case";
    static final String CASE_OPTION = "jdbcodata-case";

    private int counter;
    private int caseCounter;
    /** Bodies of the {@code case(...)} expressions found, in placeholder order (index 0 = placeholder 1). */
    private final List<String> cases = new ArrayList<>();

    /** Type the expressions refer to (resolves simple property paths for case result types), may be null. */
    private final org.apache.olingo.commons.api.edm.EdmStructuredType context;

    ExpressionRewriter() {
        this(null);
    }

    ExpressionRewriter(org.apache.olingo.commons.api.edm.EdmStructuredType context) {
        this.context = context;
    }

    List<String> cases() {
        return cases;
    }

    static String rewrite(String expression) {
        return new ExpressionRewriter().expression(expression);
    }

    /** All rewrites for an expression option ($filter, $orderby, $apply, $compute, aliases). */
    String expression(String expression) {
        if (expression == null || expression.isEmpty()) return expression;
        String s = lambdaColons(expression);
        s = matchesPattern(s);
        s = caseExpressions(s);
        s = countWithOptions(s);
        return s;
    }

    // --- case ------------------------------------------------------------------------------------------------

    /**
     * {@code case(c1:v1,c2:v2)} (OData 4.01) becomes the undefined alias {@code @jdbcodata_caseN}: Olingo gives an
     * undefined alias no type, so it fits anywhere; the evaluator parses the branches itself.
     */
    private String caseExpressions(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = stringEnd(s, i);
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (startsWithWord(s, i, "case") && nextNonSpace(s, i + 4) == '(') {
                int open = s.indexOf('(', i);
                int close = closing(s, open);
                String body = caseExpressions(s.substring(open + 1, close));
                cases.add(body);
                // Olingo needs a type for computed aliases and comparisons: cast to the type of the branch values.
                out.append("cast(").append(CASE_ALIAS).append(++caseCounter).append(',').append(caseType(body)).append(')');
                i = close + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** EDM type of the case result: from the first branch value whose type is evident. */
    private String caseType(String body) {
        List<String[]> branches;
        try {
            branches = caseBranches(body);
        } catch (IllegalArgumentException e) {
            return "Edm.String";
        }
        for (String[] b : branches) {
            String t = valueType(b[1]);
            if (t != null) return t;
        }
        return "Edm.String";
    }

    private String valueType(String v) {
        String t = v.strip();
        if (t.isEmpty() || t.equals("null")) return null;
        if (t.startsWith("'")) return "Edm.String";
        if (t.equals("true") || t.equals("false")) return "Edm.Boolean";
        if (t.matches("-?\\d+")) return "Edm.Int64";
        if (t.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) return "Edm.Decimal";
        if (t.matches("\\d{4}-\\d{2}-\\d{2}")) return "Edm.Date";
        if (t.matches("\\d{4}-\\d{2}-\\d{2}T.*")) return "Edm.DateTimeOffset";
        if (t.matches("\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?")) return "Edm.TimeOfDay";
        if (t.startsWith(CASE_ALIAS) || t.startsWith("cast(" + CASE_ALIAS)) {
            int comma = t.lastIndexOf(',');
            return comma > 0 ? t.substring(comma + 1, t.length() - 1) : null;
        }
        if (context != null && t.matches("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*")) {
            org.apache.olingo.commons.api.edm.EdmStructuredType type = context;
            String[] segments = t.split("/");
            for (int i = 0; i < segments.length; i++) {
                if (i < segments.length - 1) {
                    org.apache.olingo.commons.api.edm.EdmNavigationProperty nav = type.getNavigationProperty(segments[i]);
                    if (nav == null || nav.isCollection()) return null;
                    type = nav.getType();
                } else {
                    org.apache.olingo.commons.api.edm.EdmProperty p = type.getStructuralProperty(segments[i]);
                    if (p != null && p.getType() != null) return p.getType().getFullQualifiedName().getFullQualifiedNameAsString();
                }
            }
        }
        if (t.matches("(?s).*\\b(add|sub|mul|div|divby|mod)\\b.*")) return "Edm.Decimal";
        if (t.matches("(?s)^(tolower|toupper|trim|concat|substring)\\(.*")) return "Edm.String";
        if (t.matches("(?s)^(length|indexof|year|month|day|hour|minute|second)\\(.*")) return "Edm.Int32";
        if (t.matches("(?s)^(round|floor|ceiling)\\(.*")) return "Edm.Decimal";
        return null;
    }

    /**
     * Branches of a case body: {@code [condition, value]} pairs. The separator is a colon on nesting level 0 that
     * does not belong to a time literal ({@code 10:30}); write blanks around it when a condition ends with a
     * two-digit number and the value starts with one.
     */
    static List<String[]> caseBranches(String body) {
        List<String[]> branches = new ArrayList<>();
        for (String branch : split(body, ',')) {
            int colon = caseColon(branch);
            if (colon < 0) throw new IllegalArgumentException("case branch without ':' – " + branch.strip());
            branches.add(new String[] {branch.substring(0, colon).strip(), branch.substring(colon + 1).strip()});
        }
        return branches;
    }

    private static int caseColon(String b) {
        int depth = 0;
        int i = 0;
        while (i < b.length()) {
            char c = b.charAt(i);
            if (c == '\'') {
                i = stringEnd(b, i);
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ':' && depth == 0) {
                boolean spaced = (i > 0 && Character.isWhitespace(b.charAt(i - 1)))
                        || (i + 1 < b.length() && Character.isWhitespace(b.charAt(i + 1)));
                boolean time = i >= 2 && i + 2 < b.length() + 1 && Character.isDigit(b.charAt(i - 1)) && Character.isDigit(b.charAt(i - 2))
                        && i + 2 <= b.length() - 1 && Character.isDigit(b.charAt(i + 1)) && Character.isDigit(b.charAt(i + 2));
                if (spaced || !time) return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * For {@code $expand}: {@code Nav/$count(...)} is valid expand syntax there, so the count rewrite only applies
     * inside nested expression options ({@code $filter=}, {@code $orderby=}, {@code $compute=}, {@code $apply=}).
     */
    static String rewriteExpand(String expand) {
        return new ExpressionRewriter().expandOption(expand);
    }

    String expandOption(String expand) {
        if (expand == null || expand.isEmpty()) return expand;
        ExpressionRewriter r = this;
        String s = r.caseExpressions(r.matchesPattern(r.lambdaColons(expand)));
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = stringEnd(s, i);
                out.append(s, i, end);
                i = end;
                continue;
            }
            String option = null;
            for (String o : new String[] {"$filter=", "$orderby=", "$compute=", "$apply="}) {
                if (s.regionMatches(true, i, o, 0, o.length())) option = o;
            }
            if (option == null) {
                out.append(c);
                i++;
                continue;
            }
            int start = i + option.length();
            int end = optionEnd(s, start);
            out.append(s, i, start).append(r.countWithOptions(s.substring(start, end)));
            i = end;
        }
        return out.toString();
    }

    /** End of a nested option value: the next {@code ;} or unmatched {@code )} on its level. */
    private static int optionEnd(String s, int start) {
        int depth = 0;
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                i = stringEnd(s, i);
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') {
                if (depth == 0) return i;
                depth--;
            }
            if (c == ';' && depth == 0) return i;
            i++;
        }
        return s.length();
    }

    // --- lambda colon ---------------------------------------------------------------------------------------

    private String lambdaColons(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = stringEnd(s, i);
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (c == ':' && lambdaVariableBefore(s, i)) {
                // also spaces before the colon: "u :"
                while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) out.setLength(out.length() - 1);
                out.append(':');
                i++;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Is the colon at {@code colon} the one of {@code any(x:} / {@code all(x:}? */
    private static boolean lambdaVariableBefore(String s, int colon) {
        int j = colon - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        int end = j + 1;
        while (j >= 0 && isIdentifierChar(s.charAt(j))) j--;
        if (end - (j + 1) == 0) return false;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        if (j < 0 || s.charAt(j) != '(') return false;
        j--;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        int fnEnd = j + 1;
        while (j >= 0 && Character.isLetter(s.charAt(j))) j--;
        String fn = s.substring(j + 1, fnEnd).toLowerCase(Locale.ROOT);
        return (fn.equals("any") || fn.equals("all")) && j >= 0 && s.charAt(j) == '/';
    }

    // --- matchesPattern ---------------------------------------------------------------------------------------

    private String matchesPattern(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = stringEnd(s, i);
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (startsWithWord(s, i, "matchesPattern") && nextNonSpace(s, i + "matchesPattern".length()) == '(') {
                int open = s.indexOf('(', i);
                int close = closing(s, open);
                List<String> args = split(s.substring(open + 1, close), ',');
                if (args.size() == 2) {
                    out.append("contains(").append(matchesPattern(args.get(0).strip())).append(",concat('")
                            .append(PATTERN_MARKER).append("',").append(matchesPattern(args.get(1).strip())).append("))");
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // --- $count with options ------------------------------------------------------------------------------------

    private String countWithOptions(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = stringEnd(s, i);
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (s.startsWith("/$count", i) && nextNonSpace(s, i + 7) == '(') {
                int open = s.indexOf('(', i + 7);
                int close = closing(s, open);
                String filter = null;
                boolean other = false;
                for (String option : split(s.substring(open + 1, close), ';')) {
                    String o = option.strip();
                    if (o.toLowerCase(Locale.ROOT).startsWith("$filter=")) filter = o.substring("$filter=".length());
                    else if (!o.isEmpty()) other = true;
                }
                // the navigation path in front of /$count
                int start = out.length();
                while (start > 0 && (isIdentifierChar(out.charAt(start - 1)) || out.charAt(start - 1) == '/'
                        || out.charAt(start - 1) == '$')) {
                    start--;
                }
                if (filter != null && !other && start < out.length()) {
                    String path = out.substring(start);
                    out.setLength(start);
                    String variable = COUNT_VARIABLE + (++counter);
                    String body = prefixMembers(countWithOptions(filter), variable);
                    out.append("cast(").append(path).append("/any(").append(variable).append(':').append(body)
                            .append("),Edm.Int64)");
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Prefixes the member paths of a filter written for the elements of a collection with {@code variable/}, so it
     * can be the body of a lambda. A member path starts with an identifier that is not preceded by {@code /} or
     * {@code .}, not followed by {@code (} (function, key), {@code :} (lambda variable) or {@code '} (typed
     * literal), and is no keyword or lambda variable of the filter itself.
     */
    static String prefixMembers(String filter, String variable) {
        Set<String> lambdaVariables = new java.util.HashSet<>();
        for (int k = 0; k < filter.length(); k++) {
            if (filter.charAt(k) == ':' && lambdaVariableBefore(filter, k)) {
                int j = k - 1;
                while (j >= 0 && isIdentifierChar(filter.charAt(j))) j--;
                lambdaVariables.add(filter.substring(j + 1, k));
            }
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (c == '\'') {
                int end = stringEnd(filter, i);
                out.append(filter, i, end);
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                // numbers, dates, times: OData writes subtraction as "sub", so - + : belong to the literal
                while (j < filter.length() && (Character.isLetterOrDigit(filter.charAt(j)) || ".:-+".indexOf(filter.charAt(j)) >= 0)) j++;
                out.append(filter, i, j);
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '@') {
                int j = i + 1;
                while (j < filter.length() && (isIdentifierChar(filter.charAt(j)) || filter.charAt(j) == '.')) j++;
                String token = filter.substring(i, j);
                char before = previousNonSpace(out);
                char after = nextNonSpace(filter, j);
                boolean member = before != '/' && before != '.' && after != '(' && after != ':' && after != '\''
                        && !token.contains(".") && !token.startsWith("$") && !token.startsWith("@")
                        && !KEYWORDS.contains(token.toLowerCase(Locale.ROOT)) && !lambdaVariables.contains(token);
                if (member) out.append(variable).append('/');
                out.append(token);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean startsWithWord(String s, int i, String word) {
        if (!s.regionMatches(true, i, word, 0, word.length())) return false;
        return i == 0 || !(isIdentifierChar(s.charAt(i - 1)) || s.charAt(i - 1) == '/' || s.charAt(i - 1) == '.');
    }

    private static char nextNonSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i < s.length() ? s.charAt(i) : 0;
    }

    private static char previousNonSpace(CharSequence s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i >= 0 ? s.charAt(i) : 0;
    }

    /** Index after the string literal starting at {@code start} ({@code ''} escapes a quote). */
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

    /** Index of the parenthesis closing the one at {@code open} (or the end of the text). */
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
        return s.length() - 1;
    }

    /** Splits at {@code sep} on nesting level 0, outside string literals. */
    private static List<String> split(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int startPart = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                i = stringEnd(s, i);
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == sep && depth == 0) {
                parts.add(s.substring(startPart, i));
                startPart = i + 1;
            }
            i++;
        }
        parts.add(s.substring(startPart));
        return parts;
    }
}
