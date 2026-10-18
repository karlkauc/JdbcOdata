package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.olingo.server.api.uri.queryoption.search.SearchBinary;
import org.apache.olingo.server.api.uri.queryoption.search.SearchBinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.search.SearchExpression;
import org.apache.olingo.server.api.uri.queryoption.search.SearchTerm;
import org.apache.olingo.server.api.uri.queryoption.search.SearchUnary;
import org.apache.olingo.server.api.uri.queryoption.search.SearchUnaryOperatorKind;

/**
 * A parser for the full OData {@code $search} syntax, for what Olingo 5 rejects: parentheses
 * ({@code (Wien OR Linz) AND NOT Bau}), {@code NOT} before a parenthesis and, inside {@code $apply=search(…)}, words
 * starting with a digit and {@code NOT}. Precedence NOT > AND > OR, juxtaposition means AND, phrases are in double
 * quotes ({@code "Linzer Bau"}, {@code \"} and {@code \\} escaped). {@code NOT} of a group is pushed down to the terms
 * (De Morgan), because Olingo's model only negates terms. The result implements Olingo's interfaces, so evaluation
 * and SQL translation work as for parsed searches.
 */
final class SearchSyntax {

    private final String text;
    private int pos;

    private SearchSyntax(String text) {
        this.text = text;
    }

    static SearchExpression parse(String text) {
        SearchSyntax p = new SearchSyntax(text == null ? "" : text);
        p.skipBlanks();
        if (p.pos >= p.text.length()) throw new IllegalArgumentException("empty search expression");
        SearchExpression e = p.or();
        p.skipBlanks();
        if (p.pos < p.text.length()) throw new IllegalArgumentException("unexpected '" + p.text.substring(p.pos) + "' in search");
        return e;
    }

