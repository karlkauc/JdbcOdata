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
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmBindingTarget;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveType;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataLibraryException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.batch.BatchFacade;
import org.apache.olingo.server.api.deserializer.batch.BatchOptions;
import org.apache.olingo.server.api.deserializer.batch.BatchRequestPart;
import org.apache.olingo.server.api.deserializer.batch.ODataResponsePart;
import org.apache.olingo.server.api.prefer.PreferencesApplied;
import org.apache.olingo.server.api.processor.BatchProcessor;
import org.apache.olingo.server.api.processor.CountEntityCollectionProcessor;
import org.apache.olingo.server.api.processor.EntityProcessor;
import org.apache.olingo.server.api.processor.PrimitiveValueProcessor;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.EntitySerializerOptions;
import org.apache.olingo.server.api.serializer.PrimitiveSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceCount;
import org.apache.olingo.server.api.uri.UriResourceKind;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.CustomQueryOption;
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@code GET /odata/{Query}}, {@code /$count}, single entities ({@code Query(key)}), navigation paths
 * ({@code Umsatz(1)/Kunde}, {@code Kunden(1)/Umsaetze}) and properties ({@code Kunden(1)/Name[/$value]}): resolves the identity, serves from the
 * per-user cache or runs the query, applies the OData options in memory (including {@code $expand} along declared
 * relations) and writes an execution log record per executed query.
 * One instance per request, because it carries the caller's identity.
 */
