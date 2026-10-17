package at.jdbcodata.odata;

import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.SqlDialect;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.OrderByItem;
import org.apache.olingo.server.api.uri.queryoption.OrderByOption;
import org.apache.olingo.server.api.uri.queryoption.expression.Alias;
import org.apache.olingo.server.api.uri.queryoption.expression.Binary;
import org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;
import org.apache.olingo.server.api.uri.queryoption.expression.Literal;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.api.uri.queryoption.expression.Method;
import org.apache.olingo.server.api.uri.queryoption.expression.MethodKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Unary;
import org.apache.olingo.server.api.uri.queryoption.expression.UnaryOperatorKind;

/**
 * Translates OData expressions into SQL over the columns of a query result ({@code jdbcodata_q.<label>}), with bind
 * values instead of inlined literals. Only what can be expressed with the same semantics as the in-memory evaluation
 * is translated; anything else raises {@link Untranslatable} and stays in memory.
 *
 * <p>Null semantics follow the in-memory rules: {@code eq null} → {@code IS NULL}, {@code ne} is true when exactly one
 * side is null, ordering comparisons with null are false, {@code not} of an unknown condition is true (written as
 * {@code CASE WHEN … THEN 0 ELSE 1 END = 1}, which also works where the database has no boolean type).
 */
final class SqlFilterTranslator {

    /** The expression has no faithful SQL equivalent; evaluate it in memory. */
    static final class Untranslatable extends Exception {
        Untranslatable(String why) {
            super(why, null, false, false);
        }
    }

    /** An SQL fragment and the values of its {@code ?} placeholders, in order. */
    record Sql(String text, List<Object> binds) {
        static Sql of(String text, Object... binds) {
            return new Sql(text, List.of(binds));
        }
    }

    /** A value operand: SQL plus whether it is the literal {@code null}. */
    private record Value(Sql sql, boolean nullLiteral, String edmType) {
    }

    private final SqlDialect dialect;
    private final Map<String, QueryColumn> columns = new HashMap<>();
    private final EvaluationContext context;

    SqlFilterTranslator(QueryDefinition query, SqlDialect dialect, EvaluationContext context) {
        this.dialect = dialect;
        this.context = context;
        for (QueryColumn c : query.columns()) {
            if (c.visible()) columns.put(c.propertyName(), c);
        }
    }

    // --- predicates ---------------------------------------------------------------------------------------------

    Sql predicate(Expression e) throws Untranslatable {
        if (e instanceof Alias a) return predicate(alias(a));
        if (e instanceof Binary b) {
            BinaryOperatorKind op = b.getOperator();
            switch (op) {
                case AND, OR -> {
                    Sql l = predicate(b.getLeftOperand());
                    Sql r = predicate(b.getRightOperand());
                    return join("(" + l.text() + (op == BinaryOperatorKind.AND ? " AND " : " OR ") + r.text() + ")", l, r);
                }
                case EQ, NE, GT, GE, LT, LE -> {
                    return comparison(op, value(b.getLeftOperand()), value(b.getRightOperand()));
                }
                case IN -> {
                    Value left = value(b.getLeftOperand());
                    if (left.nullLiteral()) throw new Untranslatable("null in IN");
                    List<Object> binds = new ArrayList<>(left.sql().binds());
                    List<String> items = new ArrayList<>();
                    for (Expression item : b.getExpressions()) {
                        Value v = value(item);
                        if (v.nullLiteral()) continue; // never equal
                        items.add(v.sql().text());
                        binds.addAll(v.sql().binds());
                    }
                    if (items.isEmpty()) return Sql.of("1=0");
                    return new Sql("(" + left.sql().text() + " IN (" + String.join(", ", items) + "))", binds);
                }
                default -> throw new Untranslatable("operator " + op);
            }
        }
        if (e instanceof Unary u && u.getOperator() == UnaryOperatorKind.NOT) {
            Sql inner = predicate(u.getOperand());
            return new Sql("(CASE WHEN " + inner.text() + " THEN 0 ELSE 1 END = 1)", inner.binds());
        }
        if (e instanceof Method m) {
            switch (m.getMethod()) {
                case CONTAINS, STARTSWITH, ENDSWITH -> {
                    return like(m);
                }
                default -> throw new Untranslatable("function " + m.getMethod() + " as condition");
            }
        }
        if (e instanceof Member) {
            Value v = value(e);
            if (!"Edm.Boolean".equals(v.edmType())) throw new Untranslatable("non-boolean member as condition");
            return new Sql("(" + v.sql().text() + " = ?)", append(v.sql().binds(), Boolean.TRUE));
        }
        if (e instanceof Literal l) {
            Object v = literal(l);
            if (Boolean.TRUE.equals(v)) return Sql.of("1=1");
            if (Boolean.FALSE.equals(v) || v == null) return Sql.of("1=0");
        }
        throw new Untranslatable(e.getClass().getSimpleName());
    }

