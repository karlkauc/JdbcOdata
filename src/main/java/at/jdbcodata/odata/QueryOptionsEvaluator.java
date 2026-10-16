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
import org.apache.olingo.server.api.uri.queryoption.OrderByItem;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;

/** Applies {@code $filter}, {@code $orderby}, {@code $skip} and {@code $top} in memory; count = after filter. */
final class QueryOptionsEvaluator {

    private QueryOptionsEvaluator() {
    }

    static EntityCollection apply(List<Entity> entities, UriInfo uriInfo) throws ODataApplicationException {
        List<Entity> result = entities;
        if (uriInfo.getFilterOption() != null) {
            Expression expr = uriInfo.getFilterOption().getExpression();
            List<Entity> filtered = new ArrayList<>();
            for (Entity e : entities) {
                Object v = evaluate(expr, e);
                if (v instanceof Boolean b) {
                    if (b) filtered.add(e);
                } else {
                    throw new ODataApplicationException("$filter must evaluate to a boolean",
                            HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
                }
            }
            result = filtered;
        }
        if (uriInfo.getOrderByOption() != null) {
            List<OrderByItem> items = uriInfo.getOrderByOption().getOrders();
            List<Map<Entity, Object>> keys = new ArrayList<>();
            for (OrderByItem item : items) {
                Map<Entity, Object> perEntity = new IdentityHashMap<>();
                for (Entity e : result) {
                    perEntity.put(e, evaluate(item.getExpression(), e));
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
        int skip = uriInfo.getSkipOption() == null ? 0 : Math.max(0, uriInfo.getSkipOption().getValue());
        int from = Math.min(skip, total);
        int to = total;
        if (uriInfo.getTopOption() != null) {
            to = Math.min(total, from + Math.max(0, uriInfo.getTopOption().getValue()));
        }
        EntityCollection collection = new EntityCollection();
        collection.getEntities().addAll(result.subList(from, to));
        collection.setCount(total);
        return collection;
    }

    private static Object evaluate(Expression expr, Entity e) throws ODataApplicationException {
        try {
            return expr.accept(new EntityExpressionVisitor(e));
        } catch (ExpressionVisitException ex) {
            throw new ODataApplicationException("Invalid expression: " + ex.getMessage(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH, ex);
        }
    }
}
