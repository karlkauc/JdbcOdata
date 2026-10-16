package at.jdbcodata.odata;

import at.jdbcodata.cache.CacheKey;
import at.jdbcodata.cache.CacheStatus;
import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.identity.QueryNotVisibleException;
import at.jdbcodata.query.ParameterBinder;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
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
import org.apache.olingo.server.api.uri.queryoption.CustomQueryOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@code GET /odata/{Query}} and {@code GET /odata/{Query}/$count}: resolves the identity, serves from the
 * per-user cache or runs the query, applies the OData options in memory and writes an execution log record.
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
        Execution exec = execute(entitySet.getName(), uriInfo, req);
        EntityCollection collection = QueryOptionsEvaluator.apply(
                EntityMapper.toEntities(exec.query(), exec.table()), uriInfo);

        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), null, uriInfo.getSelectOption());
        ContextURL contextUrl = ContextURL.with().entitySet(entitySet).selectList(selectList).build();
        EntityCollectionSerializerOptions options = EntityCollectionSerializerOptions.with()
                .id(req.getRawBaseUri() + "/" + entitySet.getName())
                .contextURL(contextUrl)
                .select(uriInfo.getSelectOption())
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
        Execution exec = execute(entitySet.getName(), uriInfo, req);
        EntityCollection collection = QueryOptionsEvaluator.apply(
                EntityMapper.toEntities(exec.query(), exec.table()), uriInfo);
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

    private Execution execute(String queryName, UriInfo uriInfo, ODataRequest req) throws ODataApplicationException {
        Instant started = Instant.now();
        long t0 = System.nanoTime();
        QueryDefinition query = support.queryService().findEnabledByName(queryName)
                .orElseThrow(() -> new ODataApplicationException("Entity set '" + queryName + "' not found",
                        HttpStatusCode.NOT_FOUND.getStatusCode(), LOCALE));
        Datasource ds = support.datasourceService().findById(query.datasourceId()).orElse(null);
        LogDraft draft = new LogDraft(started, query, ds, req.getRawQueryPath());
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
            Map<String, String> custom = customOptions(uriInfo);
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
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.UNAUTHORIZED.getStatusCode(), LOCALE, e);
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
        if (uriInfo.getExpandOption() != null || uriInfo.getSearchOption() != null || uriInfo.getApplyOption() != null) {
            throw new ODataApplicationException(
                    "$expand, $search and $apply are not supported; use $filter, $orderby, $top, $skip, $select, $count",
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
