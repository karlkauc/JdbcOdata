package at.jdbcodata.odata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceCount;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.queryoption.ApplyItem;
import org.apache.olingo.server.api.uri.queryoption.ApplyOption;
import org.apache.olingo.server.api.uri.queryoption.apply.Aggregate;
import org.apache.olingo.server.api.uri.queryoption.apply.AggregateExpression;
import org.apache.olingo.server.api.uri.queryoption.apply.BottomTop;
import org.apache.olingo.server.api.uri.queryoption.apply.Compute;
import org.apache.olingo.server.api.uri.queryoption.apply.ComputeExpression;
import org.apache.olingo.server.api.uri.queryoption.apply.Concat;
import org.apache.olingo.server.api.uri.queryoption.apply.Filter;
import org.apache.olingo.server.api.uri.queryoption.apply.GroupBy;
import org.apache.olingo.server.api.uri.queryoption.apply.GroupByItem;
import org.apache.olingo.server.api.uri.queryoption.apply.OrderBy;
import org.apache.olingo.server.api.uri.queryoption.apply.Search;
import org.apache.olingo.server.api.uri.queryoption.apply.Skip;
import org.apache.olingo.server.api.uri.queryoption.apply.Top;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;

/**
 * In-memory evaluation of {@code $apply} (OData Data Aggregation Extension) on the rows of one query.
 *
 * <p>Supported transformations: {@code filter}, {@code search}, {@code orderby}, {@code top}, {@code skip},
 * {@code identity}, {@code compute}, {@code concat}, {@code aggregate} ({@code sum}, {@code min}, {@code max},
 * {@code average}, {@code countdistinct}, {@code $count}), {@code groupby} (properties, also along to-one navigations,
 * with a nested transformation sequence), {@code topcount}/{@code bottomcount}, {@code topsum}/{@code bottomsum},
 * {@code toppercent}/{@code bottompercent}. Not supported: rollup, custom aggregates and functions, {@code expand},
 * {@code from}, inline aggregates over collections.
 *
 * <p>Aggregated results are dynamic entities: their properties are the group keys and the aliases. Group keys along a
 * navigation ({@code groupby((Kunde/Region))}) are emitted under the last segment's name ({@code Region}).
 */
final class ApplyEvaluator {

    private static final Locale LOCALE = Locale.ENGLISH;

    private final EvaluationContext navigation;

    ApplyEvaluator(EvaluationContext navigation) {
        this.navigation = navigation;
    }

    List<Entity> apply(List<Entity> input, ApplyOption option) throws ODataApplicationException {
        List<Entity> current = input;
        for (ApplyItem item : option.getApplyItems()) {
            current = transform(current, item);
        }
        return current;
    }

    private List<Entity> transform(List<Entity> in, ApplyItem item) throws ODataApplicationException {
        switch (item.getKind()) {
            case IDENTITY:
                return in;
            case FILTER: {
                Filter f = (Filter) item;
                return QueryOptionsEvaluator.apply(in, f.getFilterOption(), null, null, null, navigation).getEntities();
            }
            case SEARCH:
                return SearchEvaluator.filter(in, ((Search) item).getSearchOption());
            case ORDERBY:
                return QueryOptionsEvaluator.apply(in, null, ((OrderBy) item).getOrderByOption(), null, null, navigation)
                        .getEntities();
            case TOP:
                return QueryOptionsEvaluator.apply(in, null, null, null, ((Top) item).getTopOption(), navigation).getEntities();
            case SKIP:
                return QueryOptionsEvaluator.apply(in, null, null, ((Skip) item).getSkipOption(), null, navigation).getEntities();
            case COMPUTE:
                return compute(in, (Compute) item);
            case CONCAT: {
                List<Entity> out = new ArrayList<>();
                for (ApplyOption branch : ((Concat) item).getApplyOptions()) {
                    out.addAll(apply(in, branch));
                }
                return out;
            }
            case AGGREGATE:
                return List.of(aggregate(in, (Aggregate) item));
            case GROUP_BY:
                return groupBy(in, (GroupBy) item);
            case BOTTOM_TOP:
                return bottomTop(in, (BottomTop) item);
            default:
                throw unsupported("$apply transformation " + item.getKind().name().toLowerCase(LOCALE));
        }
    }

    // --- compute ------------------------------------------------------------------------------------------------

