package at.jdbcodata.odata;

import at.jdbcodata.cache.CacheKey;
import at.jdbcodata.cache.CacheStatus;
import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.identity.ServiceAuthenticationException;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.datasource.TooManyExecutionsException;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.identity.QueryNotVisibleException;
import at.jdbcodata.query.ParameterBinder;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.ResultTooLargeException;
import at.jdbcodata.query.SchemaSnapshotMissingException;
import at.jdbcodata.stats.ClientKind;
import at.jdbcodata.stats.ExecutionRecord;
import at.jdbcodata.stats.ExecutionStatus;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataLibraryException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.processor.CountEntityCollectionProcessor;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.queryoption.CustomQueryOption;
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@code GET /odata/{Query}} and {@code GET /odata/{Query}/$count}: resolves the identity, serves from the
 * per-user cache or runs the query, applies the OData options in memory (including {@code $expand} along declared
 * relations) and writes an execution log record per executed query.
 * One instance per request, because it carries the caller's identity.
 */
public class QueryEntityCollectionProcessor implements CountEntityCollectionProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueryEntityCollectionProcessor.class);
    private static final Locale LOCALE = Locale.ENGLISH;
    static final String REFRESH_OPTION = "refresh";

    private final ProcessorSupport support;
    private final ODataServlet.RequestContext request;

    private OData odata;
    private ServiceMetadata serviceMetadata;

    public QueryEntityCollectionProcessor(ProcessorSupport support, ODataServlet.RequestContext request) {
        this.support = support;
        this.request = request;
    }

    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }

    @Override
    public void readEntityCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo,
                                     ContentType responseFormat) throws ODataApplicationException, ODataLibraryException {
        EdmEntitySet entitySet = entitySet(uriInfo);
        rejectUnsupportedOptions(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        Execution exec = execute(entitySet.getName(), custom, req.getRawQueryPath());
        executions.put(exec.query().name(), exec);
        EntityCollection collection = QueryOptionsEvaluator.apply(
                EntityMapper.toEntities(exec.query(), exec.table()), uriInfo, navigationResolver(custom));
        // Only the requested page is expanded, after $filter/$orderby/$skip/$top of the outer collection.
        if (uriInfo.getExpandOption() != null) {
            expand(collection.getEntities(), exec.query(), uriInfo.getExpandOption(), custom, 1);
        }

        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), uriInfo.getExpandOption(), uriInfo.getSelectOption());
        ContextURL contextUrl = ContextURL.with().entitySet(entitySet).selectList(selectList).build();
        EntityCollectionSerializerOptions options = EntityCollectionSerializerOptions.with()
                .id(req.getRawBaseUri() + "/" + entitySet.getName())
                .contextURL(contextUrl)
                .select(uriInfo.getSelectOption())
                .expand(uriInfo.getExpandOption())
                .count(uriInfo.getCountOption())
                .build();
        ODataSerializer serializer = odata.createSerializer(responseFormat);
        SerializerResult result = serializer.entityCollection(serviceMetadata, entitySet.getEntityType(), collection, options);

        response.setContent(result.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
        setCacheHeaders(response, exec);
    }

    @Override
    public void countEntityCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException, ODataLibraryException {
        EdmEntitySet entitySet = entitySet(uriInfo);
        rejectUnsupportedOptions(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        Execution exec = execute(entitySet.getName(), custom, req.getRawQueryPath());
        executions.put(exec.query().name(), exec);
        EntityCollection collection = QueryOptionsEvaluator.apply(
                EntityMapper.toEntities(exec.query(), exec.table()), uriInfo, navigationResolver(custom));
        byte[] body = String.valueOf(collection.getCount()).getBytes(StandardCharsets.UTF_8);
        response.setContent(new ByteArrayInputStream(body));
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, ContentType.TEXT_PLAIN.toContentTypeString());
        setCacheHeaders(response, exec);
    }

    private static void setCacheHeaders(ODataResponse response, Execution exec) {
        response.setHeader("X-Cache", exec.cacheStatus().name());
        if (exec.cacheAgeSeconds() != null) {
            response.setHeader("X-Cache-Age", String.valueOf(exec.cacheAgeSeconds()));
        }
        response.setHeader("X-Query-Version", String.valueOf(exec.query().version()));
    }

    private record Execution(QueryDefinition query, ResultTable table, CacheStatus cacheStatus, Long cacheAgeSeconds) {
    }

    // --- $expand ------------------------------------------------------------------------------------------------

    /** Nesting depth of {@code $expand} (Umsatz → Kunde → Umsaetze …); deeper requests get 400. */
    static final int MAX_EXPAND_DEPTH = 3;

    /** Queries already executed for this request, so each target runs (or hits the cache) once. */
    private final Map<String, Execution> executions = new HashMap<>();

    /**
     * Expands navigation properties in memory: the target query is executed with the caller's identity (and the
     * same custom query options, so shared parameters such as {@code p_jahr} apply to both), its rows are joined
     * on the relation's column pairs and attached as inline entity / entity set. Nested {@code $filter},
     * {@code $orderby}, {@code $skip}, {@code $top}, {@code $count}, {@code $select} and {@code $expand} work as on
     * the top level; {@code $levels}, {@code $search} and {@code $apply} are not supported.
     */
    private void expand(List<Entity> entities, QueryDefinition source, ExpandOption expandOption,
                        Map<String, String> custom, int depth) throws ODataApplicationException {
        if (depth > MAX_EXPAND_DEPTH) {
            throw new ODataApplicationException("$expand may be nested at most " + MAX_EXPAND_DEPTH + " levels deep",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        for (ExpandItem item : expandOption.getExpandItems()) {
            if (item.getLevelsOption() != null || item.getSearchOption() != null || item.getApplyOption() != null) {
                throw new ODataApplicationException("$levels, $search and $apply are not supported inside $expand",
                        HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
            }
            for (String navName : navigationNames(source, item)) {
                QueryRelation relation = source.relations().stream().filter(r -> r.name().equals(navName)).findFirst()
                        .orElseThrow(() -> new ODataApplicationException("Unknown navigation property '" + navName + "'",
                                HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE));
                if (relation.localColumns().isEmpty() || relation.localColumns().size() != relation.targetColumns().size()) {
                    throw new ODataApplicationException("Navigation property '" + navName
                            + "' has no join columns and cannot be expanded", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
                }
                Execution target = target(relation, custom, "$expand=" + navName + " from " + source.name());
                RelationJoin join = new RelationJoin(relation, EntityMapper.toEntities(target.query(), target.table()));

                // Matches per source entity; nested options are applied per collection, nested $expand once on
                // the distinct target entities (a customer shared by many rows is expanded only once).
                Map<Entity, EntityCollection> inlineSets = new IdentityHashMap<>();
                Map<Entity, Entity> inlineEntities = new IdentityHashMap<>();
                Map<Entity, Boolean> distinct = new IdentityHashMap<>();
                for (Entity e : entities) {
                    List<Entity> matches = join.matches(e);
                    if (relation.toMany()) {
                        EntityCollection c = QueryOptionsEvaluator.apply(matches, item.getFilterOption(),
                                item.getOrderByOption(), item.getSkipOption(), item.getTopOption(), navigationResolver(custom));
                        inlineSets.put(e, c);
                        c.getEntities().forEach(t -> distinct.put(t, Boolean.TRUE));
                    } else if (!matches.isEmpty()) {
                        inlineEntities.put(e, matches.get(0));
                        distinct.put(matches.get(0), Boolean.TRUE);
                    }
                }
                if (item.getExpandOption() != null && !distinct.isEmpty()) {
                    expand(new ArrayList<>(distinct.keySet()), target.query(), item.getExpandOption(), custom, depth + 1);
                }
                for (Entity e : entities) {
                    Link link = new Link();
                    link.setTitle(navName);
                    link.setRel(Constants.NS_NAVIGATION_LINK_REL + navName);
                    if (relation.toMany()) {
                        link.setInlineEntitySet(inlineSets.get(e));
                    } else {
                        Entity t = inlineEntities.get(e);
                        if (t == null) continue; // serialised as null
                        link.setInlineEntity(t);
                    }
                    e.getNavigationLinks().add(link);
                }
            }
        }
    }

    /** The target query's execution, run at most once per request. */
    private Execution target(QueryRelation relation, Map<String, String> custom, String why) throws ODataApplicationException {
        Execution target = executions.get(relation.targetQuery());
        if (target == null) {
            target = execute(relation.targetQuery(), custom, why);
            executions.put(relation.targetQuery(), target);
        }
        return target;
    }

    /** Joins for navigation in $filter/$orderby, per "Source/Navigation"; built once per request. */
    private final Map<String, RelationJoin> filterJoins = new HashMap<>();

    /**
     * Resolver for navigation paths in $filter and $orderby ({@code Kunde/Region eq 'Nord'},
     * {@code Umsaetze/any(u: u/Betrag gt 1000)}): executes the target like $expand does and joins in memory.
     */
    private NavigationResolver navigationResolver(Map<String, String> custom) {
        return (source, navName) -> {
            String sourceQuery = source.getType() == null ? null
                    : source.getType().substring(source.getType().lastIndexOf('.') + 1);
            String key = sourceQuery + "/" + navName;
            RelationJoin join = filterJoins.get(key);
            if (join == null) {
                Execution sourceExec = executions.get(sourceQuery);
                if (sourceExec == null) {
                    throw new ODataApplicationException("Cannot navigate from '" + sourceQuery + "'",
                            HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
                }
                QueryRelation relation = sourceExec.query().relations().stream().filter(r -> r.name().equals(navName))
                        .findFirst().orElseThrow(() -> new ODataApplicationException("Unknown navigation property '"
                                + navName + "'", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE));
                if (relation.localColumns().isEmpty() || relation.localColumns().size() != relation.targetColumns().size()) {
                    throw new ODataApplicationException("Navigation property '" + navName
                            + "' has no join columns and cannot be used in $filter", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
                }
                Execution target = target(relation, custom, "$filter via " + key);
                join = new RelationJoin(relation, EntityMapper.toEntities(target.query(), target.table()));
                filterJoins.put(key, join);
            }
            return join.matches(source);
        };
    }

    /** Navigation property names an expand item stands for ({@code *} = all of the source type). */
    private List<String> navigationNames(QueryDefinition source, ExpandItem item) throws ODataApplicationException {
        if (item.isStar()) {
            if (item.isRef()) {
                throw new ODataApplicationException("$expand=*/$ref is not supported",
                        HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
            }
            EdmEntityType type = serviceMetadata.getEdm().getEntityType(DynamicEdmProvider.typeName(source.name()));
            return type == null ? List.of() : type.getNavigationPropertyNames();
        }
        List<UriResource> parts = item.getResourcePath() == null ? List.of() : item.getResourcePath().getUriResourceParts();
        if (parts.size() != 1 || !(parts.get(0) instanceof UriResourceNavigation nav) || item.isRef() || item.hasCountPath()) {
            throw new ODataApplicationException("Only $expand=<NavigationProperty> (optionally with nested options) is "
                    + "supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        return List.of(nav.getProperty().getName());
    }

    private Execution execute(String queryName, Map<String, String> custom, String optionsForLog)
            throws ODataApplicationException {
        Instant started = Instant.now();
        long t0 = System.nanoTime();
        QueryDefinition query = support.queryService().findEnabledByName(queryName)
                .orElseThrow(() -> new ODataApplicationException("Entity set '" + queryName + "' not found",
                        HttpStatusCode.NOT_FOUND.getStatusCode(), LOCALE));
        Datasource ds = support.datasourceService().findById(query.datasourceId()).orElse(null);
        LogDraft draft = new LogDraft(started, query, ds, optionsForLog);
        try {
            if (!query.hasSnapshot()) {
                throw new SchemaSnapshotMissingException(queryName);
            }
            if (ds == null || !ds.enabled()) {
                draft.status = ExecutionStatus.ERROR;
                throw new ODataApplicationException("Datasource of query '" + queryName + "' is disabled",
                        HttpStatusCode.SERVICE_UNAVAILABLE.getStatusCode(), LOCALE);
            }
            ExecutionIdentity identity = support.identityResolver().resolve(query, request.identity());
            draft.identity = identity;
            Map<String, Object> params = ParameterBinder.resolve(query, custom);
            draft.parameters = maskedParameters(query, params);
            boolean refresh = "true".equalsIgnoreCase(custom.get(REFRESH_OPTION));

            AtomicLong dbNanos = new AtomicLong();
            java.util.function.Supplier<ResultTable> run = () -> {
                long s = System.nanoTime();
                try {
                    return support.executor().execute(query, ds, identity, params,
                            support.executor().effectiveMaxRows(query, ds), support.executor().effectiveTimeoutSec(query, ds));
                } finally {
                    dbNanos.set(System.nanoTime() - s);
                }
            };

            Execution exec;
            if (!query.cacheEnabled()) {
                exec = new Execution(query, run.get(), CacheStatus.DISABLED, null);
            } else {
                CacheKey key = CacheKey.of(query.id(), query.version(), identity.dbUsername(), params);
                Duration ttl = Duration.ofSeconds(Math.max(1, query.cacheTtlSec()));
                if (refresh) {
                    ResultTable table = run.get();
                    support.cache().put(key, params, table, ttl);
                    exec = new Execution(query, table, CacheStatus.BYPASS, null);
                } else {
                    ResultCache.CachedResult r = support.cache().getOrLoad(key, params, ttl, run);
                    if (r.status() == CacheStatus.HIT
                            && ExecutionIdentityResolver.isUnverifiedPassThrough(request.identity(), identity)) {
                        // The key holds only the DB user name. A hit must not bypass the database login, so the
                        // caller's password is checked against the datasource before the cached rows go out.
                        support.identityResolver().verifyLogin(ds, identity.dbUsername(), identity.dbPassword());
                    }
                    exec = new Execution(query, r.cached().table(), r.status(),
                            r.status() == CacheStatus.HIT ? r.cached().ageSeconds() : null);
                }
            }
            draft.cacheStatus = exec.cacheStatus();
            draft.dbDurationMs = dbNanos.get() == 0 ? null : dbNanos.get() / 1_000_000;
            draft.rows = exec.table().rows().size();
            draft.status = ExecutionStatus.OK;
            return exec;
        } catch (SchemaSnapshotMissingException e) {
            draft.fail(ExecutionStatus.ERROR, e.getMessage());
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.SERVICE_UNAVAILABLE.getStatusCode(), LOCALE, e);
        } catch (QueryNotVisibleException e) {
            draft.fail(ExecutionStatus.NOT_FOUND, e.getMessage());
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.NOT_FOUND.getStatusCode(), LOCALE, e);
        } catch (InvalidRequestException e) {
            draft.fail(ExecutionStatus.VALIDATION_ERROR, e.getMessage());
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE, e);
        } catch (DbAuthenticationException e) {
            draft.fail(ExecutionStatus.AUTH_FAILED, e.getMessage());
            support.rateLimiter().recordFailure(request.clientIp(), request.identity().caller());
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.UNAUTHORIZED.getStatusCode(), LOCALE, e);
        } catch (ServiceAuthenticationException e) {
            // Anonymous caller without stored credentials for this datasource: credentials are required.
            draft.fail(ExecutionStatus.AUTH_FAILED, e.getMessage());
            throw new ODataApplicationException("Authentication required for this query. Send HTTP Basic credentials.",
                    HttpStatusCode.UNAUTHORIZED.getStatusCode(), LOCALE, e);
        } catch (TooManyExecutionsException e) {
            draft.fail(ExecutionStatus.BUSY, e.getMessage());
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.SERVICE_UNAVAILABLE.getStatusCode(), LOCALE, e);
        } catch (ResultTooLargeException e) {
            draft.fail(ExecutionStatus.TOO_LARGE, e.getMessage());
            throw new ODataApplicationException(e.getMessage(), 413, LOCALE, e);
        } catch (DbExecutionException e) {
            String ref = UUID.randomUUID().toString().substring(0, 8);
            log.error("Query '{}' as '{}' failed (ref {}): {}", queryName, request.identity().caller(), ref, e.getMessage(), e);
            draft.fail(e.isTimeout() ? ExecutionStatus.TIMEOUT : ExecutionStatus.DB_ERROR, "(ref " + ref + ") " + e.getMessage());
            int status = e.isTimeout() ? HttpStatusCode.GATEWAY_TIMEOUT.getStatusCode()
                    : HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode();
            throw new ODataApplicationException("Database error while executing '" + queryName + "' (ref " + ref
                    + "): " + e.getMessage(), status, LOCALE, e);
        } catch (ODataApplicationException e) {
            if (draft.status == null) draft.fail(ExecutionStatus.ERROR, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            draft.fail(ExecutionStatus.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
            support.executionLog().record(draft.toRecord((System.nanoTime() - t0) / 1_000_000, request));
        }
    }

    /** Mutable collector for the execution log record of one request. */
    private static final class LogDraft {
        final Instant started;
        final QueryDefinition query;
        final Datasource datasource;
        final String odataOptions;
        ExecutionIdentity identity;
        String parameters;
        CacheStatus cacheStatus = CacheStatus.DISABLED;
        Long dbDurationMs;
        Integer rows;
        ExecutionStatus status;
        String error;

        LogDraft(Instant started, QueryDefinition query, Datasource datasource, String odataOptions) {
            this.started = started;
            this.query = query;
            this.datasource = datasource;
            this.odataOptions = odataOptions;
        }

        void fail(ExecutionStatus status, String error) {
            this.status = status;
            this.error = error;
        }

        ExecutionRecord toRecord(long totalMs, ODataServlet.RequestContext request) {
            return new ExecutionRecord(started, Instant.now(), query.id(), query.name(), query.version(),
                    datasource == null ? null : datasource.id(), datasource == null ? null : datasource.name(),
                    request.identity().caller(), identity == null ? null : identity.dbUsername(),
                    identity == null ? null : identity.mode(), ClientKind.detect(request.userAgent()), request.userAgent(),
                    request.clientIp(), parameters, odataOptions, cacheStatus, dbDurationMs, totalMs, rows, null,
                    status == null ? ExecutionStatus.ERROR : status, error);
        }
    }

    private static String maskedParameters(QueryDefinition query, Map<String, Object> params) {
        if (params.isEmpty()) return null;
        Map<String, Object> masked = new LinkedHashMap<>();
        for (QueryParameter p : query.parameters()) {
            Object v = params.get(p.name());
            masked.put(p.name(), p.sensitive() && v != null ? "***" : v);
        }
        return CacheKey.canonical(masked);
    }

    private static EdmEntitySet entitySet(UriInfo uriInfo) throws ODataApplicationException {
        List<UriResource> parts = uriInfo.getUriResourceParts();
        if (parts.isEmpty() || !(parts.get(0) instanceof UriResourceEntitySet es)) {
            throw new ODataApplicationException("Only entity set requests are supported",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        return es.getEntitySet();
    }

    private static void rejectUnsupportedOptions(UriInfo uriInfo) throws ODataApplicationException {
        if (uriInfo.getSearchOption() != null || uriInfo.getApplyOption() != null) {
            throw new ODataApplicationException(
                    "$search and $apply are not supported; use $filter, $orderby, $top, $skip, $select, $count, $expand",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
    }

    private static Map<String, String> customOptions(UriInfo uriInfo) {
        Map<String, String> options = new HashMap<>();
        for (CustomQueryOption o : uriInfo.getCustomQueryOptions()) {
            options.put(o.getName(), o.getText());
        }
        return options;
    }
}
