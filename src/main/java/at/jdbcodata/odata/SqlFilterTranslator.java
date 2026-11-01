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
    /** Visible columns SQL cannot compare (collections, SQL arrays): $search may still match their elements. */
    private final List<QueryColumn> opaque = new ArrayList<>();
    private final EvaluationContext context;

    SqlFilterTranslator(QueryDefinition query, SqlDialect dialect, EvaluationContext context) {
        this(query.columns(), dialect, context);
    }

    /** Over arbitrary result columns, e.g. those of a pushed aggregation ({@link Aggregation#columns()}). */
    SqlFilterTranslator(List<QueryColumn> resultColumns, SqlDialect dialect, EvaluationContext context) {
        this.dialect = dialect;
        this.context = context;
        for (QueryColumn c : resultColumns) {
            if (c.visible() && c.sqlComparable()) columns.put(c.propertyName(), c); // collections stay in memory
            else if (c.visible()) opaque.add(c);
        }
    }

    // --- predicates ---------------------------------------------------------------------------------------------

    Sql predicate(Expression e) throws Untranslatable {
        org.apache.olingo.server.api.uri.queryoption.search.SearchExpression search = searchPlaceholder(e);
        if (search != null) return search(search); // exact, or Untranslatable (see prefilter)
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
                    boolean string = "Edm.String".equals(left.edmType());
                    List<Object> binds = new ArrayList<>(left.sql().binds());
                    List<String> items = new ArrayList<>();
                    for (Expression item : b.getExpressions()) {
                        Value v = value(item);
                        if (v.nullLiteral()) continue; // never equal
                        items.add(string ? dialect.binary(v.sql().text()) : v.sql().text());
                        binds.addAll(v.sql().binds());
                    }
                    if (items.isEmpty()) return Sql.of("1=0");
                    String subject = string ? dialect.binary(left.sql().text()) : left.sql().text();
                    return new Sql("(" + subject + " IN (" + String.join(", ", items) + "))", binds);
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
        if ("Edm.String".equals(l.edmType()) || "Edm.String".equals(r.edmType())) {
            // compare like Java does, not by the database collation
            a = dialect.binary(a);
            b = dialect.binary(b);
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
        return new Sql("(" + likeSubject(subject.sql().text()) + " LIKE ? ESCAPE '!')", append(subject.sql().binds(), pattern));
    }

    /** LIKE subject with case- and accent-sensitive matching (Oracle's LIKE already is, NLSSORT would not fit). */
    private String likeSubject(String expression) {
        return dialect == SqlDialect.ORACLE ? expression : dialect.binary(expression);
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

    /**
     * A condition that keeps at least the rows {@code e} keeps, for search placeholders whose exact translation
     * is not possible; {@link Untranslatable} for anything else.
     */
    Sql prefilter(Expression e) throws Untranslatable {
        org.apache.olingo.server.api.uri.queryoption.search.SearchExpression search = searchPlaceholder(e);
        if (search == null) throw new Untranslatable("no search");
        return searchFilter(search).sql();
    }

    /** The search behind {@code cast(@jdbcodata_searchN,Edm.Boolean)}, or null. */
    private org.apache.olingo.server.api.uri.queryoption.search.SearchExpression searchPlaceholder(Expression e) {
        if (e instanceof Method m && m.getMethod() == MethodKind.CAST && !m.getParameters().isEmpty()
                && m.getParameters().get(0) instanceof Alias a) {
            e = a;
        }
        if (!(e instanceof Alias a) || context == null) return null;
        String name = a.getParameterName().startsWith("@") ? a.getParameterName() : "@" + a.getParameterName();
        if (!name.startsWith(QueryRewriter.SEARCH_ALIAS)) return null;
        try {
            return context.search(name);
        } catch (ODataApplicationException ex) {
            return null;
        }
    }

    // --- $search -------------------------------------------------------------------------------------------------

    /**
     * {@code $search} as {@code LOWER(col) LIKE '%term%'} over the string columns, only when that is exact: no term
     * could match the text of a number, date, time or boolean (in memory, terms match those too) and LIKE behaves
     * like the in-memory comparison on this database. Otherwise {@link Untranslatable}; see {@link #searchFilter}.
     */
    Sql search(org.apache.olingo.server.api.uri.queryoption.search.SearchExpression e) throws Untranslatable {
        boolean[] exact = {true};
        Sql sql = searchBound(e, true, exact);
        if (!exact[0]) throw new Untranslatable("search term may match a number, date or boolean");
        return sql;
    }

    /** A $search condition and whether it is exact or only a pre-filter (a superset of the matches). */
    record SearchSql(Sql sql, boolean exact) {
    }

    /**
     * {@code $search} as a condition that keeps at least every matching row: exact where possible, otherwise a
     * superset that the in-memory search then narrows down. For a term that may match non-string values, the
     * columns whose text could contain it are kept ({@code IS NOT NULL}, booleans by value); under {@code NOT} the
     * term needs the opposite, a subset, which is the string match alone.
     */
    SearchSql searchFilter(org.apache.olingo.server.api.uri.queryoption.search.SearchExpression e) throws Untranslatable {
        boolean[] exact = {true};
        Sql sql = searchBound(e, true, exact);
        return new SearchSql(sql, exact[0]);
    }

    /** @param upper true: superset of the matches, false: subset; {@code exact[0]} is cleared when it is neither */
    private Sql searchBound(org.apache.olingo.server.api.uri.queryoption.search.SearchExpression e, boolean upper,
                            boolean[] exact) throws Untranslatable {
        if (e instanceof org.apache.olingo.server.api.uri.queryoption.search.SearchBinary b) {
            Sql l = searchBound(b.getLeftOperand(), upper, exact);
            Sql r = searchBound(b.getRightOperand(), upper, exact);
            boolean or = b.getOperator() == org.apache.olingo.server.api.uri.queryoption.search.SearchBinaryOperatorKind.OR;
            return join("(" + l.text() + (or ? " OR " : " AND ") + r.text() + ")", l, r);
        }
        if (e instanceof org.apache.olingo.server.api.uri.queryoption.search.SearchUnary u) {
            Sql inner = searchBound(u.getOperand(), !upper, exact);
            return new Sql("(CASE WHEN " + inner.text() + " THEN 0 ELSE 1 END = 1)", inner.binds());
        }
        if (e instanceof org.apache.olingo.server.api.uri.queryoption.search.SearchTerm t) {
            String needle = unquoteSearch(t.getSearchTerm()).toLowerCase(java.util.Locale.ROOT);
            if (needle.isEmpty()) return Sql.of("1=1");
            List<String> parts = new ArrayList<>();
            List<Object> binds = new ArrayList<>();
            String pattern = "%" + needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            for (QueryColumn c : columns.values()) {
                if (!"Edm.String".equals(c.edmType())) continue;
                parts.add(likeSubject("LOWER(jdbcodata_q." + dialect.quote(c.name()) + ")") + " LIKE ? ESCAPE '!'");
                binds.add(pattern);
            }
            // LIKE that ignores case and accents (MySQL) finds more than the in-memory search: a superset only
            boolean likeExact = dialect.supportsLike();
            List<String> others = new ArrayList<>();
            List<Object> otherBinds = new ArrayList<>();
            for (QueryColumn c : columns.values()) {
                if ("Edm.String".equals(c.edmType())) continue;
                String col = "jdbcodata_q." + dialect.quote(c.name());
                if ("Edm.Boolean".equals(c.edmType())) {
                    boolean t1 = "true".contains(needle);
                    boolean f1 = "false".contains(needle);
                    if (t1 && f1) others.add(col + " IS NOT NULL");
                    else if (t1 || f1) {
                        others.add(col + " = ?");
                        otherBinds.add(t1);
                    }
                } else if (couldMatch(c.edmType(), needle)) {
                    others.add(col + " IS NOT NULL");
                }
            }
            for (QueryColumn c : opaque) others.add("jdbcodata_q." + dialect.quote(c.name()) + " IS NOT NULL");
            if (!likeExact || !others.isEmpty()) exact[0] = false;
            if (upper) {
                List<String> all = new ArrayList<>(parts);
                all.addAll(others);
                binds.addAll(otherBinds);
                if (all.isEmpty()) return Sql.of("1=0");
                return new Sql("(CASE WHEN (" + String.join(" OR ", all) + ") THEN 1 ELSE 0 END = 1)", binds);
            }
            if (!likeExact || parts.isEmpty()) return Sql.of("1=0");
            return new Sql("(CASE WHEN (" + String.join(" OR ", parts) + ") THEN 1 ELSE 0 END = 1)", binds);
        }
        throw new Untranslatable("search expression");
    }

    /** Could the in-memory text of a value of this type ({@link SearchEvaluator}) contain {@code needle}? */
    private static boolean couldMatch(String edmType, String needle) {
        return switch (edmType == null ? "" : edmType) {
            case "Edm.Byte", "Edm.SByte", "Edm.Int16", "Edm.Int32", "Edm.Int64", "Edm.Decimal", "Edm.Double", "Edm.Single" ->
                    needle.matches("[0-9.\\-]+");
            case "Edm.Date" -> needle.matches("[0-9\\-]+");
            case "Edm.TimeOfDay" -> needle.matches("[0-9:.]+");
            case "Edm.DateTimeOffset" -> needle.matches("[0-9.:\\-tz]+");
            case "Edm.Guid" -> needle.matches("[0-9a-f\\-]+");
            default -> true; // unknown text form: keep the row
        };
    }

    private static String unquoteSearch(String term) {
        if (term == null) return "";
        String t = term.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }

    // --- $apply groupby/aggregate --------------------------------------------------------------------------------

    /** One output column of a pushed aggregation. */
    record Slot(String kind, String name, String label, String edmType) {
    }

    /** Label of the first-appearance position of a group ({@code MIN(jdbcodata_rn)}), see {@link #aggregation}. */
    static final String FIRST = "jdbcodata_first";
    /** Label of the rollup level of a row (1 = first grouping). */
    static final String LEVEL = "jdbcodata_level";

    /**
     * SELECT list, GROUP BY and how to read the result back. With grouping, the source rows are numbered and
     * {@link #FIRST} is the position of the first row of each group: ordering by it gives the groups in the order
     * the in-memory evaluation produces (order of first appearance).
     */
    record Aggregation(String select, String groupBy, List<Slot> slots, List<java.util.Set<String>> levels) {

        Aggregation(String select, String groupBy, List<Slot> slots) {
            this(select, groupBy, slots, null);
        }

        boolean grouped() {
            return groupBy != null && !groupBy.isBlank();
        }

        /** Rollup: one level per grouping, the labels of the group slots each level has. */
        boolean rollup() {
            return levels != null;
        }

        /** Tie-breaking order that reproduces the in-memory order: level (rollup), then first appearance. */
        List<String> order() {
            if (!grouped()) return List.of();
            return rollup() ? List.of(LEVEL, FIRST) : List.of(FIRST);
        }

        /**
         * The result columns for translating later steps (filter, orderby) over the aggregated rows. Averages are
         * left out: they are divided in Java, so a comparison in SQL could round differently.
         */
        List<QueryColumn> columns() {
            List<QueryColumn> out = new ArrayList<>();
            int i = 0;
            for (Slot s : slots) {
                if (s.edmType() == null || s.kind().equals("average")) continue;
                out.add(new QueryColumn(++i, s.label(), s.name(), 0, null, s.edmType(), null, null, true, false, true));
            }
            if (grouped()) out.add(new QueryColumn(++i, FIRST, FIRST, 0, null, "Edm.Int64", null, null, false, false, true));
            if (rollup()) out.add(new QueryColumn(++i, LEVEL, LEVEL, 0, null, "Edm.Int64", null, null, false, false, true));
            return out;
        }

        /** Names of the grouping properties. */
        List<String> groupNames() {
            return slots.stream().filter(s -> s.kind().equals("group")).map(Slot::name).toList();
        }
    }

    private static final java.util.Set<String> GROUPABLE = java.util.Set.of("Edm.String", "Edm.Int16", "Edm.Int32",
            "Edm.Int64", "Edm.Byte", "Edm.SByte", "Edm.Decimal", "Edm.Boolean", "Edm.Date");
    private static final java.util.Set<String> SUMMABLE = java.util.Set.of("Edm.Int16", "Edm.Int32", "Edm.Int64",
            "Edm.Byte", "Edm.SByte", "Edm.Decimal");

    /**
     * {@code groupby((P1,P2),aggregate(...))} or {@code aggregate(...)} over plain columns with sum, min, max,
     * average, countdistinct and {@code $count}. Average is read as SUM and COUNT and divided like in memory;
     * double columns are not summed (floating point sums differ), strings are not compared by min/max (collation).
     */
    Aggregation aggregation(List<List<UriResource>> groupPaths,
                            List<org.apache.olingo.server.api.uri.queryoption.apply.AggregateExpression> aggregates)
            throws Untranslatable {
        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        int n = 0;
        for (List<UriResource> path : groupPaths) {
            QueryColumn c = column(path);
            if (!GROUPABLE.contains(c.edmType())) throw new Untranslatable("grouping by " + c.edmType());
            String label = "g" + (++n);
            String col = "jdbcodata_q." + dialect.quote(c.name());
            // MySQL groups 'a' and 'A' together under its default collations
            if (dialect == SqlDialect.MYSQL && "Edm.String".equals(c.edmType())) col = dialect.binary(col);
            select.add(("Edm.String".equals(c.edmType()) ? dialect.text(col) : col) + " AS " + dialect.quote(label));
            group.add(col);
            slots.add(new Slot("group", c.propertyName(), label, c.edmType()));
        }
        int a = 0;
        for (org.apache.olingo.server.api.uri.queryoption.apply.AggregateExpression agg : aggregates) {
            List<UriResource> path = agg.getPath();
            if (agg.getExpression() instanceof Member m) path = m.getResourcePath().getUriResourceParts(); // amount with sum
            else if (agg.getExpression() != null) throw new Untranslatable("aggregate over an expression");
            if (agg.getAlias() == null || agg.getCustomMethod() != null
                    || agg.getInlineAggregateExpression() != null || (agg.getFrom() != null && !agg.getFrom().isEmpty())) {
                throw new Untranslatable("aggregate expression");
            }
            String label = "a" + (++a);
            if (agg.getStandardMethod() == null) {
                if (path == null || path.size() != 1 || !(path.get(0) instanceof org.apache.olingo.server.api.uri.UriResourceCount)) {
                    throw new Untranslatable("aggregate without method");
                }
                select.add("COUNT(*) AS " + dialect.quote(label));
                slots.add(new Slot("count", agg.getAlias(), label, "Edm.Int64"));
                continue;
            }
            QueryColumn c = column(path);
            String col = "jdbcodata_q." + dialect.quote(c.name());
            switch (agg.getStandardMethod()) {
                case SUM -> {
                    if (!SUMMABLE.contains(c.edmType())) throw new Untranslatable("sum of " + c.edmType());
                    select.add("SUM(" + col + ") AS " + dialect.quote(label));
                    slots.add(new Slot("sum", agg.getAlias(), label, "Edm.Decimal"));
                }
                case AVERAGE -> {
                    if (!SUMMABLE.contains(c.edmType())) throw new Untranslatable("average of " + c.edmType());
                    select.add("SUM(" + col + ") AS " + dialect.quote(label + "s"));
                    select.add("COUNT(" + col + ") AS " + dialect.quote(label + "c"));
                    slots.add(new Slot("average", agg.getAlias(), label, null));
                }
                case MIN, MAX -> {
                    boolean string = "Edm.String".equals(c.edmType()) && dialect.supportsBinaryMinMax();
                    if (!SUMMABLE.contains(c.edmType()) && !"Edm.Date".equals(c.edmType()) && !string) {
                        throw new Untranslatable(agg.getStandardMethod() + " of " + c.edmType());
                    }
                    String extreme = agg.getStandardMethod().name() + "(" + (string ? dialect.binary(col) : col) + ")";
                    select.add((string ? dialect.text(extreme) : extreme) + " AS " + dialect.quote(label));
                    slots.add(new Slot("minmax", agg.getAlias(), label, c.edmType()));
                }
                case COUNT_DISTINCT -> {
                    if (!GROUPABLE.contains(c.edmType())) throw new Untranslatable("countdistinct of " + c.edmType());
                    String distinct = dialect == SqlDialect.MYSQL && "Edm.String".equals(c.edmType()) ? dialect.binary(col) : col;
                    select.add("COUNT(DISTINCT " + distinct + ") AS " + dialect.quote(label));
                    slots.add(new Slot("count", agg.getAlias(), label, "Edm.Int64"));
                }
                default -> throw new Untranslatable("method " + agg.getStandardMethod());
            }
        }
        if (!group.isEmpty()) {
            select.add("MIN(jdbcodata_q." + at.jdbcodata.query.SqlWrapper.ROW_NUMBER + ") AS " + dialect.quote(FIRST));
        }
        return new Aggregation(String.join(", ", select), String.join(", ", group), slots);
    }

    /**
     * {@code groupby((P1,rollup(P2,P3)),aggregate(…))}: all groupings in one scan. Every row is combined with the
     * levels 1…n ({@link at.jdbcodata.query.SqlWrapper#LEVEL}); a group column takes part in the levels whose
     * grouping contains it ({@code CASE WHEN level IN (…) THEN col END}) and is null elsewhere, so
     * {@code GROUP BY level, …} yields the groups of every grouping. Works on every supported database, unlike
     * ROLLUP/GROUPING SETS.
     *
     * @param groupings the groupings in in-memory order, the first one containing every path
     */
    Aggregation rollup(List<List<List<UriResource>>> groupings,
                       List<org.apache.olingo.server.api.uri.queryoption.apply.AggregateExpression> aggregates)
            throws Untranslatable {
        Aggregation values = aggregation(List.of(), aggregates);
        List<List<UriResource>> all = groupings.get(0);
        String level = "jdbcodata_q." + at.jdbcodata.query.SqlWrapper.LEVEL;
        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>(List.of(level));
        List<Slot> slots = new ArrayList<>();
        List<java.util.Set<String>> levels = new ArrayList<>();
        for (int k = 0; k < groupings.size(); k++) levels.add(new java.util.HashSet<>());
        select.add(level + " AS " + dialect.quote(LEVEL));
        int n = 0;
        for (List<UriResource> path : all) {
            QueryColumn c = column(path);
            if (!GROUPABLE.contains(c.edmType())) throw new Untranslatable("grouping by " + c.edmType());
            String label = "g" + (++n);
            String col = "jdbcodata_q." + dialect.quote(c.name());
            if (dialect == SqlDialect.MYSQL && "Edm.String".equals(c.edmType())) col = dialect.binary(col);
            List<String> in = new ArrayList<>();
            for (int k = 0; k < groupings.size(); k++) {
                if (containsPath(groupings.get(k), path)) {
                    in.add(String.valueOf(k + 1));
                    levels.get(k).add(label);
                }
            }
            String expr = in.size() == groupings.size() ? col : "CASE WHEN " + level + " IN (" + String.join(", ", in) + ") THEN " + col + " END";
            select.add(("Edm.String".equals(c.edmType()) ? dialect.text(expr) : expr) + " AS " + dialect.quote(label));
            group.add(expr);
            slots.add(new Slot("group", c.propertyName(), label, c.edmType()));
        }
        if (!values.select().isBlank()) select.add(values.select());
        slots.addAll(values.slots());
        select.add("MIN(jdbcodata_q." + at.jdbcodata.query.SqlWrapper.ROW_NUMBER + ") AS " + dialect.quote(FIRST));
        return new Aggregation(String.join(", ", select), String.join(", ", group), slots, levels);
    }

    private static boolean containsPath(List<List<UriResource>> grouping, List<UriResource> path) {
        for (List<UriResource> p : grouping) if (sameMember(p, path)) return true;
        return false;
    }

    private static boolean sameMember(List<UriResource> a, List<UriResource> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).getSegmentValue().equals(b.get(i).getSegmentValue())) return false;
        return true;
    }

    private QueryColumn column(List<UriResource> path) throws Untranslatable {
        if (path == null || path.size() != 1 || !(path.get(0) instanceof UriResourcePrimitiveProperty p)) {
            throw new Untranslatable("path");
        }
        QueryColumn c = columns.get(p.getProperty().getName());
        if (c == null) throw new Untranslatable("not a result column");
        return c;
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
                String text = "Edm.String".equals(v.edmType()) ? dialect.binary(v.sql().text()) : v.sql().text();
                parts.add(text + (item.isDescending() ? " DESC" : " ASC") + nulls(item.isDescending()));
                binds.addAll(v.sql().binds());
            }
        }
        // Stable paging: the key decides between equal sort values.
        for (String key : keyProperties) {
            QueryColumn c = columns.get(key);
            if (c == null) continue;
            String col = "jdbcodata_q." + dialect.quote(c.name());
            parts.add(("Edm.String".equals(c.edmType()) ? dialect.binary(col) : col) + " ASC" + nulls(false));
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
