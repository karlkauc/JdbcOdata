package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.queryoption.FilterOption;
import org.apache.olingo.server.api.uri.queryoption.OrderByItem;
import org.apache.olingo.server.api.uri.queryoption.OrderByOption;
import org.apache.olingo.server.api.uri.queryoption.SearchOption;
import org.apache.olingo.server.api.uri.queryoption.SkipOption;
import org.apache.olingo.server.api.uri.queryoption.TopOption;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;

/** Applies {@code $search}, {@code $filter}, {@code $orderby}, {@code $skip} and {@code $top} in memory; count = after filter. */
final class QueryOptionsEvaluator {

    private QueryOptionsEvaluator() {
    }

    static EntityCollection apply(List<Entity> entities, UriInfo uriInfo) throws ODataApplicationException {
        return apply(entities, uriInfo, null);
    }

    /** With {@code navigation}, member paths in $filter/$orderby may follow navigation properties. */
    static EntityCollection apply(List<Entity> entities, UriInfo uriInfo, EvaluationContext navigation)
            throws ODataApplicationException {
        return apply(entities, uriInfo.getSearchOption(), uriInfo.getFilterOption(), uriInfo.getOrderByOption(),
                uriInfo.getSkipOption(), uriInfo.getTopOption(), navigation);
    }

    static EntityCollection apply(List<Entity> entities, FilterOption filter, OrderByOption orderBy, SkipOption skipOption,
                                  TopOption topOption, EvaluationContext navigation) throws ODataApplicationException {
        return apply(entities, null, filter, orderBy, skipOption, topOption, navigation);
    }

    /** Same evaluation for the nested options of an {@code $expand} item; {@code $search} restricts first. */
    static EntityCollection apply(List<Entity> entities, SearchOption search, FilterOption filter, OrderByOption orderBy,
                                  SkipOption skipOption, TopOption topOption, EvaluationContext navigation)
            throws ODataApplicationException {
        List<Entity> result = SearchEvaluator.filter(entities, search);
        if (filter != null) {
            Expression expr = filter.getExpression();
            List<Entity> filtered = new ArrayList<>();
            for (Entity e : result) {
                Object v = evaluate(expr, e, navigation);
                if (v instanceof Boolean b) {
                    if (b) filtered.add(e);
                } else {
                    throw new ODataApplicationException("$filter must evaluate to a boolean",
                            HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
                }
            }
            result = filtered;
        }
        if (orderBy != null) {
            List<OrderByItem> items = orderBy.getOrders();
            List<Map<Entity, Object>> keys = new ArrayList<>();
            for (OrderByItem item : items) {
                Map<Entity, Object> perEntity = new IdentityHashMap<>();
                for (Entity e : result) {
                    perEntity.put(e, evaluate(item.getExpression(), e, navigation));
                }
                keys.add(perEntity);
            }
            Comparator<Entity> comparator = null;
            for (int i = 0; i < items.size(); i++) {
                Map<Entity, Object> key = keys.get(i);
                Comparator<Entity> c = (a, b) -> {
                    Integer r = FilterValues.compare(key.get(a), key.get(b));
                    return r == null ? 0 : r;
                };
                if (items.get(i).isDescending()) c = c.reversed();
                comparator = comparator == null ? c : comparator.thenComparing(c);
            }
            result = new ArrayList<>(result);
            result.sort(comparator);
        }
        int total = result.size();
        int skip = skipOption == null ? 0 : Math.max(0, skipOption.getValue());
        int from = Math.min(skip, total);
        int to = total;
        if (topOption != null) {
            to = Math.min(total, from + Math.max(0, topOption.getValue()));
        }
        EntityCollection collection = new EntityCollection();
        collection.getEntities().addAll(result.subList(from, to));
        collection.setCount(total);
        return collection;
    }

    /** Rows for which {@code expr} is true (one conjunct of a $filter that was not pushed to the database). */
    static List<Entity> filterBy(List<Entity> entities, Expression expr, EvaluationContext navigation)
            throws ODataApplicationException {
        List<Entity> out = new ArrayList<>();
        for (Entity e : entities) {
            Object v = evaluate(expr, e, navigation);
            if (v instanceof Boolean b) {
                if (b) out.add(e);
            } else if (v != null) {
                throw new ODataApplicationException("$filter must evaluate to a boolean",
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
            }
        }
        return out;
    }

    private static Object evaluate(Expression expr, Entity e, EvaluationContext navigation) throws ODataApplicationException {
        try {
            return new EntityExpressionVisitor(e, navigation).evaluate(expr);
        } catch (ExpressionVisitException ex) {
            throw new ODataApplicationException("Invalid expression: " + ex.getMessage(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH, ex);
        }
    }
}