    private Sql comparison(BinaryOperatorKind op, Value l, Value r) throws Untranslatable {
        if (l.nullLiteral() && r.nullLiteral()) {
            return Sql.of(op == BinaryOperatorKind.EQ ? "1=1" : "1=0");
        }
        if (l.nullLiteral() || r.nullLiteral()) {
            Value v = l.nullLiteral() ? r : l;
            return switch (op) {
                case EQ -> new Sql("(" + v.sql().text() + " IS NULL)", v.sql().binds());
                case NE -> new Sql("(" + v.sql().text() + " IS NOT NULL)", v.sql().binds());
                default -> Sql.of("1=0");
            };
        }
        String a = l.sql().text();
        String b = r.sql().text();
        boolean ordering = op != BinaryOperatorKind.EQ && op != BinaryOperatorKind.NE;
        if (ordering && ("Edm.String".equals(l.edmType()) || "Edm.String".equals(r.edmType()))) {
            // the database collation orders strings differently than Java
            throw new Untranslatable("string range comparison");
        }
        return switch (op) {
            case EQ -> join("(" + a + " = " + b + ")", l.sql(), r.sql());
            // true when the values differ or exactly one of them is null
            case NE -> repeat("(" + a + " <> " + b + " OR (" + a + " IS NULL AND " + b + " IS NOT NULL) OR (" + a
                    + " IS NOT NULL AND " + b + " IS NULL))", l.sql(), r.sql(), l.sql(), r.sql(), l.sql(), r.sql());
            case GT -> join("(" + a + " > " + b + ")", l.sql(), r.sql());
            case GE -> join("(" + a + " >= " + b + ")", l.sql(), r.sql());
            case LT -> join("(" + a + " < " + b + ")", l.sql(), r.sql());
            default -> join("(" + a + " <= " + b + ")", l.sql(), r.sql());
        };
    }

    /** contains/startswith/endswith with a literal pattern: LIKE with '!' as escape character. */
    private Sql like(Method m) throws Untranslatable {
        if (!dialect.supportsLike()) throw new Untranslatable("LIKE is case-insensitive on this database");
        if (m.getParameters().size() != 2) throw new Untranslatable("arity");
        Value subject = value(m.getParameters().get(0));
        if (!subject.nullLiteral() && !"Edm.String".equals(subject.edmType())) throw new Untranslatable("LIKE on non-string");
        Expression patternExpr = m.getParameters().get(1);
        if (patternExpr instanceof Alias a) patternExpr = alias(a);
        if (!(patternExpr instanceof Literal lit) || !(literal(lit) instanceof String text)) {
            throw new Untranslatable("pattern must be a string literal");
        }
        if (subject.nullLiteral()) return Sql.of("1=0");
        String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        String pattern = switch (m.getMethod()) {
            case CONTAINS -> "%" + escaped + "%";
            case STARTSWITH -> escaped + "%";
            default -> "%" + escaped;
        };
        return new Sql("(" + subject.sql().text() + " LIKE ? ESCAPE '!')", append(subject.sql().binds(), pattern));
    }

    // --- values -------------------------------------------------------------------------------------------------

    private Value value(Expression e) throws Untranslatable {
        if (e instanceof Alias a) return value(alias(a));
        if (e instanceof Member m) {
            List<UriResource> parts = m.getResourcePath().getUriResourceParts();
            if (parts.size() != 1 || !(parts.get(0) instanceof UriResourcePrimitiveProperty p)) {
                throw new Untranslatable("member path");
            }
            QueryColumn c = columns.get(p.getProperty().getName());
            if (c == null) throw new Untranslatable("not a result column: " + p.getProperty().getName());
            return new Value(Sql.of("jdbcodata_q." + dialect.quote(c.name())), false, c.edmType());
        }
        if (e instanceof Literal l) {
            Object v = literal(l);
            if (v == null) return new Value(Sql.of("NULL"), true, null);
            return new Value(Sql.of("?", v), false, literalType(v));
        }
        if (e instanceof Binary b) {
            String op = switch (b.getOperator()) {
                case ADD -> " + ";
                case SUB -> " - ";
                case MUL -> " * ";
                default -> throw new Untranslatable("operator " + b.getOperator()); // DIV/MOD: division by zero differs
            };
            Value l = value(b.getLeftOperand());
            Value r = value(b.getRightOperand());
            if (l.nullLiteral() || r.nullLiteral()) return new Value(Sql.of("NULL"), true, null);
            return new Value(join("(" + l.sql().text() + op + r.sql().text() + ")", l.sql(), r.sql()), false, "Edm.Decimal");
        }
        if (e instanceof Unary u && u.getOperator() == UnaryOperatorKind.MINUS) {
            Value v = value(u.getOperand());
            if (v.nullLiteral()) return v;
            return new Value(new Sql("(-" + v.sql().text() + ")", v.sql().binds()), false, "Edm.Decimal");
        }
        if (e instanceof Method m) return function(m);
        throw new Untranslatable(e.getClass().getSimpleName());
    }

