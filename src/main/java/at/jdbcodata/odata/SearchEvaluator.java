package at.jdbcodata.odata;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.server.api.uri.queryoption.SearchOption;
import org.apache.olingo.server.api.uri.queryoption.search.SearchBinary;
import org.apache.olingo.server.api.uri.queryoption.search.SearchBinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.search.SearchExpression;
import org.apache.olingo.server.api.uri.queryoption.search.SearchTerm;
import org.apache.olingo.server.api.uri.queryoption.search.SearchUnary;

/**
 * {@code $search}: a term (or phrase) matches an entity when one of its properties contains it, ignoring case.
 * Numbers are compared in plain notation ({@code 1500.5}), dates and timestamps in ISO format. {@code AND},
 * {@code OR} and {@code NOT} combine terms; juxtaposed terms ({@code $search=Wien Software}) mean {@code AND}.
 */
final class SearchEvaluator {

    private SearchEvaluator() {
    }

    static List<Entity> filter(List<Entity> entities, SearchOption search) {
        if (search == null || search.getSearchExpression() == null) return entities;
        SearchExpression expr = search.getSearchExpression();
        return entities.stream().filter(e -> matches(expr, e)).toList();
    }

    static boolean matches(SearchExpression expr, Entity entity) {
        if (expr instanceof SearchBinary b) {
            boolean left = matches(b.getLeftOperand(), entity);
            return b.getOperator() == SearchBinaryOperatorKind.OR ? left || matches(b.getRightOperand(), entity)
                    : left && matches(b.getRightOperand(), entity);
        }
        if (expr instanceof SearchUnary u) {
            return !matches(u.getOperand(), entity);
        }
        if (expr instanceof SearchTerm t) {
            String needle = unquote(t.getSearchTerm()).toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) return true;
            for (Property p : entity.getProperties()) {
                Object v = p.getValue();
                if (v != null && text(v).toLowerCase(Locale.ROOT).contains(needle)) return true;
            }
            return false;
        }
        return true;
    }

    private static String text(Object v) {
        Object canonical = FilterValues.canonical(v);
        if (canonical instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        return String.valueOf(canonical);
    }

    private static String unquote(String term) {
        if (term == null) return "";
        String t = term.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }
}