    private List<Entity> compute(List<Entity> in, Compute compute) throws ODataApplicationException {
        List<Entity> out = new ArrayList<>(in.size());
        for (Entity e : in) {
            Entity copy = copy(e);
            for (ComputeExpression c : compute.getExpressions()) {
                set(copy, c.getAlias(), evaluate(c.getExpression(), e));
            }
            out.add(copy);
        }
        return out;
    }

    // --- aggregate ----------------------------------------------------------------------------------------------

    private Entity aggregate(List<Entity> in, Aggregate aggregate) throws ODataApplicationException {
        Entity result = new Entity();
        for (AggregateExpression a : aggregate.getExpressions()) {
            if (a.getInlineAggregateExpression() != null || (a.getFrom() != null && !a.getFrom().isEmpty())
                    || a.getCustomMethod() != null) {
                throw unsupported("Inline, 'from' and custom aggregates");
            }
            String alias = a.getAlias();
            if (isCount(a)) {
                set(result, alias == null ? "$count" : alias, BigDecimal.valueOf(in.size()));
                continue;
            }
            if (a.getStandardMethod() == null) throw unsupported("Aggregation without 'with <method>'");
            if (alias == null) throw bad("Aggregate expressions need an alias ('as Name')");
            List<Object> values = new ArrayList<>(in.size());
            for (Entity e : in) values.add(value(a, e));
            set(result, alias, reduce(a.getStandardMethod(), values));
        }
        return result;
    }

    private static boolean isCount(AggregateExpression a) {
        List<UriResource> path = a.getPath();
        return a.getExpression() == null && a.getStandardMethod() == null && path != null && !path.isEmpty()
                && path.get(path.size() - 1) instanceof UriResourceCount;
    }

    private Object value(AggregateExpression a, Entity e) throws ODataApplicationException {
        if (a.getExpression() != null) return evaluate(a.getExpression(), e);
        return path(a.getPath(), e);
    }