    private Value function(Method m) throws Untranslatable {
        MethodKind kind = m.getMethod();
        if (m.getParameters().size() != 1) throw new Untranslatable("function " + kind);
        Value arg = value(m.getParameters().get(0));
        if (arg.nullLiteral()) return arg;
        String a = arg.sql().text();
        String needed = switch (kind) {
            case TOLOWER, TOUPPER, TRIM, LENGTH -> "Edm.String";
            case YEAR, MONTH, DAY -> "Edm.Date"; // timestamps: the database time zone may differ from UTC
            case HOUR, MINUTE, SECOND -> "Edm.TimeOfDay";
            default -> null;
        };
        if (needed != null && !needed.equals(arg.edmType())) throw new Untranslatable("function " + kind + " on " + arg.edmType());
        String sql = switch (kind) {
            case TOLOWER -> "LOWER(" + a + ")";
            case TOUPPER -> "UPPER(" + a + ")";
            case TRIM -> "TRIM(" + a + ")";
            case LENGTH -> dialect.lengthFunction() + "(" + a + ")";
            case YEAR -> "EXTRACT(YEAR FROM " + a + ")";
            case MONTH -> "EXTRACT(MONTH FROM " + a + ")";
            case DAY -> "EXTRACT(DAY FROM " + a + ")";
            case HOUR, MINUTE, SECOND -> {
                if (!dialect.supportsTimeExtract()) throw new Untranslatable("time parts on this database");
                yield "EXTRACT(" + kind.name() + " FROM " + a + ")";
            }
            default -> throw new Untranslatable("function " + kind);
        };
        String type = switch (kind) {
            case TOLOWER, TOUPPER, TRIM -> "Edm.String";
            default -> "Edm.Int32";
        };
        return new Value(new Sql(sql, arg.sql().binds()), false, type);
    }

    // --- $orderby -----------------------------------------------------------------------------------------------

    /**
     * ORDER BY for the given option, nulls first ascending and last descending, like the in-memory sort. MySQL
     * already orders nulls that way and has no {@code NULLS FIRST}.
     */
    Sql orderBy(OrderByOption orderBy, List<String> keyProperties) throws Untranslatable {
        List<String> parts = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        if (orderBy != null) {
            for (OrderByItem item : orderBy.getOrders()) {
                Value v = value(item.getExpression());
                if (v.nullLiteral()) continue;
                parts.add(v.sql().text() + (item.isDescending() ? " DESC" : " ASC") + nulls(item.isDescending()));
                binds.addAll(v.sql().binds());
            }
        }
        // Stable paging: the key decides between equal sort values.
        for (String key : keyProperties) {
            QueryColumn c = columns.get(key);
            if (c != null) parts.add("jdbcodata_q." + dialect.quote(c.name()) + " ASC" + nulls(false));
        }
        return new Sql(String.join(", ", parts), binds);
    }

    private String nulls(boolean descending) {
        if (dialect == SqlDialect.MYSQL) return "";
        return descending ? " NULLS LAST" : " NULLS FIRST";
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private Expression alias(Alias a) throws Untranslatable {
        AliasQueryOption option = context == null ? null : context.alias(a.getParameterName());
        if (option == null || option.getValue() == null) throw new Untranslatable("alias");
        return option.getValue();
    }

    private static Object literal(Literal l) throws Untranslatable {
        try {
            Object v = new EntityExpressionVisitor(new Entity()).visitLiteral(l);
            // no DateTimeOffset: comparing an instant with a TIMESTAMP column depends on the session time zone
            if (v instanceof BigDecimal || v instanceof String || v instanceof Boolean || v == null
                    || v instanceof java.time.LocalDate || v instanceof java.time.LocalTime) {
                return v;
            }
            throw new Untranslatable("literal type");
        } catch (ExpressionVisitException | ODataApplicationException e) {
            throw new Untranslatable("literal");
        }
    }

    private static String literalType(Object v) {
        if (v instanceof String) return "Edm.String";
        if (v instanceof Boolean) return "Edm.Boolean";
        if (v instanceof java.time.LocalDate) return "Edm.Date";
        if (v instanceof java.time.LocalTime) return "Edm.TimeOfDay";
        return "Edm.Decimal";
    }

    private static Sql join(String text, Sql... parts) {
        List<Object> binds = new ArrayList<>();
        for (Sql p : parts) binds.addAll(p.binds());
        return new Sql(text, binds);
    }

    private static Sql repeat(String text, Sql... partsInOrder) {
        return join(text, partsInOrder);
    }

    private static List<Object> append(List<Object> binds, Object value) {
        List<Object> out = new ArrayList<>(binds);
        out.add(value);
        return out;
    }
}
