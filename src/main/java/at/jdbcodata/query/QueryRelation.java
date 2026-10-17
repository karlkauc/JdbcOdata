package at.jdbcodata.query;

import java.util.List;

/**
 * A relation from this query to another query, published as an OData navigation property.
 *
 * <p>Relations are metadata only: {@code $metadata} gains a {@code NavigationProperty} (with a
 * {@code ReferentialConstraint} for to-one relations whose columns exist) and the entity set a
 * {@code NavigationPropertyBinding}, so OData explorers, Excel and Power BI show how the feeds hang together.
 * Navigation paths and {@code $expand} are not evaluated; clients join on the columns themselves.
 *
 * @param name          navigation property name ({@code [A-Za-z][A-Za-z0-9_]*}), unique among the query's properties
 * @param targetQuery   name of the target query (entity set); a missing or invisible target hides the relation
 * @param localColumns  property names of this query that reference the target, same length as {@code targetColumns}
 * @param targetColumns property names of the target query that are referenced
 * @param toMany        {@code true}: the target side is a collection (one customer → many orders)
 * @param partner       name of the inverse navigation property on the target query, optional
 * @param description   free text for admins
 */
public record QueryRelation(
        String name,
        String targetQuery,
        List<String> localColumns,
        List<String> targetColumns,
        boolean toMany,
        String partner,
        String description) {

    public QueryRelation {
        localColumns = localColumns == null ? List.of() : List.copyOf(localColumns);
        targetColumns = targetColumns == null ? List.of() : List.copyOf(targetColumns);
    }
}
