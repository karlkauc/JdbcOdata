package at.jdbcodata.odata;

import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.identity.BasicCredentials;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.query.ParameterBinder;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryExecutor;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.ResultTooLargeException;
import at.jdbcodata.query.SchemaSnapshotMissingException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 * Serves {@code GET /odata/{Query}} and {@code GET /odata/{Query}/$count}. One instance per request, because it
 * carries the caller's credentials.
 */
public class QueryEntityCollectionProcessor implements CountEntityCollectionProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueryEntityCollectionProcessor.class);
    private static final Locale LOCALE = Locale.ENGLISH;

    private final QueryService queryService;
    private final DatasourceService datasourceService;
    private final ExecutionIdentityResolver identityResolver;
    private final QueryExecutor executor;
    private final BasicCredentials credentials;

    private OData odata;
    private ServiceMetadata serviceMetadata;

    public QueryEntityCollectionProcessor(QueryService queryService, DatasourceService datasourceService,
                                          ExecutionIdentityResolver identityResolver, QueryExecutor executor,
                                          BasicCredentials credentials) {
        this.queryService = queryService;
        this.datasourceService = datasourceService;
        this.identityResolver = identityResolver;
        this.executor = executor;
        this.credentials = credentials;
    }

    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }

    @Override
    public void readEntityCollection(ODataRequest request, ODataResponse response, UriInfo uriInfo,
                                     ContentType responseFormat) throws ODataApplicationException, ODataLibraryException {
        EdmEntitySet entitySet = entitySet(uriInfo);
        rejectUnsupportedOptions(uriInfo);
        Execution exec = execute(entitySet.getName(), uriInfo);
        EntityCollection collection = QueryOptionsEvaluator.apply(
                EntityMapper.toEntities(exec.query(), exec.table()), uriInfo);

        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), null, uriInfo.getSelectOption());
        ContextURL contextUrl = ContextURL.with().entitySet(entitySet).selectList(selectList).build();
        EntityCollectionSerializerOptions options = EntityCollectionSerializerOptions.with()
                .id(request.getRawBaseUri() + "/" + entitySet.getName())
                .contextURL(contextUrl)
                .select(uriInfo.getSelectOption())
                .count(uriInfo.getCountOption())
                .build();
        ODataSerializer serializer = odata.createSerializer(responseFormat);
        SerializerResult result = serializer.entityCollection(serviceMetadata, entitySet.getEntityType(), collection, options);

        response.setContent(result.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
        response.setHeader("X-Cache", "DISABLED");
        response.setHeader("X-Query-Version", String.valueOf(exec.query().version()));
    }

    @Override
    public void countEntityCollection(ODataRequest request, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException, ODataLibraryException {
        EdmEntitySet entitySet = entitySet(uriInfo);
        rejectUnsupportedOptions(uriInfo);
        Execution exec = execute(entitySet.getName(), uriInfo);
        EntityCollection collection = QueryOptionsEvaluator.apply(
                EntityMapper.toEntities(exec.query(), exec.table()), uriInfo);
        byte[] body = String.valueOf(collection.getCount()).getBytes(StandardCharsets.UTF_8);
        response.setContent(new ByteArrayInputStream(body));
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, ContentType.TEXT_PLAIN.toContentTypeString());
    }

    private record Execution(QueryDefinition query, ResultTable table) {
    }

    private Execution execute(String queryName, UriInfo uriInfo) throws ODataApplicationException {
        QueryDefinition query = queryService.findEnabledByName(queryName)
                .orElseThrow(() -> new ODataApplicationException("Entity set '" + queryName + "' not found",
                        HttpStatusCode.NOT_FOUND.getStatusCode(), LOCALE));
        if (!query.hasSnapshot()) {
            throw new ODataApplicationException(new SchemaSnapshotMissingException(queryName).getMessage(),
                    HttpStatusCode.SERVICE_UNAVAILABLE.getStatusCode(), LOCALE);
        }
        Datasource ds = datasourceService.findById(query.datasourceId())
                .filter(Datasource::enabled)
                .orElseThrow(() -> new ODataApplicationException("Datasource of query '" + queryName + "' is disabled",
                        HttpStatusCode.SERVICE_UNAVAILABLE.getStatusCode(), LOCALE));
        try {
            ExecutionIdentity identity = identityResolver.resolve(query, credentials);
            Map<String, Object> params = ParameterBinder.resolve(query, customOptions(uriInfo));
            ResultTable table = executor.execute(query, ds, identity, params,
                    executor.effectiveMaxRows(query, ds), executor.effectiveTimeoutSec(query, ds));
            return new Execution(query, table);
        } catch (InvalidRequestException e) {
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE, e);
        } catch (DbAuthenticationException e) {
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.UNAUTHORIZED.getStatusCode(), LOCALE, e);
        } catch (ResultTooLargeException e) {
            throw new ODataApplicationException(e.getMessage(), 413, LOCALE, e);
        } catch (DbExecutionException e) {
            String ref = UUID.randomUUID().toString().substring(0, 8);
            log.error("Query '{}' on '{}' as '{}' failed (ref {}): {}", queryName, ds.name(), credentials.username(),
                    ref, e.getMessage(), e);
            int status = e.isTimeout() ? HttpStatusCode.GATEWAY_TIMEOUT.getStatusCode()
                    : HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode();
            throw new ODataApplicationException("Database error while executing '" + queryName + "' (ref " + ref
                    + "): " + e.getMessage(), status, LOCALE, e);
        } catch (UnsupportedOperationException e) {
            throw new ODataApplicationException(e.getMessage(), HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE, e);
        }
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