public class QueryEntityCollectionProcessor implements CountEntityCollectionProcessor, EntityProcessor,
        PrimitiveValueProcessor, BatchProcessor {

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
        rejectUnsupportedOptions(uriInfo);
        begin(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        Resolved r = resolve(uriInfo.getUriResourceParts(), custom, req);
        if (r.entities() == null) {
            throw new ODataApplicationException("The path does not address a collection",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        EdmEntitySet entitySet = r.entitySet();
        if (uriInfo.getApplyOption() != null) {
            writeApplyResult(req, response, uriInfo, r, custom, responseFormat);
            return;
        }
        EntityCollection collection = QueryOptionsEvaluator.apply(r.entities(), uriInfo, navigationResolver(custom));
        Page page = page(req, uriInfo, collection.getEntities());
        collection.getEntities().clear();
        collection.getEntities().addAll(page.rows());
        if (page.nextLink() != null) collection.setNext(java.net.URI.create(page.nextLink()));
        applyPreference(response, page);
        // Only the delivered page is expanded, after $filter/$orderby/$skip/$top and paging.
        if (uriInfo.getExpandOption() != null) {
            expand(collection.getEntities(), r.query(), uriInfo.getExpandOption(), custom, 1);
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
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), r.root());
    }

    @Override
    public void countEntityCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException, ODataLibraryException {
        rejectUnsupportedOptions(uriInfo);
        begin(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        Resolved r = resolve(uriInfo.getUriResourceParts(), custom, req);
        if (r.entities() == null) {
            throw new ODataApplicationException("$count needs a collection", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        List<Entity> input = uriInfo.getApplyOption() == null ? r.entities()
                : new ApplyEvaluator(navigationResolver(custom)).apply(r.entities(), uriInfo.getApplyOption());
        EntityCollection collection = QueryOptionsEvaluator.apply(input, uriInfo, navigationResolver(custom));
        byte[] body = String.valueOf(collection.getCount()).getBytes(StandardCharsets.UTF_8);
        respond(req, response, new ByteArrayInputStream(body), ContentType.TEXT_PLAIN.toContentTypeString(), r.root());
    }

    /**
     * {@code $apply} first, then $search/$filter/$orderby/$skip/$top/$count on the transformed rows (which may refer
     * to aliases), then $select. Written by {@link ApplyResultWriter} because the rows are dynamic structures.
     */
    private void writeApplyResult(ODataRequest req, ODataResponse response, UriInfo uriInfo, Resolved r,
                                  Map<String, String> custom, ContentType responseFormat) throws ODataApplicationException {
        if (!responseFormat.isCompatible(ContentType.APPLICATION_JSON)) {
            throw new ODataApplicationException("$apply results are available as JSON only",
                    HttpStatusCode.NOT_ACCEPTABLE.getStatusCode(), LOCALE);
        }
        EvaluationContext navigation = navigationResolver(custom);
        List<Entity> applied = new ApplyEvaluator(navigation).apply(r.entities(), uriInfo.getApplyOption());
        EntityCollection filtered = QueryOptionsEvaluator.apply(applied, uriInfo, navigation);
        Page page = page(req, uriInfo, filtered.getEntities());
        applyPreference(response, page);
        java.util.Set<String> select = new java.util.HashSet<>();
        if (uriInfo.getSelectOption() != null) {
            for (org.apache.olingo.server.api.uri.queryoption.SelectItem item : uriInfo.getSelectOption().getSelectItems()) {
                if (item.isStar()) {
                    select.clear();
                    break;
                }
                if (item.getResourcePath() != null && !item.getResourcePath().getUriResourceParts().isEmpty()) {
                    select.add(item.getResourcePath().getUriResourceParts().get(0).getSegmentValue());
                }
            }
        }
        boolean count = uriInfo.getCountOption() != null && uriInfo.getCountOption().getValue();
        byte[] body = ApplyResultWriter.write(r.entitySet().getName(), page.rows(), count ? filtered.getCount() : null,
                select, page.nextLink());
        respond(req, response, new ByteArrayInputStream(body), "application/json;odata.metadata=minimal", r.root());
    }

    // --- request state, paging, ETags -------------------------------------------------------------------------

    /** Parameter aliases of the current request ({@code @name → option}). */
    private final Map<String, AliasQueryOption> aliases = new HashMap<>();

    /**
     * Starts a read: drops per-request state. The processor instance serves all parts of a {@code $batch}, which may
     * use different parameters, so executions and joins must not leak from one part into the next.
     */
    private void begin(UriInfo uriInfo) {
        executions.clear();
        filterJoins.clear();
        aliases.clear();
        if (uriInfo.getAliases() != null) {
            for (AliasQueryOption a : uriInfo.getAliases()) {
                aliases.put(a.getName().startsWith("@") ? a.getName() : "@" + a.getName(), a);
            }
        }
    }

    /** One page of a result: rows to deliver, the link to the next page, the page size applied from {@code Prefer}. */
    private record Page(List<Entity> rows, String nextLink, Integer appliedMaxPageSize) {
    }

    /**
     * Server-driven paging. The page size is {@code jdbcodata.odata.page-size} or the client's
     * {@code Prefer: odata.maxpagesize} (whichever is smaller when both are set). {@code $skiptoken} is the number of
     * rows already delivered of the result after $filter/$orderby/$skip/$top; the next page is served from the same
     * cached result, so pages stay consistent while the cache entry lives.
     */
    private Page page(ODataRequest req, UriInfo uriInfo, List<Entity> rows) throws ODataApplicationException {
        int offset = 0;
        if (uriInfo.getSkipTokenOption() != null) {
            try {
                offset = Integer.parseInt(uriInfo.getSkipTokenOption().getValue());
            } catch (NumberFormatException e) {
                offset = -1;
            }
            if (offset < 0) {
                throw new ODataApplicationException("Invalid $skiptoken", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
            }
        }
        int configured = support.properties() == null ? 0 : support.properties().odata().pageSize();
        Integer preferred = odata.createPreferences(req.getHeaders(HttpHeader.PREFER)).getMaxPageSize();
        int size = configured;
        Integer applied = null;
        if (preferred != null && preferred > 0) {
            size = configured > 0 ? Math.min(preferred, configured) : preferred;
            applied = size;
        }
        List<Entity> rest = rows.subList(Math.min(offset, rows.size()), rows.size());
        String next = null;
        if (size > 0 && rest.size() > size) {
            rest = rest.subList(0, size);
            next = nextLink(req.getRawRequestUri(), offset + size);
        }
        return new Page(new ArrayList<>(rest), next, applied);
    }

    /** The request URI with {@code $skiptoken} replaced. */
    static String nextLink(String rawRequestUri, int skipToken) {
        int q = rawRequestUri.indexOf('?');
        String base = q < 0 ? rawRequestUri : rawRequestUri.substring(0, q);
        StringBuilder query = new StringBuilder();
        if (q >= 0) {
            for (String part : rawRequestUri.substring(q + 1).split("&")) {
                if (part.isEmpty() || part.startsWith("$skiptoken=") || part.toLowerCase(Locale.ROOT).startsWith("%24skiptoken=")) {
                    continue;
                }
                query.append(query.length() == 0 ? "" : "&").append(part);
            }
        }
        query.append(query.length() == 0 ? "" : "&").append("$skiptoken=").append(skipToken);
        return base + "?" + query;
    }

    private static void applyPreference(ODataResponse response, Page page) {
        if (page.appliedMaxPageSize() != null) {
            response.setHeader(HttpHeader.PREFERENCE_APPLIED,
                    PreferencesApplied.with().maxPageSize(page.appliedMaxPageSize()).build().toValueString());
        }
    }

    /**
     * Writes a read response with a weak ETag of the body; answers 304 without body when {@code If-None-Match}
     * matches. The execution headers (X-Cache, X-Query-Version) are kept on both.
     */
    private static void respond(ODataRequest req, ODataResponse response, java.io.InputStream content, String contentType,
                                Execution root) throws ODataApplicationException {
        byte[] body;
        try (java.io.InputStream in = content) {
            body = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new ODataApplicationException("Cannot write the response", HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    LOCALE, e);
        }
        String etag = ResponseETag.of(body);
        response.setHeader(HttpHeader.ETAG, etag);
        if (root != null) setCacheHeaders(response, root);
        if (ResponseETag.matches(req.getHeader(HttpHeader.IF_NONE_MATCH), etag)) {
            response.setStatusCode(HttpStatusCode.NOT_MODIFIED.getStatusCode());
            return;
        }
        response.setContent(new ByteArrayInputStream(body));
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, contentType);
    }

    // --- $batch -------------------------------------------------------------------------------------------------

    /**
     * {@code POST /odata/$batch} (multipart/mixed): every part is dispatched like a single request under the caller's
     * identity; read parts behave exactly as outside a batch, change sets end in 405 because the feed is read-only.
     */
    @Override
    public void processBatch(BatchFacade facade, ODataRequest req, ODataResponse response)
            throws ODataApplicationException, ODataLibraryException {
        String boundary = facade.extractBoundaryFromContentType(req.getHeader(HttpHeader.CONTENT_TYPE));
        List<BatchRequestPart> parts = odata.createFixedFormatDeserializer().parseBatchRequest(req.getBody(), boundary,
                BatchOptions.with().rawBaseUri(req.getRawBaseUri()).rawServiceResolutionUri(req.getRawServiceResolutionUri())
                        .build());
        List<ODataResponsePart> responses = new ArrayList<>();
        for (BatchRequestPart part : parts) {
            responses.add(facade.handleBatchRequest(part));
        }
        String responseBoundary = "batch_" + UUID.randomUUID();
        response.setContent(odata.createFixedFormatSerializer().batchResponse(responses, responseBoundary));
        response.setHeader(HttpHeader.CONTENT_TYPE, ContentType.MULTIPART_MIXED + ";boundary=" + responseBoundary);
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
    }

    @Override
    public ODataResponsePart processChangeSet(BatchFacade facade, List<ODataRequest> requests)
            throws ODataApplicationException, ODataLibraryException {
        List<ODataResponse> responses = new ArrayList<>();
        for (ODataRequest r : requests) {
            responses.add(facade.handleODataRequest(r));
        }
        return new ODataResponsePart(responses, true);
    }

    // --- single entities, navigation paths, properties --------------------------------------------------------

    /** {@code Umsatz(1)}, {@code Umsatz(1)/Kunde}, {@code Kunden(1)/Umsaetze(5)/Produkt}. */
    @Override
    public void readEntity(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        rejectUnsupportedOptions(uriInfo);
        begin(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        Resolved r = resolve(uriInfo.getUriResourceParts(), custom, req);
        setCacheHeaders(response, r.root());
        if (r.single() == null) {
            // Single-valued navigation without a related row (Umsatz(1)/Kunde with no matching customer).
            response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
            return;
        }
        if (uriInfo.getExpandOption() != null) {
            expand(List.of(r.single()), r.query(), uriInfo.getExpandOption(), custom, 1);
        }
        EdmEntitySet entitySet = r.entitySet();
        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), uriInfo.getExpandOption(), uriInfo.getSelectOption());
        ContextURL contextUrl = ContextURL.with().entitySet(entitySet).selectList(selectList)
                .suffix(ContextURL.Suffix.ENTITY).build();
        EntitySerializerOptions options = EntitySerializerOptions.with().contextURL(contextUrl)
                .select(uriInfo.getSelectOption()).expand(uriInfo.getExpandOption()).build();
        SerializerResult result = odata.createSerializer(responseFormat)
                .entity(serviceMetadata, entitySet.getEntityType(), r.single(), options);
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), r.root());
    }

    /** {@code Kunden(1)/Name}. */
    @Override
    public void readPrimitive(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        PropertyRead p = readProperty(uriInfo, req, response);
        if (p == null) return;
        ContextURL contextUrl = ContextURL.with().entitySet(p.entitySet()).keyPath(p.keyPath())
                .navOrPropertyPath(p.edmProperty().getName()).build();
        SerializerResult result = odata.createSerializer(responseFormat).primitive(serviceMetadata,
                (EdmPrimitiveType) p.edmProperty().getType(), p.property(),
                PrimitiveSerializerOptions.with().contextURL(contextUrl).build());
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), null);
    }

    /** {@code Kunden(1)/Name/$value}: the raw value as text. */
    @Override
    public void readPrimitiveValue(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        PropertyRead p = readProperty(uriInfo, req, response);
        if (p == null) return;
        EdmPrimitiveType type = (EdmPrimitiveType) p.edmProperty().getType();
        String text;
        try {
            text = type.valueToString(p.property().getValue(), p.edmProperty().isNullable(), p.edmProperty().getMaxLength(),
                    p.edmProperty().getPrecision(), p.edmProperty().getScale(), p.edmProperty().isUnicode());
        } catch (org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException e) {
            text = String.valueOf(p.property().getValue());
        }
        respond(req, response, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                ContentType.TEXT_PLAIN.toContentTypeString(), null);
    }

    private record PropertyRead(EdmEntitySet entitySet, String keyPath, EdmProperty edmProperty, Property property) {
    }

    /** Resolves the entity in front of a property segment; answers 204 itself when entity or value is null. */
    private PropertyRead readProperty(UriInfo uriInfo, ODataRequest req, ODataResponse response)
            throws ODataApplicationException {
        List<UriResource> parts = new ArrayList<>(uriInfo.getUriResourceParts());
        if (!parts.isEmpty() && parts.get(parts.size() - 1).getKind() == UriResourceKind.value) {
            parts.remove(parts.size() - 1);
        }
        begin(uriInfo);
        if (parts.isEmpty() || !(parts.get(parts.size() - 1) instanceof UriResourcePrimitiveProperty propPart)) {
            throw new ODataApplicationException("Only primitive properties can be read",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        Resolved r = resolve(parts.subList(0, parts.size() - 1), customOptions(uriInfo), req);
        setCacheHeaders(response, r.root());
        Property property = r.single() == null ? null : r.single().getProperty(propPart.getProperty().getName());
        if (property == null || property.getValue() == null) {
            response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
            return null;
        }
        String id = r.single().getId() == null ? "" : r.single().getId().toString();
        int open = id.indexOf('(');
        String keyPath = open >= 0 && id.endsWith(")") ? id.substring(open + 1, id.length() - 1) : id;
        return new PropertyRead(r.entitySet(), keyPath, propPart.getProperty(), property);
    }

    /**
     * Result of walking a resource path: either a collection ({@code entities}), a single entity ({@code single}) or
     * neither (a single-valued navigation without a related row).
     */
    private record Resolved(Execution root, QueryDefinition query, EdmEntitySet entitySet, List<Entity> entities,
                            Entity single) {
    }

    /**
     * Walks {@code EntitySet[(key)] [/Navigation[(key)]]* [/$count]}. Key lookups and navigations work on the
     * in-memory results; each query on the path runs at most once per request.
     */
    private Resolved resolve(List<UriResource> path, Map<String, String> custom, ODataRequest req)
            throws ODataApplicationException {
        List<UriResource> parts = new ArrayList<>(path);
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof UriResourceCount) {
            parts.remove(parts.size() - 1);
        }
        if (parts.isEmpty() || !(parts.get(0) instanceof UriResourceEntitySet first)) {
            throw new ODataApplicationException("Only entity set requests are supported",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        Execution root = execute(first.getEntitySet().getName(), custom, req.getRawQueryPath());
        executions.put(root.query().name(), root);
        EdmEntitySet set = first.getEntitySet();
        QueryDefinition query = root.query();
        List<Entity> entities = EntityMapper.toEntities(query, root.table());
        Entity single = null;
        if (!first.getKeyPredicates().isEmpty()) {
            single = byKey(entities, first.getKeyPredicates(), set.getName());
            entities = null;
        }
        EvaluationContext navigation = navigationResolver(custom);
        for (int i = 1; i < parts.size(); i++) {
            if (!(parts.get(i) instanceof UriResourceNavigation nav)) {
                throw new ODataApplicationException("Path segment '" + parts.get(i).getSegmentValue() + "' is not supported",
                        HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
            }
            if (single == null) {
                // Olingo only lets navigations follow a single entity; null = a to-one navigation found nothing.
                return new Resolved(root, query, set, null, null);
            }
            String navName = nav.getProperty().getName();
            List<Entity> targets = navigation.navigate(single, navName);
            EdmBindingTarget target = set.getRelatedBindingTarget(navName);
            if (!(target instanceof EdmEntitySet targetSet)) {
                throw new ODataApplicationException("Navigation property '" + navName + "' has no target entity set",
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
            }
            set = targetSet;
            query = executions.get(set.getName()).query();
            if (nav.isCollection()) {
                if (nav.getKeyPredicates().isEmpty()) {
                    entities = targets;
                    single = null;
                } else {
                    single = byKey(targets, nav.getKeyPredicates(), set.getName());
                    entities = null;
                }
            } else {
                single = targets.isEmpty() ? null : targets.get(0);
                entities = null;
            }
        }
        return new Resolved(root, query, set, entities, single);
    }

    /** The entity whose key properties equal the key predicates; 404 if there is none. */
    private static Entity byKey(List<Entity> entities, List<UriParameter> keys, String setName)
            throws ODataApplicationException {
        List<Object> wanted = new ArrayList<>();
        for (UriParameter k : keys) wanted.add(keyLiteral(k.getText()));
        for (Entity e : entities) {
            boolean match = true;
            for (int i = 0; i < keys.size() && match; i++) {
                Property p = e.getProperty(keys.get(i).getName());
                match = p != null && p.getValue() != null && RelationJoin.normalize(p.getValue()).equals(wanted.get(i));
            }
            if (match) return e;
        }
        throw new ODataApplicationException("No entity in '" + setName + "' with this key",
                HttpStatusCode.NOT_FOUND.getStatusCode(), LOCALE);
    }

    /** Key literal from the URL in the form {@link RelationJoin#normalize} produces for entity values. */
    static Object keyLiteral(String text) {
        if (text == null) return null;
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        try {
            return RelationJoin.normalize(new java.math.BigDecimal(text));
        } catch (NumberFormatException e) {
            return text;
        }
    }

    // --- read-only feed ------------------------------------------------------------------------------------------

    private static ODataApplicationException readOnly() {
        return new ODataApplicationException("The feed is read-only", HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode(), LOCALE);
    }

    @Override
    public void createEntity(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                             ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void updateEntity(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                             ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deleteEntity(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void updatePrimitive(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                                ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deletePrimitive(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void updatePrimitiveValue(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                                     ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deletePrimitiveValue(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
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
            if (item.getLevelsOption() != null || item.getApplyOption() != null) {
                throw new ODataApplicationException("$levels and $apply are not supported inside $expand",
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
                        EntityCollection c = QueryOptionsEvaluator.apply(matches, item.getSearchOption(), item.getFilterOption(),
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
    private EvaluationContext navigationResolver(Map<String, String> custom) {
        return new EvaluationContext() {
            @Override
            public List<Entity> navigate(Entity source, String navName) throws ODataApplicationException {
                return QueryEntityCollectionProcessor.this.navigate(source, navName, custom);
            }

            @Override
            public AliasQueryOption alias(String name) {
                return aliases.get(name.startsWith("@") ? name : "@" + name);
            }
        };
    }

    private List<Entity> navigate(Entity source, String navName, Map<String, String> custom) throws ODataApplicationException {
        {
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
        }
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

    private static void rejectUnsupportedOptions(UriInfo uriInfo) throws ODataApplicationException {
        if (uriInfo.getApplyOption() != null && uriInfo.getExpandOption() != null) {
            throw new ODataApplicationException("$expand together with $apply is not supported",
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