    static Object reduce(AggregateExpression.StandardMethod method, List<Object> values) throws ODataApplicationException {
        List<Object> present = values.stream().filter(v -> v != null).toList();
        switch (method) {
            case COUNT_DISTINCT: {
                Set<Object> distinct = new HashSet<>();
                for (Object v : present) distinct.add(RelationJoin.normalize(v));
                return BigDecimal.valueOf(distinct.size());
            }
            case MIN:
            case MAX: {
                Object best = null;
                for (Object v : present) {
                    if (best == null) {
                        best = v;
                        continue;
                    }
                    Integer c = FilterValues.compare(v, best);
                    if (c == null) throw bad("Values cannot be compared for " + method.name().toLowerCase(LOCALE));
                    if (method == AggregateExpression.StandardMethod.MIN ? c < 0 : c > 0) best = v;
                }
                return best;
            }
            case SUM:
            case AVERAGE: {
                BigDecimal sum = BigDecimal.ZERO;
                for (Object v : present) {
                    if (!(v instanceof BigDecimal bd)) throw bad(method.name().toLowerCase(LOCALE) + " needs numeric values");
                    sum = sum.add(bd);
                }
                if (method == AggregateExpression.StandardMethod.SUM) return present.isEmpty() ? null : sum;
                if (present.isEmpty()) return null;
                return sum.divide(BigDecimal.valueOf(present.size()), 10, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default:
                throw unsupported("Aggregate method " + method);
        }
    }

    // --- groupby ------------------------------------------------------------------------------------------------

    private List<Entity> groupBy(List<Entity> in, GroupBy groupBy) throws ODataApplicationException {
        List<GroupByItem> items = groupBy.getGroupByItems();
        for (GroupByItem g : items) {
            if (g.isRollupAll() || (g.getRollup() != null && !g.getRollup().isEmpty())) throw unsupported("rollup");
        }
        Map<List<Object>, List<Entity>> groups = new LinkedHashMap<>();
        Map<List<Object>, List<Object>> keyValues = new LinkedHashMap<>();
        for (Entity e : in) {
            List<Object> values = new ArrayList<>(items.size());
            List<Object> key = new ArrayList<>(items.size());
            for (GroupByItem g : items) {
                Object v = path(g.getPath(), e);
                values.add(v);
                key.add(v == null ? null : RelationJoin.normalize(v));
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            keyValues.putIfAbsent(key, values);
        }
        List<Entity> out = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Entity>> group : groups.entrySet()) {
            List<Entity> rows = groupBy.getApplyOption() == null ? List.of(new Entity())
                    : apply(group.getValue(), groupBy.getApplyOption());
            List<Object> values = keyValues.get(group.getKey());
            for (Entity row : rows) {
                Entity result = new Entity();
                for (int i = 0; i < items.size(); i++) {
                    set(result, name(items.get(i).getPath()), values.get(i));
                }
                for (Property p : row.getProperties()) {
                    if (result.getProperty(p.getName()) == null) result.addProperty(p);
                }
                out.add(result);
            }
        }
        return out;
    }

    // --- topcount & co. -----------------------------------------------------------------------------------------

    private List<Entity> bottomTop(List<Entity> in, BottomTop bt) throws ODataApplicationException {
        Object n = evaluate(bt.getNumber(), in.isEmpty() ? new Entity() : in.get(0));
        if (!(n instanceof BigDecimal limit)) throw bad("The first argument of " + bt.getMethod() + " must be a number");
        Map<Entity, Object> values = new IdentityHashMap<>();
        for (Entity e : in) values.put(e, evaluate(bt.getValue(), e));
        boolean top = bt.getMethod().name().startsWith("TOP");
        Comparator<Entity> byValue = (a, b) -> {
            Integer c = FilterValues.compare(values.get(a), values.get(b));
            if (values.get(a) == null) return values.get(b) == null ? 0 : 1;
            if (values.get(b) == null) return -1;
            return c == null ? 0 : (top ? -c : c);
        };
        List<Entity> sorted = new ArrayList<>(in);
        sorted.sort(byValue);
        switch (bt.getMethod()) {
            case TOP_COUNT:
            case BOTTOM_COUNT:
                return sorted.subList(0, Math.min(sorted.size(), Math.max(0, limit.intValue())));
            case TOP_SUM:
            case BOTTOM_SUM:
                return takeUntil(sorted, values, limit);
            case TOP_PERCENT:
            case BOTTOM_PERCENT: {
                BigDecimal total = BigDecimal.ZERO;
                for (Entity e : sorted) total = total.add(number(values.get(e)));
                return takeUntil(sorted, values, total.multiply(limit).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            }
            default:
                throw unsupported(bt.getMethod().name());
        }
    }

    /** The shortest prefix whose sum reaches {@code threshold} (all rows if the total stays below). */
    private static List<Entity> takeUntil(List<Entity> sorted, Map<Entity, Object> values, BigDecimal threshold)
            throws ODataApplicationException {
        List<Entity> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Entity e : sorted) {
            if (sum.compareTo(threshold) >= 0) break;
            out.add(e);
            sum = sum.add(number(values.get(e)));
        }
        return out;
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private Object evaluate(Expression expr, Entity e) throws ODataApplicationException {
        try {
            return new EntityExpressionVisitor(e, navigation).evaluate(expr);
        } catch (ExpressionVisitException ex) {
            throw bad("Invalid expression: " + ex.getMessage());
        }
    }

    private Object path(List<UriResource> path, Entity e) throws ODataApplicationException {
        if (path == null || path.isEmpty()) throw bad("Empty path in $apply");
        try {
            return new EntityExpressionVisitor(e, navigation).path(path);
        } catch (ExpressionVisitException ex) {
            throw bad("Invalid path: " + ex.getMessage());
        }
    }

    private static String name(List<UriResource> path) throws ODataApplicationException {
        UriResource last = path.get(path.size() - 1);
        if (!(last instanceof UriResourcePrimitiveProperty p)) throw unsupported("groupby on non-primitive paths");
        return p.getProperty().getName();
    }

    private static BigDecimal number(Object v) throws ODataApplicationException {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        throw bad("Expected a number");
    }

    private static Entity copy(Entity e) {
        Entity c = new Entity();
        c.setType(e.getType());
        c.setId(e.getId());
        for (Property p : e.getProperties()) c.addProperty(p);
        return c;
    }

    private static void set(Entity e, String name, Object value) {
        Property existing = e.getProperty(name);
        if (existing != null) e.getProperties().remove(existing);
        e.addProperty(new Property(null, name, ValueType.PRIMITIVE, value));
    }

    private static ODataApplicationException bad(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
    }

    private static ODataApplicationException unsupported(String what) {
        return new ODataApplicationException(what + " not supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
    }
}
