package at.jdbcodata.odata;

import java.util.List;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;

/**
 * What expression evaluation needs from the request: following navigation properties (the source query is taken
 * from the entity's type {@code JdbcOdata.<Query>}; implementations execute each target at most once per request,
 * a to-one navigation yields zero or one entity) and parameter aliases ({@code $filter=Name eq @n&@n='Wien'}).
 */
@FunctionalInterface
interface EvaluationContext {

    List<Entity> navigate(Entity source, String navigationProperty) throws ODataApplicationException;

    /** The alias option {@code @name}, or {@code null} if the request does not define it. */
    default AliasQueryOption alias(String name) {
        return null;
    }

    /**
     * Branches of {@code case(...)} placeholder {@code name} ({@code @jdbcodata_caseN}), parsed for the type of
     * {@code entity}: pairs of condition and value; {@code null} if the request has no such case.
     */
    default List<Expression[]> caseBranches(String name, Entity entity) throws ODataApplicationException {
        return null;
    }

    /**
     * As above for a case inside lambda bodies: {@code lambdaVariables} are the elements bound to the variables in
     * scope, which the branches may reference ({@code u/Betrag}).
     */
    default List<Expression[]> caseBranches(String name, Entity entity, java.util.Map<String, Entity> lambdaVariables)
            throws ODataApplicationException {
        return caseBranches(name, entity);
    }

    /** The search behind placeholder {@code @jdbcodata_searchN} (see {@link QueryRewriter#SEARCH_ALIAS}), or null. */
    default org.apache.olingo.server.api.uri.queryoption.search.SearchExpression search(String name)
            throws ODataApplicationException {
        return null;
    }

    /** All rows of an entity set ({@code $root/Set} in hierarchy transformations). */
    default List<Entity> entitySet(String name) throws ODataApplicationException {
        throw new ODataApplicationException("$root not supported here", 501, java.util.Locale.ENGLISH);
    }

    /** {@code $root/EntitySet(key)}: the entity with this key, 404 if there is none. */
    default Entity lookup(String entitySet, List<UriParameter> keys) throws ODataApplicationException {
        throw new ODataApplicationException("$root not supported here", 501, java.util.Locale.ENGLISH);
    }
}