    /**
     * Does Olingo's parser need help with this search text? Parentheses outside phrases always; inside $apply also
     * NOT and words that start with a digit.
     */
    static boolean needsOwnParser(String text, boolean inApply) {
        if (text == null) return false;
        boolean phrase = false;
        boolean wordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && phrase) {
                i++;
                continue;
            }
            if (c == '"') {
                phrase = !phrase;
                continue;
            }
            if (phrase) continue;
            if (c == '(' || c == ')') return true;
            if (inApply && wordStart && Character.isDigit(c)) return true;
            if (inApply && wordStart && text.startsWith("NOT", i)
                    && (i + 3 == text.length() || Character.isWhitespace(text.charAt(i + 3)) || text.charAt(i + 3) == '(')) {
                return true;
            }
            wordStart = Character.isWhitespace(c);
        }
        return false;
    }

    private SearchExpression or() {
        SearchExpression left = and();
        while (keyword("OR")) left = new Binary(SearchBinaryOperatorKind.OR, left, and());
        return left;
    }

    private SearchExpression and() {
        SearchExpression left = unary();
        while (true) {
            skipBlanks();
            if (pos >= text.length() || text.charAt(pos) == ')' || peekKeyword("OR")) return left;
            keyword("AND"); // optional: juxtaposition is AND
            left = new Binary(SearchBinaryOperatorKind.AND, left, unary());
        }
    }

    private SearchExpression unary() {
        skipBlanks();
        if (keyword("NOT")) return negate(unary());
        if (pos < text.length() && text.charAt(pos) == '(') {
            pos++;
            SearchExpression inner = or();
            skipBlanks();
            if (pos >= text.length() || text.charAt(pos) != ')') throw new IllegalArgumentException("missing ')' in search");
            pos++;
            return inner;
        }
        return term();
    }

    private SearchExpression term() {
        skipBlanks();
        if (pos >= text.length()) throw new IllegalArgumentException("search term expected");
        if (text.charAt(pos) == '"') {
            int start = pos++;
            while (pos < text.length() && text.charAt(pos) != '"') {
                if (text.charAt(pos) == '\\') pos++;
                pos++;
            }
            if (pos >= text.length()) throw new IllegalArgumentException("unterminated phrase in search");
            pos++;
            return new Term(text.substring(start, pos));
        }
        int start = pos;
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos)) && text.charAt(pos) != '('
                && text.charAt(pos) != ')' && text.charAt(pos) != '"') {
            pos++;
        }
        if (start == pos) throw new IllegalArgumentException("search term expected at '" + text.substring(pos) + "'");
        String word = text.substring(start, pos);
        if (word.equals("AND") || word.equals("OR") || word.equals("NOT")) {
            throw new IllegalArgumentException("search term expected, found " + word);
        }
        return new Term(word);
    }

    /** NOT pushed down to the terms (De Morgan); double negation cancels. */
    private static SearchExpression negate(SearchExpression e) {
        if (e instanceof Unary u) return u.getOperand();
        if (e instanceof Term t) return new Unary(t);
        Binary b = (Binary) e;
        SearchBinaryOperatorKind flipped = b.getOperator() == SearchBinaryOperatorKind.AND ? SearchBinaryOperatorKind.OR
                : SearchBinaryOperatorKind.AND;
        return new Binary(flipped, negate(b.getLeftOperand()), negate(b.getRightOperand()));
    }

    private boolean peekKeyword(String word) {
        skipBlanks();
        if (!text.startsWith(word, pos)) return false;
        int end = pos + word.length();
        return end == text.length() || Character.isWhitespace(text.charAt(end)) || text.charAt(end) == '(' || text.charAt(end) == '"';
    }

    private boolean keyword(String word) {
        if (!peekKeyword(word)) return false;
        pos += word.length();
        return true;
    }

    private void skipBlanks() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    // --- Olingo's search model ----------------------------------------------------------------------------------

    private abstract static class Node implements SearchExpression {
        @Override
        public boolean isSearchTerm() {
            return this instanceof SearchTerm;
        }

        @Override
        public SearchTerm asSearchTerm() {
            return (SearchTerm) this;
        }

        @Override
        public boolean isSearchBinary() {
            return this instanceof SearchBinary;
        }

        @Override
        public SearchBinary asSearchBinary() {
            return (SearchBinary) this;
        }

        @Override
        public boolean isSearchUnary() {
            return this instanceof SearchUnary;
        }

        @Override
        public SearchUnary asSearchUnary() {
            return (SearchUnary) this;
        }
    }

    private static final class Term extends Node implements SearchTerm {
        private final String term;

        Term(String term) {
            this.term = term;
        }

        @Override
        public String getSearchTerm() {
            return term;
        }

        @Override
        public String toString() {
            return term;
        }
    }

    private static final class Unary extends Node implements SearchUnary {
        private final SearchTerm operand;

        Unary(SearchTerm operand) {
            this.operand = operand;
        }

        @Override
        public SearchUnaryOperatorKind getOperator() {
            return SearchUnaryOperatorKind.NOT;
        }

        @Override
        public SearchTerm getOperand() {
            return operand;
        }

        @Override
        public String toString() {
            return "NOT " + operand;
        }
    }

    private static final class Binary extends Node implements SearchBinary {
        private final SearchBinaryOperatorKind operator;
        private final SearchExpression left;
        private final SearchExpression right;

        Binary(SearchBinaryOperatorKind operator, SearchExpression left, SearchExpression right) {
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        @Override
        public SearchBinaryOperatorKind getOperator() {
            return operator;
        }

        @Override
        public SearchExpression getLeftOperand() {
            return left;
        }

        @Override
        public SearchExpression getRightOperand() {
            return right;
        }

        @Override
        public String toString() {
            return "(" + left + " " + operator.name().toLowerCase(Locale.ROOT) + " " + right + ")";
        }
    }

    /** Positions of {@code search(} transformations in an $apply text (outside string literals). */
    static List<int[]> applySearches(String apply) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < apply.length()) {
            char c = apply.charAt(i);
            if (c == '\'') {
                i = stringEnd(apply, i);
                continue;
            }
            if (apply.startsWith("search(", i) && (i == 0 || "/(,".indexOf(apply.charAt(i - 1)) >= 0)) {
                int open = i + "search".length();
                int close = closing(apply, open);
                if (close < 0) break;
                out.add(new int[] {i, open, close});
                i = close + 1;
                continue;
            }
            i++;
        }
        return out;
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

    /** Closing parenthesis of the one at {@code open}, skipping phrases and string literals; -1 if none. */
    static int closing(String s, int open) {
        int depth = 0;
        boolean phrase = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (phrase) {
                if (c == '\\') i++;
                else if (c == '"') phrase = false;
                continue;
            }
            if (c == '"') phrase = true;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }
}
