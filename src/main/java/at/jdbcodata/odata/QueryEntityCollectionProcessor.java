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
import at.jdbcodata.datasource.SessionSettings;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.identity.QueryNotVisibleException;
import at.jdbcodata.query.ParameterBinder;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.ResultTooLargeException;
import at.jdbcodata.query.SqlDialect;
import at.jdbcodata.query.SqlWrapper;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
import org.apache.olingo.server.api.processor.ReferenceCollectionProcessor;
import org.apache.olingo.server.api.processor.ReferenceProcessor;
import org.apache.olingo.server.api.serializer.ReferenceCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ReferenceSerializerOptions;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.EntitySerializerOptions;
import org.apache.olingo.server.api.serializer.PrimitiveSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriInfoKind;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceCount;
import org.apache.olingo.server.api.uri.UriResourceKind;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.UriResourceRef;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceFunction;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.CustomQueryOption;
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
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
        PrimitiveValueProcessor, BatchProcessor, ReferenceCollectionProcessor, ReferenceProcessor,
        org.apache.olingo.server.api.processor.CountPrimitiveCollectionProcessor,
        org.apache.olingo.server.api.processor.CountComplexCollectionProcessor,
        org.apache.olingo.server.api.processor.ComplexProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueryEntityCollectionProcessor.class);
    private static final Locale LOCALE = Locale.ENGLISH;
    static final int MAX_BATCH_REQUESTS = 100;
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
        begin(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        if (uriInfo.getDeltaTokenOption() != null || wantsTrackChanges(req)) {
            // snapshots and comparisons need the current rows, not a cached result (the cache is refreshed)
            custom = new LinkedHashMap<>(custom);
            custom.put(REFRESH_OPTION, "true");
        }
        Resolved r = resolvePushDown(uriInfo, custom, req, false);
        if (r == null) r = resolve(uriInfo.getUriResourceParts(), custom, req);
        if (r.entities() == null) {
            throw new ODataApplicationException("The path does not address a collection",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        EdmEntitySet entitySet = r.entitySet();
        if (uriInfo.getDeltaTokenOption() != null && (custom.containsKey(EXTENDED_APPLY) || uriInfo.getApplyOption() != null
                || hasNestedApply(uriInfo.getExpandOption()))) {
            throw new ODataApplicationException("Delta links are not available for $apply results",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        if (custom.containsKey(EXTENDED_APPLY)) {
            writeExtendedResult(req, response, uriInfo, r, custom, responseFormat);
            return;
        }
        if (uriInfo.getApplyOption() != null || hasNestedApply(uriInfo.getExpandOption())) {
            writeDynamicResult(req, response, uriInfo, r, custom, responseFormat);
            return;
        }
        EntityCollection collection = r.pushDown() != null ? afterPushDown(r, uriInfo, navigationResolver(custom), response)
                : QueryOptionsEvaluator.apply(r.entities(), uriInfo, navigationResolver(custom));
        String deltaScope = deltaScope(req, uriInfo, collection.getEntities());
        if (uriInfo.getDeltaTokenOption() != null) {
            writeDelta(req, response, uriInfo, r, collection.getEntities(), custom, responseFormat, deltaScope);
            return;
        }
        boolean track = deltaScope != null && wantsTrackChanges(req);
        List<Entity> all = track ? new ArrayList<>(collection.getEntities()) : null;
        Page page = page(req, uriInfo, collection.getEntities());
        collection.getEntities().clear();
        collection.getEntities().addAll(page.rows());
        if (page.nextLink() != null) collection.setNext(java.net.URI.create(page.nextLink()));
        if (track && page.nextLink() == null) {
            // the delta link belongs on the last page; its snapshot is the whole result
            String token = support.deltaTracker().register(request.identity().caller(), deltaScope, all);
            collection.setDeltaLink(java.net.URI.create(deltaLink(req, deltaScope, token)));
        }
        applyPreference(response, page, track);
        // Only the delivered page is expanded, after $filter/$orderby/$skip/$top and paging.
        if (uriInfo.getExpandOption() != null) {
            expand(collection.getEntities(), r.query(), uriInfo.getExpandOption(), custom, 1);
        }
        applySelectOptions(collection.getEntities(), custom);

        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), contextExpand(uriInfo.getExpandOption()), uriInfo.getSelectOption());
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(entitySet).selectList(selectList).build();
        EntityCollectionSerializerOptions options = EntityCollectionSerializerOptions.with()
                .id(req.getRawBaseUri() + "/" + entitySet.getName())
                .contextURL(contextUrl)
                .select(uriInfo.getSelectOption())
                .expand(serializerExpand(uriInfo.getExpandOption(), entitySet.getEntityType()))
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
        Resolved pushed = resolvePushDown(uriInfo, custom, req, true);
        if (pushed != null) {
            long n;
            if (pushed.pushDown().countOnly()) {
                response.setHeader("X-Push-Down", pushed.pushDown().summary());
                n = pushed.root().pushedCount();
            } else {
                n = afterPushDown(pushed, uriInfo, navigationResolver(custom), response).getCount();
            }
            respond(req, response, new ByteArrayInputStream(String.valueOf(n).getBytes(StandardCharsets.UTF_8)),
                    ContentType.TEXT_PLAIN.toContentTypeString(), pushed.root());
            return;
        }
        Resolved r = resolve(uriInfo.getUriResourceParts(), custom, req);
        if (r.entities() == null) {
            throw new ODataApplicationException("$count needs a collection", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        if (custom.containsKey(EXTENDED_APPLY)) {
            Extended x = extended(uriInfo, r, custom);
            byte[] body = String.valueOf(x.filtered().getCount()).getBytes(StandardCharsets.UTF_8);
            respond(req, response, new ByteArrayInputStream(body), ContentType.TEXT_PLAIN.toContentTypeString(), r.root());
            return;
        }
        List<Entity> input = uriInfo.getApplyOption() == null ? r.entities()
                : new ApplyEvaluator(navigationResolver(custom)).apply(r.entities(), uriInfo.getApplyOption());
        EntityCollection collection = QueryOptionsEvaluator.apply(input, uriInfo, navigationResolver(custom));
        byte[] body = String.valueOf(collection.getCount()).getBytes(StandardCharsets.UTF_8);
        respond(req, response, new ByteArrayInputStream(body), ContentType.TEXT_PLAIN.toContentTypeString(), r.root());
    }

    /**
     * Results with dynamic properties: {@code $apply} (and {@code $compute}, rewritten to it) first, then
     * $search/$filter/$orderby/$skip/$top/$count on the transformed rows (which may refer to aliases), paging,
     * {@code $expand} (also with nested {@code $apply}) and $select. Written by {@link DynamicResultWriter}.
     */
    private void writeDynamicResult(ODataRequest req, ODataResponse response, UriInfo uriInfo, Resolved r,
                                    Map<String, String> custom, ContentType responseFormat) throws ODataApplicationException {
        requireJson(responseFormat);
        EvaluationContext navigation = navigationResolver(custom);
        List<Entity> applied;
        if (r.pushDown() != null && r.pushDown().applyPushed()) {
            response.setHeader("X-Push-Down", r.pushDown().summary());
            applied = r.pushDown().remainingApply() == null ? r.entities()
                    : new ApplyEvaluator(navigation).apply(r.entities(), r.pushDown().remainingApply());
        } else {
            applied = uriInfo.getApplyOption() == null ? r.entities()
                    : new ApplyEvaluator(navigation).apply(r.entities(), uriInfo.getApplyOption());
        }
        EntityCollection filtered = QueryOptionsEvaluator.apply(applied, uriInfo, navigation);
        Page page = page(req, uriInfo, filtered.getEntities());
        applyPreference(response, page);
        if (uriInfo.getExpandOption() != null) {
            requireTyped(page.rows());
            expand(page.rows(), r.query(), uriInfo.getExpandOption(), custom, 1);
        }
        applySelectOptions(page.rows(), custom);
        java.util.Set<String> select = DynamicResultWriter.selected(uriInfo.getSelectOption());
        boolean count = uriInfo.getCountOption() != null && uriInfo.getCountOption().getValue();
        String context = serviceRoot(req) + "$metadata#" + r.entitySet().getName() + "("
                + DynamicResultWriter.contextSelectList(page.rows(), select, uriInfo.getExpandOption()) + ")";
        DynamicResultWriter.Format format = DynamicResultWriter.Format.of(responseFormat);
        byte[] body = DynamicResultWriter.collection(context, page.rows(), count ? filtered.getCount() : null,
                page.nextLink(), select, uriInfo.getExpandOption(), null, format);
        respond(req, response, new ByteArrayInputStream(body), format.contentType(), r.root());
    }

    /** {@code $select=Tags($top=2)}: options of collection-valued select items (see {@link SelectOptions}). */
    private void applySelectOptions(List<Entity> rows, Map<String, String> custom) throws ODataApplicationException {
        if (SelectOptions.present(custom)) new SelectOptions(serviceMetadata.getEdm(), odata, aliases).apply(rows, custom);
    }

    // --- extended $apply (join, outerjoin, nest, ancestors, descendants, traverse) -----------------------------------

    static final String EXTENDED_APPLY = QueryRewriter.EXTENDED_PREFIX + "apply";

    /** Result of an extended $apply with the options the servlet moved along with it. */
    private record Extended(EntityCollection filtered, org.apache.olingo.server.api.uri.queryoption.SelectOption select,
                            ExpandOption expand) {
    }

    private Extended extended(UriInfo uriInfo, Resolved r, Map<String, String> custom) throws ODataApplicationException {
        EvaluationContext context = navigationResolver(custom);
        ExtendedApply.Host host = new ExtendedApply.Host() {
            @Override
            public EvaluationContext context() {
                return context;
            }

            @Override
            public org.apache.olingo.server.api.uri.queryoption.ApplyOption parseApply(String text,
                    org.apache.olingo.commons.api.edm.EdmStructuredType type) throws ODataApplicationException {
                org.apache.olingo.commons.api.edm.EdmStructuredType dynamic =
                        type instanceof org.apache.olingo.server.core.uri.queryoption.apply.DynamicStructuredType ? type
                                : new org.apache.olingo.server.core.uri.queryoption.apply.DynamicStructuredType(type);
                return parse(text, t -> new org.apache.olingo.server.core.uri.parser.ApplyParser(serviceMetadata.getEdm(), odata)
                        .parse(t, dynamic, null, aliases));
            }

            @Override
            public EdmEntityType entityType(String entitySet) {
                EdmEntitySet set = serviceMetadata.getEdm().getEntityContainer().getEntitySet(entitySet);
                return set == null ? null : set.getEntityType();
            }

            @Override
            public org.apache.olingo.commons.api.edm.Edm edm() {
                return serviceMetadata.getEdm();
            }
        };
        ExtendedApply apply = new ExtendedApply(host, r.entitySet().getEntityType());
        List<Entity> rows = apply.run(r.entities(), custom.get(EXTENDED_APPLY));
        org.apache.olingo.commons.api.edm.EdmStructuredType type = apply.type();
        String f = custom.get(QueryRewriter.EXTENDED_PREFIX + "filter");
        String o = custom.get(QueryRewriter.EXTENDED_PREFIX + "orderby");
        String sel = custom.get(QueryRewriter.EXTENDED_PREFIX + "select");
        String exp = custom.get(QueryRewriter.EXTENDED_PREFIX + "expand");
        org.apache.olingo.server.api.uri.queryoption.FilterOption filter = f == null ? null
                : parse(f, t -> new org.apache.olingo.server.core.uri.parser.FilterParser(serviceMetadata.getEdm(), odata).parse(t, type, null, aliases));
        org.apache.olingo.server.api.uri.queryoption.OrderByOption orderBy = o == null ? null
                : parse(o, t -> new org.apache.olingo.server.core.uri.parser.OrderByParser(serviceMetadata.getEdm(), odata).parse(t, type, null, aliases));
        org.apache.olingo.server.api.uri.queryoption.SelectOption select = sel == null ? null
                : parse(sel, t -> new org.apache.olingo.server.core.uri.parser.SelectParser(serviceMetadata.getEdm()).parse(t, type, true));
        ExpandOption expand = exp == null ? null
                : parse(exp, t -> new org.apache.olingo.server.core.uri.parser.ExpandParser(serviceMetadata.getEdm(), odata, aliases, null).parse(t, type));
        EntityCollection filtered = QueryOptionsEvaluator.apply(rows, uriInfo.getSearchOption(), filter, orderBy,
                uriInfo.getSkipOption(), uriInfo.getTopOption(), context);
        return new Extended(filtered, select, expand);
    }

    @FunctionalInterface
    private interface ParseStep<T> {
        T parse(org.apache.olingo.server.core.uri.parser.UriTokenizer tokenizer)
                throws org.apache.olingo.server.core.uri.parser.UriParserException,
                org.apache.olingo.server.core.uri.validator.UriValidationException;
    }

    private static <T> T parse(String text, ParseStep<T> step) throws ODataApplicationException {
        try {
            org.apache.olingo.server.core.uri.parser.UriTokenizer tokenizer = new org.apache.olingo.server.core.uri.parser.UriTokenizer(text);
            T result = step.parse(tokenizer);
            if (!tokenizer.next(org.apache.olingo.server.core.uri.parser.UriTokenizer.TokenKind.EOF)) {
                throw new ODataApplicationException("Unexpected text in '" + text + "'", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
            }
            return result;
        } catch (org.apache.olingo.server.core.uri.parser.UriParserException
                 | org.apache.olingo.server.core.uri.validator.UriValidationException e) {
            throw new ODataApplicationException("Cannot parse '" + text + "': " + e.getMessage(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
    }

    private void writeExtendedResult(ODataRequest req, ODataResponse response, UriInfo uriInfo, Resolved r,
                                     Map<String, String> custom, ContentType responseFormat) throws ODataApplicationException {
        requireJson(responseFormat);
        Extended x = extended(uriInfo, r, custom);
        Page page = page(req, uriInfo, x.filtered().getEntities());
        applyPreference(response, page);
        if (x.expand() != null) {
            requireTyped(page.rows());
            expand(page.rows(), r.query(), x.expand(), custom, 1);
        }
        applySelectOptions(page.rows(), custom);
        java.util.Set<String> select = DynamicResultWriter.selected(x.select());
        boolean count = uriInfo.getCountOption() != null && uriInfo.getCountOption().getValue();
        String context = serviceRoot(req) + "$metadata#" + r.entitySet().getName() + "("
                + DynamicResultWriter.contextSelectList(page.rows(), select, x.expand()) + ")";
        DynamicResultWriter.Format format = DynamicResultWriter.Format.of(responseFormat);
        byte[] body = DynamicResultWriter.collection(context, page.rows(), count ? x.filtered().getCount() : null,
                page.nextLink(), select, x.expand(), null, format);
        respond(req, response, new ByteArrayInputStream(body), format.contentType(), r.root());
    }

    private static void requireJson(ContentType responseFormat) throws ODataApplicationException {
        if (!responseFormat.isCompatible(ContentType.APPLICATION_JSON)) {
            throw new ODataApplicationException("This result is available as JSON only",
                    HttpStatusCode.NOT_ACCEPTABLE.getStatusCode(), LOCALE);
        }
    }

    /** $expand needs rows that still are entities of a query (not aggregated by $apply). */
    private static void requireTyped(List<Entity> rows) throws ODataApplicationException {
        for (Entity e : rows) {
            if (e.getType() == null) {
                throw new ODataApplicationException("$expand is only possible when $apply keeps the entities "
                        + "(filter, search, orderby, top, skip, compute), not after aggregate or groupby",
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
            }
        }
    }

    /**
     * Expansions Olingo's serializer cannot write: a nested {@code $apply} (dynamic properties) or {@code $levels}
     * (Olingo does not follow the attached links below the first level).
     */
    static boolean hasNestedApply(ExpandOption expand) {
        if (expand == null) return false;
        for (ExpandItem item : expand.getExpandItems()) {
            if (item.getApplyOption() != null || item.getLevelsOption() != null || hasNestedApply(item.getExpandOption())) {
                return true;
            }
        }
        return false;
    }

    // --- push-down --------------------------------------------------------------------------------------------

    /**
     * What went to the database: the wrapper, the $filter conjuncts that stay in memory, and a summary for the
     * {@code X-Push-Down} header.
     */
    private record PushDown(SqlWrapper wrapper, List<Expression> residual, boolean countOnly, String summary,
                            boolean searchPushed, boolean applyPushed,
                            org.apache.olingo.server.api.uri.queryoption.ApplyOption remainingApply) {
        PushDown(SqlWrapper wrapper, List<Expression> residual, boolean countOnly, String summary) {
            this(wrapper, residual, countOnly, summary, false, false, null);
        }
    }

    /**
     * Plans and runs a push-down for {@code GET EntitySet} or {@code EntitySet/$count} of a query with push-down
     * switched on and real key columns. Returns {@code null} when nothing can be pushed; the caller then takes the
     * in-memory path. $filter is split at top-level {@code and}: translatable conjuncts become the WHERE clause, the
     * rest stays in memory. $orderby, $skip and $top go to the database only when the whole filter did and there is
     * no $search, because otherwise the database would slice before the in-memory part filters.
     */
    private Resolved resolvePushDown(UriInfo uriInfo, Map<String, String> custom, ODataRequest req, boolean countEndpoint)
            throws ODataApplicationException {
        List<UriResource> parts = new ArrayList<>(uriInfo.getUriResourceParts());
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof UriResourceCount) parts.remove(parts.size() - 1);
        if (parts.size() != 1) return null;
        EdmEntitySet rootSet = rootSet(parts.get(0));
        boolean keyed = parts.get(0) instanceof UriResourceEntitySet es ? !es.getKeyPredicates().isEmpty()
                : parts.get(0) instanceof UriResourceFunction fn && !fn.getKeyPredicates().isEmpty();
        if (rootSet == null || keyed) return null;
        if (hasNestedApply(uriInfo.getExpandOption()) || custom.containsKey(EXTENDED_APPLY)) return null;
        QueryDefinition query = support.queryService().findEnabledByName(rootSet.getName()).orElse(null);
        if (query == null || !query.pushDown() || query.keyMode() != KeyMode.COLUMNS) return null;
        List<String> keys = query.columns().stream().filter(c -> c.visible() && c.key()).map(QueryColumn::propertyName).toList();
        if (keys.isEmpty()) return null;
        Datasource ds = support.datasourceService().findById(query.datasourceId()).orElse(null);
        SqlDialect dialect = ds == null ? null : SqlDialect.of(ds.jdbcUrl());
        if (dialect == null) return null;

        SqlFilterTranslator translator = new SqlFilterTranslator(query, dialect, navigationResolver(custom));
        if (uriInfo.getApplyOption() != null) return pushApply(uriInfo, custom, req, query, rootSet, dialect, translator);
        List<String> where = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        List<Expression> residual = new ArrayList<>();
        if (uriInfo.getFilterOption() != null) {
            for (Expression conjunct : conjuncts(uriInfo.getFilterOption().getExpression())) {
                try {
                    SqlFilterTranslator.Sql sql = translator.predicate(conjunct);
                    where.add(sql.text());
                    binds.addAll(sql.binds());
                } catch (SqlFilterTranslator.Untranslatable e) {
                    residual.add(conjunct);
                    try {
                        // a search the database can only narrow down: pre-filter there, decide in memory
                        SqlFilterTranslator.Sql pre = translator.prefilter(conjunct);
                        where.add(pre.text());
                        binds.addAll(pre.binds());
                    } catch (SqlFilterTranslator.Untranslatable ignored) {
                        // stays in memory only
                    }
                }
            }
        }
        boolean searchPushed = false;
        boolean searchPrefiltered = false;
        if (uriInfo.getSearchOption() != null && uriInfo.getSearchOption().getSearchExpression() != null) {
            try {
                SqlFilterTranslator.SearchSql sql = translator.searchFilter(uriInfo.getSearchOption().getSearchExpression());
                where.add(sql.sql().text());
                binds.addAll(sql.sql().binds());
                // not exact: the database pre-filters, the in-memory search decides
                searchPushed = sql.exact();
                searchPrefiltered = !sql.exact();
            } catch (SqlFilterTranslator.Untranslatable e) {
                // stays in memory
            }
        }
        boolean complete = residual.isEmpty() && (uriInfo.getSearchOption() == null || searchPushed);
        boolean paging = !countEndpoint && complete && (uriInfo.getTopOption() != null || uriInfo.getSkipOption() != null);
        SqlFilterTranslator.Sql order = null;
        if (paging) {
            try {
                order = translator.orderBy(uriInfo.getOrderByOption(), keys);
                binds.addAll(order.binds());
            } catch (SqlFilterTranslator.Untranslatable e) {
                paging = false;
            }
        }
        boolean countOnly = countEndpoint && complete;
        if (where.isEmpty() && !paging && !countOnly) return null;
        SqlWrapper wrapper = new SqlWrapper(dialect, String.join(" AND ", where),
                paging ? binds : binds.subList(0, binds.size() - (order == null ? 0 : order.binds().size())),
                paging ? order.text() : null,
                paging ? (uriInfo.getSkipOption() == null ? 0 : uriInfo.getSkipOption().getValue()) : null,
                paging && uriInfo.getTopOption() != null ? uriInfo.getTopOption().getValue() : null);
        boolean withCount = paging && uriInfo.getCountOption() != null && uriInfo.getCountOption().getValue();
        Execution root;
        try {
            root = execute(query.name(), custom, req.getRawQueryPath(), wrapper, countOnly, withCount);
        } catch (ODataApplicationException e) {
            if (e.getStatusCode() != HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode()) throw e;
            // e.g. a type mismatch the database rejects: answer from the full result instead
            log.warn("Push-down for '{}' failed, evaluating in memory: {}", query.name(), e.getMessage());
            return null;
        }
        executions.put(query.name(), root);
        List<String> summary = new ArrayList<>();
        if (uriInfo.getFilterOption() != null && where.size() > (searchPushed || searchPrefiltered ? 1 : 0)) {
            summary.add(residual.isEmpty() ? "filter" : "filter(partial)");
        }
        if (searchPushed) summary.add("search");
        if (searchPrefiltered) summary.add("search(partial)");
        if (paging) {
            if (uriInfo.getOrderByOption() != null) summary.add("orderby");
            if (uriInfo.getSkipOption() != null) summary.add("skip");
            if (uriInfo.getTopOption() != null) summary.add("top");
        }
        if (countOnly || withCount) summary.add("count");
        List<Entity> entities = countOnly ? List.of() : EntityMapper.toEntities(query, root.table());
        return new Resolved(root, query, rootSet, entities, null,
                new PushDown(wrapper, residual, countOnly, String.join(",", summary), searchPushed, false, null));
    }

    /**
     * $apply: leading {@code filter}/{@code search} steps become the WHERE clause, a following simple
     * {@code aggregate} or {@code groupby} the SELECT list and GROUP BY; the remaining steps and all options after
     * $apply run in memory on the (much smaller) result.
     */
    private Resolved pushApply(UriInfo uriInfo, Map<String, String> custom, ODataRequest req, QueryDefinition query,
                               EdmEntitySet rootSet, SqlDialect dialect, SqlFilterTranslator translator)
            throws ODataApplicationException {
        List<org.apache.olingo.server.api.uri.queryoption.ApplyItem> items = uriInfo.getApplyOption().getApplyItems();
        List<String> where = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        boolean stopped = false;
        int i = 0;
        for (; i < items.size(); i++) {
            org.apache.olingo.server.api.uri.queryoption.ApplyItem item = items.get(i);
            try {
                SqlFilterTranslator.Sql sql;
                if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Filter f) {
                    try {
                        sql = translator.predicate(f.getFilterOption().getExpression());
                    } catch (SqlFilterTranslator.Untranslatable e) {
                        SqlFilterTranslator.Sql pre = translator.prefilter(f.getFilterOption().getExpression());
                        where.add(pre.text());
                        binds.addAll(pre.binds());
                        summary.add("search(partial)");
                        stopped = true;
                        break;
                    }
                    summary.add("filter");
                } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Search se) {
                    SqlFilterTranslator.SearchSql bounded = translator.searchFilter(se.getSearchOption().getSearchExpression());
                    if (!bounded.exact()) {
                        // pre-filter in the database; the step itself stays in memory, and so does everything after it
                        where.add(bounded.sql().text());
                        binds.addAll(bounded.sql().binds());
                        summary.add("search(partial)");
                        stopped = true;
                        break;
                    }
                    sql = bounded.sql();
                    summary.add("search");
                } else {
                    break;
                }
                where.add(sql.text());
                binds.addAll(sql.binds());
            } catch (SqlFilterTranslator.Untranslatable e) {
                break;
            }
        }
        SqlFilterTranslator.Aggregation aggregation = null;
        org.apache.olingo.server.api.uri.queryoption.apply.GroupBy rollupGrandTotal = null;
        if (i < items.size() && !stopped) {
            try {
                org.apache.olingo.server.api.uri.queryoption.ApplyItem item = items.get(i);
                if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Aggregate a) {
                    aggregation = translator.aggregation(List.of(), a.getExpressions());
                    summary.add("aggregate");
                } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.GroupBy g) {
                    List<List<UriResource>> paths = new ArrayList<>();
                    boolean rollup = false;
                    for (org.apache.olingo.server.api.uri.queryoption.apply.GroupByItem gi : g.getGroupByItems()) {
                        if (gi.getRollup() != null && !gi.getRollup().isEmpty()) rollup = true;
                        else paths.add(gi.getPath());
                    }
                    List<org.apache.olingo.server.api.uri.queryoption.apply.AggregateExpression> aggs = List.of();
                    if (g.getApplyOption() != null) {
                        List<org.apache.olingo.server.api.uri.queryoption.ApplyItem> nested = g.getApplyOption().getApplyItems();
                        if (nested.size() != 1 || !(nested.get(0) instanceof org.apache.olingo.server.api.uri.queryoption.apply.Aggregate na)) {
                            throw new SqlFilterTranslator.Untranslatable("groupby with nested transformations");
                        }
                        aggs = na.getExpressions();
                    }
                    if (rollup) {
                        List<List<List<UriResource>>> groupings = ApplyEvaluator.groupings(g);
                        aggregation = translator.rollup(groupings, aggs);
                        // an empty input still has a grand total in memory; SQL would return no row for it
                        rollupGrandTotal = groupings.stream().anyMatch(List::isEmpty) ? g : null;
                    } else {
                        aggregation = translator.aggregation(paths, aggs);
                    }
                    summary.add("groupby");
                }
            } catch (SqlFilterTranslator.Untranslatable e) {
                aggregation = null;
                rollupGrandTotal = null;
            }
            if (aggregation != null) i++;
        }
        // Without aggregation: orderby, skip and top on the filtered rows (ties broken by the key, as for $orderby).
        SqlFilterTranslator.Sql rowOrder = null;
        Integer rowSkip = null;
        Integer rowTop = null;
        if (aggregation == null && !stopped) {
            List<String> keys = query.columns().stream().filter(c -> c.visible() && c.key()).map(QueryColumn::propertyName).toList();
            for (; i < items.size(); i++) {
                org.apache.olingo.server.api.uri.queryoption.ApplyItem item = items.get(i);
                try {
                    if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.OrderBy o && rowSkip == null && rowTop == null) {
                        rowOrder = translator.orderBy(o.getOrderByOption(), keys);
                        summary.add("orderby");
                    } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Skip sk && rowTop == null) {
                        rowSkip = (rowSkip == null ? 0 : rowSkip) + sk.getSkipOption().getValue();
                        summary.add("skip");
                    } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Top t) {
                        rowTop = rowTop == null ? t.getTopOption().getValue() : Math.min(rowTop, t.getTopOption().getValue());
                        summary.add("top");
                    } else {
                        break;
                    }
                } catch (SqlFilterTranslator.Untranslatable e) {
                    break;
                }
            }
            if ((rowSkip != null || rowTop != null) && rowOrder == null) {
                try {
                    rowOrder = translator.orderBy(null, keys);
                } catch (SqlFilterTranslator.Untranslatable e) {
                    return null;
                }
            }
            if (rowOrder != null) binds.addAll(rowOrder.binds());
        }
        if (where.isEmpty() && aggregation == null && rowOrder == null) return null;
        // Groups come back in the order of their first row, like the in-memory groupby.
        boolean grouped = aggregation != null && aggregation.grouped();
        String groupOrder = !grouped ? null : (aggregation.rollup() ? "jdbcodata_q." + SqlWrapper.LEVEL + ", " : "")
                + "MIN(jdbcodata_q." + SqlWrapper.ROW_NUMBER + ")";
        String order = rowOrder != null && !rowOrder.text().isBlank() ? rowOrder.text() : groupOrder;
        boolean rowPaging = rowSkip != null || rowTop != null;
        SqlWrapper wrapper = new SqlWrapper(dialect, String.join(" AND ", where), binds, order,
                rowPaging ? (rowSkip == null ? 0 : rowSkip) : null, rowTop,
                aggregation == null ? null : aggregation.select(), aggregation == null ? null : aggregation.groupBy(),
                null, grouped, aggregation != null && aggregation.rollup() ? aggregation.levels().size() : 0);
        if (aggregation != null && rollupGrandTotal == null) {
            PostAggregation post = postAggregation(items, i, aggregation, dialect, custom, uriInfo, wrapper);
            if (post != null) {
                wrapper = post.wrapper();
                i = post.next();
                summary.addAll(post.summary());
            }
        }
        Execution root;
        try {
            root = execute(query.name(), custom, req.getRawQueryPath(), wrapper, false, false);
        } catch (ODataApplicationException e) {
            if (e.getStatusCode() != HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode()) throw e;
            log.warn("Push-down of $apply for '{}' failed, evaluating in memory: {}", query.name(), e.getMessage());
            return null;
        }
        executions.put(query.name(), root);
        List<Entity> rows = aggregation == null ? EntityMapper.toEntities(query, root.table())
                : aggregatedRows(aggregation, root.table());
        if (rollupGrandTotal != null && rows.isEmpty()) {
            // nothing matched: the in-memory groupby of no rows gives the grand total row(s) exactly
            org.apache.olingo.server.core.uri.queryoption.ApplyOptionImpl only = new org.apache.olingo.server.core.uri.queryoption.ApplyOptionImpl();
            only.add(rollupGrandTotal);
            rows = new ApplyEvaluator(navigationResolver(custom)).apply(List.of(), only);
        }
        org.apache.olingo.server.core.uri.queryoption.ApplyOptionImpl remaining = new org.apache.olingo.server.core.uri.queryoption.ApplyOptionImpl();
        for (int k = i; k < items.size(); k++) remaining.add(items.get(k));
        return new Resolved(root, query, rootSet, rows, null, new PushDown(wrapper, List.of(), false,
                "apply:" + String.join(",", summary), false, true, remaining.getApplyItems().isEmpty() ? null : remaining));
    }

    /** Steps after a pushed aggregation that went to the database as well, wrapped around it. */
    private record PostAggregation(SqlWrapper wrapper, int next, List<String> summary) {
    }

    /**
     * {@code filter}, {@code orderby}, {@code skip} and {@code top} directly after a pushed groupby/aggregate run as
     * an outer SELECT over the aggregated rows (in this order; a filter after paging stays in memory). Groups keep
     * the order of their first row, which also breaks ties of orderby, exactly as in memory. When the whole $apply went to the database,
     * the translatable conjuncts of $filter are added too; the in-memory $filter repeats them harmlessly.
     */
    private PostAggregation postAggregation(List<org.apache.olingo.server.api.uri.queryoption.ApplyItem> items, int from,
                                            SqlFilterTranslator.Aggregation aggregation, SqlDialect dialect,
                                            Map<String, String> custom, UriInfo uriInfo, SqlWrapper inner) {
        SqlFilterTranslator post = new SqlFilterTranslator(aggregation.columns(), dialect, navigationResolver(custom));
        // ties are broken by the position of the group's first row, as the stable in-memory sort does
        List<String> groups = aggregation.order();
        List<String> where = new ArrayList<>();
        List<Object> whereBinds = new ArrayList<>();
        SqlFilterTranslator.Sql order = null;
        Integer skip = null;
        Integer top = null;
        List<String> summary = new ArrayList<>();
        int i = from;
        for (; i < items.size(); i++) {
            org.apache.olingo.server.api.uri.queryoption.ApplyItem item = items.get(i);
            try {
                if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Filter f && order == null
                        && skip == null && top == null) {
                    SqlFilterTranslator.Sql sql;
                    try {
                        sql = post.predicate(f.getFilterOption().getExpression());
                    } catch (SqlFilterTranslator.Untranslatable e) {
                        // e.g. a condition on an average: the database narrows down, the step stays in memory
                        SqlFilterTranslator.Sql pre = averagePrefilter(f.getFilterOption().getExpression(), aggregation, post, dialect);
                        if (pre == null) throw e;
                        where.add(pre.text());
                        whereBinds.addAll(pre.binds());
                        summary.add("filter(partial)");
                        break;
                    }
                    where.add(sql.text());
                    whereBinds.addAll(sql.binds());
                    summary.add("filter");
                } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.OrderBy o && skip == null
                        && top == null) {
                    order = post.orderBy(o.getOrderByOption(), groups);
                    summary.add("orderby");
                } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Skip sk && top == null
                        && !groups.isEmpty()) {
                    skip = (skip == null ? 0 : skip) + sk.getSkipOption().getValue();
                    summary.add("skip");
                } else if (item instanceof org.apache.olingo.server.api.uri.queryoption.apply.Top t && !groups.isEmpty()) {
                    top = top == null ? t.getTopOption().getValue() : Math.min(top, t.getTopOption().getValue());
                    summary.add("top");
                } else {
                    break;
                }
            } catch (SqlFilterTranslator.Untranslatable e) {
                break;
            }
        }
        if (i == items.size() && uriInfo.getFilterOption() != null && skip == null && top == null) {
            for (Expression conjunct : conjuncts(uriInfo.getFilterOption().getExpression())) {
                try {
                    SqlFilterTranslator.Sql sql = post.predicate(conjunct);
                    where.add(sql.text());
                    whereBinds.addAll(sql.binds());
                    if (!summary.contains("$filter")) summary.add("$filter");
                } catch (SqlFilterTranslator.Untranslatable e) {
                    // stays in memory only
                }
            }
        }
        if (summary.isEmpty()) return null;
        if (order == null && (skip != null || top != null || !groups.isEmpty())) {
            try {
                order = post.orderBy(null, groups);
            } catch (SqlFilterTranslator.Untranslatable e) {
                return null;
            }
        }
        List<Object> binds = new ArrayList<>(whereBinds);
        if (order != null) binds.addAll(order.binds());
        boolean paging = skip != null || top != null;
        SqlWrapper wrapper = new SqlWrapper(dialect, String.join(" AND ", where), binds,
                order == null || order.text().isBlank() ? null : order.text(), paging ? (skip == null ? 0 : skip) : null, top,
                null, null, new SqlWrapper(dialect, inner.where(), inner.whereBinds(), null, null, null, inner.select(),
                        inner.groupBy(), null, inner.numbered(), inner.levels())); // no ORDER BY in the subquery
        return new PostAggregation(wrapper, i, summary);
    }

    /** Rounding tolerance for averages: in memory they are divided with 10 decimals (half up). */
    private static final java.math.BigDecimal AVERAGE_TOLERANCE = new java.math.BigDecimal("1E-9");

    /**
     * A condition that keeps at least every row the filter keeps, for filters on averages, which are divided in
     * Java: {@code A gt L} becomes {@code COUNT > 0 AND SUM >= (L - ε)·COUNT}, {@code lt}/{@code le} use
     * {@code (L + ε)}, {@code eq} both; other translatable conjuncts are taken exactly, the rest is dropped (which
     * only widens the condition). {@code null} when nothing narrows the rows.
     */
    private static SqlFilterTranslator.Sql averagePrefilter(Expression filter, SqlFilterTranslator.Aggregation aggregation,
                                                            SqlFilterTranslator post, SqlDialect dialect) {
        List<String> parts = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        for (Expression conjunct : conjuncts(filter)) {
            try {
                SqlFilterTranslator.Sql exact = post.predicate(conjunct);
                parts.add(exact.text());
                binds.addAll(exact.binds());
                continue;
            } catch (SqlFilterTranslator.Untranslatable e) {
                // maybe an average comparison
            }
            if (!(conjunct instanceof org.apache.olingo.server.api.uri.queryoption.expression.Binary b)) continue;
            org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind op = b.getOperator();
            Expression left = b.getLeftOperand();
            Expression right = b.getRightOperand();
            if (left instanceof org.apache.olingo.server.api.uri.queryoption.expression.Literal) {
                Expression t = left;
                left = right;
                right = t;
                op = switch (op) {
                    case GT -> org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.LT;
                    case GE -> org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.LE;
                    case LT -> org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.GT;
                    case LE -> org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.GE;
                    default -> op;
                };
            }
            if (!(left instanceof org.apache.olingo.server.api.uri.queryoption.expression.Member m)
                    || !(right instanceof org.apache.olingo.server.api.uri.queryoption.expression.Literal lit)) continue;
            List<UriResource> path = m.getResourcePath().getUriResourceParts();
            if (path.size() != 1) continue;
            SqlFilterTranslator.Slot slot = aggregation.slots().stream()
                    .filter(x -> x.kind().equals("average") && x.name().equals(path.get(0).getSegmentValue())).findFirst().orElse(null);
            java.math.BigDecimal limit;
            try {
                limit = new EntityExpressionVisitor(new Entity()).visitLiteral(lit) instanceof java.math.BigDecimal bd ? bd : null;
            } catch (org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException | ODataApplicationException e) {
                limit = null;
            }
            if (slot == null || limit == null) continue;
            String sum = "jdbcodata_q." + dialect.quote(slot.label() + "s");
            String count = "jdbcodata_q." + dialect.quote(slot.label() + "c");
            boolean lower = op == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.GT
                    || op == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.GE
                    || op == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.EQ;
            boolean upper = op == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.LT
                    || op == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.LE
                    || op == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.EQ;
            if (!lower && !upper) continue;
            StringBuilder c = new StringBuilder("(" + count + " > 0");
            if (lower) {
                c.append(" AND ").append(sum).append(" >= ? * ").append(count);
                binds.add(limit.subtract(AVERAGE_TOLERANCE));
            }
            if (upper) {
                c.append(" AND ").append(sum).append(" <= ? * ").append(count);
                binds.add(limit.add(AVERAGE_TOLERANCE));
            }
            parts.add(c.append(")").toString());
        }
        return parts.isEmpty() ? null : new SqlFilterTranslator.Sql(String.join(" AND ", parts), binds);
    }

    /** Rows of a pushed groupby/aggregate in the form the in-memory evaluation produces. */
    private static List<Entity> aggregatedRows(SqlFilterTranslator.Aggregation aggregation, at.jdbcodata.query.ResultTable table) {
        List<Entity> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            Entity e = new Entity();
            java.util.Set<String> present = null;
            if (aggregation.rollup()) {
                Object level = row[table.indexOf(SqlFilterTranslator.LEVEL)];
                present = aggregation.levels().get(((Number) level).intValue() - 1);
            }
            for (SqlFilterTranslator.Slot slot : aggregation.slots()) {
                // subtotal rows lack the rolled-up properties, as in memory
                if (present != null && slot.kind().equals("group") && !present.contains(slot.label())) continue;
                Object value;
                if (slot.kind().equals("average")) {
                    Object sum = FilterValues.canonical(row[table.indexOf(slot.label() + "s")]);
                    Object count = FilterValues.canonical(row[table.indexOf(slot.label() + "c")]);
                    value = sum == null || count == null || ((java.math.BigDecimal) count).signum() == 0 ? null
                            : ((java.math.BigDecimal) sum).divide((java.math.BigDecimal) count, 10, java.math.RoundingMode.HALF_UP)
                                    .stripTrailingZeros();
                } else {
                    value = FilterValues.canonical(row[table.indexOf(slot.label())]);
                    // MySQL's TINYINT(1) loses its boolean mapping once it is grouped
                    if ("Edm.Boolean".equals(slot.edmType()) && value instanceof java.math.BigDecimal n) value = n.signum() != 0;
                }
                e.addProperty(new Property(slot.kind().equals("group") ? slot.edmType() : null, slot.name(),
                        org.apache.olingo.commons.api.data.ValueType.PRIMITIVE, value));
            }
            out.add(e);
        }
        return out;
    }

    /** Top-level {@code and} operands of a filter expression. */
    static List<Expression> conjuncts(Expression e) {
        List<Expression> out = new ArrayList<>();
        if (e instanceof org.apache.olingo.server.api.uri.queryoption.expression.Binary b
                && b.getOperator() == org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind.AND) {
            out.addAll(conjuncts(b.getLeftOperand()));
            out.addAll(conjuncts(b.getRightOperand()));
        } else {
            out.add(e);
        }
        return out;
    }

    /** The in-memory rest after a push-down: remaining conjuncts, $search and whatever was not sliced in SQL. */
    private EntityCollection afterPushDown(Resolved r, UriInfo uriInfo, EvaluationContext context, ODataResponse response)
            throws ODataApplicationException {
        PushDown pd = r.pushDown();
        response.setHeader("X-Push-Down", pd.summary());
        List<Entity> rows = r.entities();
        if (pd.applyPushed()) {
            List<Entity> applied = pd.remainingApply() == null ? rows : new ApplyEvaluator(context).apply(rows, pd.remainingApply());
            return QueryOptionsEvaluator.apply(applied, uriInfo, context);
        }
        for (Expression e : pd.residual()) rows = QueryOptionsEvaluator.filterBy(rows, e, context);
        boolean sliced = pd.wrapper().pagingPushed();
        EntityCollection c = QueryOptionsEvaluator.apply(rows, pd.searchPushed() ? null : uriInfo.getSearchOption(), null,
                sliced ? null : uriInfo.getOrderByOption(), sliced ? null : uriInfo.getSkipOption(),
                sliced ? null : uriInfo.getTopOption(), context);
        if (sliced && r.root().pushedCount() != null) c.setCount(r.root().pushedCount().intValue());
        return c;
    }

    // --- request state, paging, ETags -------------------------------------------------------------------------

    /** Parameter aliases of the current request ({@code @name → option}). */
    private final Map<String, AliasQueryOption> aliases = new HashMap<>();

    /** {@code $root/Set(key)} results of the current request. */
    private final Map<String, Entity> rootLookups = new HashMap<>();

    /**
     * Starts a read: drops per-request state. The processor instance serves all parts of a {@code $batch}, which may
     * use different parameters, so executions and joins must not leak from one part into the next.
     */
    private void begin(UriInfo uriInfo) {
        executions.clear();
        filterJoins.clear();
        expandedEntities = 0;
        aliases.clear();
        rootLookups.clear();
        caseCache.clear();
        searches.clear();
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
        applyPreference(response, page, false);
    }

    private static void applyPreference(ODataResponse response, Page page, boolean trackChanges) {
        if (page.appliedMaxPageSize() == null && !trackChanges) return;
        PreferencesApplied.Builder applied = PreferencesApplied.with();
        if (page.appliedMaxPageSize() != null) applied.maxPageSize(page.appliedMaxPageSize());
        if (trackChanges) applied.trackChanges();
        response.setHeader(HttpHeader.PREFERENCE_APPLIED, applied.build().toValueString());
    }

    // --- change tracking (delta links) --------------------------------------------------------------------------

    private static boolean wantsTrackChanges(ODataRequest req) {
        for (String prefer : req.getHeaders(HttpHeader.PREFER) == null ? List.<String>of() : req.getHeaders(HttpHeader.PREFER)) {
            for (String p : prefer.split(",")) {
                String name = p.strip().toLowerCase(Locale.ROOT);
                if (name.equals("odata.track-changes") || name.equals("track-changes")) return true;
            }
        }
        return false;
    }

    /**
     * What a delta link stands for: the OData path and the query options except $deltatoken and $skiptoken, or
     * {@code null} when the result cannot be tracked (rows without id). With $top/$skip the snapshot is the window:
     * entities moving in or out of it are reported as new or removed.
     */
    private static String deltaScope(ODataRequest req, UriInfo uriInfo, List<Entity> rows) {
        for (Entity e : rows) if (e.getId() == null) return null;
        List<String> kept = new ArrayList<>();
        String query = req.getRawQueryPath();
        if (query != null && !query.isEmpty()) {
            for (String part : query.split("&")) {
                String name = (part.contains("=") ? part.substring(0, part.indexOf('=')) : part).toLowerCase(Locale.ROOT)
                        .replace("%24", "$");
                if (!name.equals("$deltatoken") && !name.equals("$skiptoken") && !part.isEmpty()) kept.add(part);
            }
        }
        return req.getRawODataPath() + (kept.isEmpty() ? "" : "?" + String.join("&", kept));
    }

    private static String deltaLink(ODataRequest req, String scope, String token) {
        return req.getRawBaseUri() + scope + (scope.contains("?") ? "&" : "?") + "$deltatoken=" + token;
    }

    /**
     * {@code $deltatoken}: the request is evaluated again and compared with the snapshot of the token. Entities
     * that are new or whose values changed are returned, entities that disappeared (deleted, or no longer matching
     * the filter) as deleted entities; the response carries the next delta link.
     */
    private void writeDelta(ODataRequest req, ODataResponse response, UriInfo uriInfo, Resolved r, List<Entity> current,
                            Map<String, String> custom, ContentType responseFormat, String scope)
            throws ODataApplicationException, ODataLibraryException {
        requireJson(responseFormat);
        if (scope == null) {
            throw new ODataApplicationException("Delta links need entities with ids (no $apply results)",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        String owner = request.identity().caller();
        DeltaTracker.Snapshot before = support.deltaTracker().find(uriInfo.getDeltaTokenOption().getValue(), owner, scope);
        if (before == null) {
            throw new ODataApplicationException("The delta link is unknown or has expired; load the feed again "
                    + "with Prefer: odata.track-changes", HttpStatusCode.GONE.getStatusCode(), LOCALE);
        }
        Map<String, Long> now = DeltaTracker.fingerprints(current);
        org.apache.olingo.commons.api.data.Delta delta = new org.apache.olingo.commons.api.data.Delta();
        for (Entity e : current) {
            String id = e.getId().toString();
            if (!now.get(id).equals(before.rows().get(id))) delta.getEntities().add(e);
        }
        // with $filter/$search a missing entity may just no longer match: "changed" (left the set), else "deleted"
        boolean narrowed = uriInfo.getFilterOption() != null || uriInfo.getSearchOption() != null;
        for (String id : before.rows().keySet()) {
            if (now.containsKey(id)) continue;
            org.apache.olingo.commons.api.data.DeletedEntity gone = new org.apache.olingo.commons.api.data.DeletedEntity();
            gone.setId(java.net.URI.create(id));
            gone.setReason(narrowed ? org.apache.olingo.commons.api.data.DeletedEntity.Reason.changed
                    : org.apache.olingo.commons.api.data.DeletedEntity.Reason.deleted);
            delta.getDeletedEntities().add(gone);
        }
        if (uriInfo.getExpandOption() != null) expand(delta.getEntities(), r.query(), uriInfo.getExpandOption(), custom, 1);
        String token = support.deltaTracker().register(owner, scope, current);
        delta.setDeltaLink(java.net.URI.create(deltaLink(req, scope, token)));
        applySelectOptions(delta.getEntities(), custom); // after the snapshot: it holds the complete values
        EdmEntitySet entitySet = r.entitySet();
        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), contextExpand(uriInfo.getExpandOption()), uriInfo.getSelectOption());
        // the delta serializer appends /$delta itself
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(entitySet).selectList(selectList).build();
        EntityCollectionSerializerOptions options = EntityCollectionSerializerOptions.with()
                .id(req.getRawBaseUri() + "/" + entitySet.getName())
                .contextURL(contextUrl)
                .select(uriInfo.getSelectOption())
                .expand(serializerExpand(uriInfo.getExpandOption(), entitySet.getEntityType()))
                .build();
        String maxVersion = req.getHeader("OData-MaxVersion");
        List<String> versions = maxVersion == null ? List.of("4.0") : List.of(maxVersion.strip());
        SerializerResult result = odata.createEdmDeltaSerializer(responseFormat, versions)
                .entityCollection(serviceMetadata, entitySet.getEntityType(), delta, options);
        response.setHeader(HttpHeader.PREFERENCE_APPLIED, PreferencesApplied.with().trackChanges().build().toValueString());
        respond(req, response, fixDeletedEntityContext(result.getContent(), entitySet.getName()),
                responseFormat.toContentTypeString(), r.root());
    }

    /**
     * Olingo's 4.0 delta serializer writes {@code "#Set(key)/$deletedEntity"} as context of a deleted entity; the
     * specification has {@code "#Set/$deletedEntity"} (the key is in {@code id}).
     */
    private static java.io.InputStream fixDeletedEntityContext(java.io.InputStream content, String entitySet)
            throws ODataApplicationException {
        try (java.io.InputStream in = content) {
            tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            tools.jackson.databind.JsonNode body = mapper.readTree(in);
            tools.jackson.databind.JsonNode value = body.get("value");
            if (value != null) {
                for (tools.jackson.databind.JsonNode row : value) {
                    tools.jackson.databind.JsonNode ctx = row.get("@odata.context");
                    if (ctx != null && ctx.asString().endsWith("/$deletedEntity")) {
                        ((tools.jackson.databind.node.ObjectNode) row).put("@odata.context", "#" + entitySet + "/$deletedEntity");
                    }
                }
            }
            return new ByteArrayInputStream(mapper.writeValueAsBytes(body));
        } catch (java.io.IOException | tools.jackson.core.JacksonException e) {
            throw new ODataApplicationException("Cannot write the delta response",
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), LOCALE, e);
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
        int requests = parts.stream().mapToInt(p -> p.getRequests().size()).sum();
        if (requests > MAX_BATCH_REQUESTS) {
            throw new ODataApplicationException("$batch is limited to " + MAX_BATCH_REQUESTS + " requests",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        List<ODataResponsePart> responses = new ArrayList<>();
        for (BatchRequestPart part : parts) {
            for (ODataRequest r : part.getRequests()) rewriteBatchRequest(r);
            responses.add(facade.handleBatchRequest(part));
        }
        String responseBoundary = "batch_" + UUID.randomUUID();
        response.setContent(odata.createFixedFormatSerializer().batchResponse(responses, responseBoundary));
        response.setHeader(HttpHeader.CONTENT_TYPE, ContentType.MULTIPART_MIXED + ";boundary=" + responseBoundary);
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
    }

    /**
     * Batch parts bypass the servlet, so the rewrites of {@link ODataServlet#rewrite} (key segments, filter
     * segments, $compute, case(), …) are applied here to each part's path and query.
     */
    private void rewriteBatchRequest(ODataRequest r) {
        String path = r.getRawODataPath();
        String query = r.getRawQueryPath();
        ODataServlet.Rewritten rw = ODataServlet.rewrite(r.getMethod() == null ? null : r.getMethod().name(), path,
                query == null || query.isEmpty() ? null : query, serviceMetadata.getEdm());
        String newQuery = rw.query() == null ? "" : rw.query();
        if (rw.path().equals(path) && newQuery.equals(query == null ? "" : query)) return;
        r.setRawODataPath(rw.path());
        r.setRawQueryPath(newQuery);
        String base = r.getRawBaseUri() == null ? "" : r.getRawBaseUri();
        r.setRawRequestUri(base + rw.path() + (newQuery.isEmpty() ? "" : "?" + newQuery));
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
        EdmEntitySet entitySet = r.entitySet();
        if (custom.containsKey(QueryRewriter.SINGLE_COMPUTE)) {
            writeComputedEntity(req, response, uriInfo, r, custom, responseFormat);
            return;
        }
        if (uriInfo.getExpandOption() != null) {
            expand(List.of(r.single()), r.query(), uriInfo.getExpandOption(), custom, 1);
        }
        applySelectOptions(List.of(r.single()), custom);
        if (hasNestedApply(uriInfo.getExpandOption())) {
            requireJson(responseFormat);
            java.util.Set<String> select = DynamicResultWriter.selected(uriInfo.getSelectOption());
            String context = serviceRoot(req) + "$metadata#" + entitySet.getName() + "("
                    + DynamicResultWriter.contextSelectList(List.of(r.single()), select, uriInfo.getExpandOption()) + ")/$entity";
            DynamicResultWriter.Format format = DynamicResultWriter.Format.of(responseFormat);
            byte[] body = DynamicResultWriter.entity(context, r.single(), select, uriInfo.getExpandOption(), format);
            respond(req, response, new ByteArrayInputStream(body), format.contentType(), r.root());
            return;
        }
        String selectList = odata.createUriHelper()
                .buildContextURLSelectList(entitySet.getEntityType(), contextExpand(uriInfo.getExpandOption()), uriInfo.getSelectOption());
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(entitySet).selectList(selectList)
                .suffix(ContextURL.Suffix.ENTITY).build();
        EntitySerializerOptions options = EntitySerializerOptions.with().contextURL(contextUrl)
                .select(uriInfo.getSelectOption()).expand(serializerExpand(uriInfo.getExpandOption(), entitySet.getEntityType()))
                .build();
        SerializerResult result = odata.createSerializer(responseFormat)
                .entity(serviceMetadata, entitySet.getEntityType(), r.single(), options);
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), r.root());
    }

    /**
     * {@code Kunden(1)?$compute=…}: Olingo does not allow $apply on single entities, so the servlet passes $compute
     * (and $select) as custom options; they are parsed here in the context of the entity set and applied to the one
     * entity.
     */
    private void writeComputedEntity(ODataRequest req, ODataResponse response, UriInfo uriInfo, Resolved r,
                                     Map<String, String> custom, ContentType responseFormat) throws ODataApplicationException {
        requireJson(responseFormat);
        EdmEntitySet entitySet = r.entitySet();
        String query = "$apply=compute(" + QueryRewriter.encode(custom.get(QueryRewriter.SINGLE_COMPUTE)) + ")";
        if (custom.containsKey(QueryRewriter.SINGLE_SELECT)) {
            query += "&$select=" + QueryRewriter.encode(custom.get(QueryRewriter.SINGLE_SELECT));
        }
        UriInfo computed;
        try {
            computed = new org.apache.olingo.server.core.uri.parser.Parser(serviceMetadata.getEdm(), odata)
                    .parseUri("/" + entitySet.getName(), QueryRewriter.rewrite(query), null, req.getRawBaseUri());
        } catch (org.apache.olingo.server.core.uri.parser.UriParserException
                 | org.apache.olingo.server.core.uri.validator.UriValidationException e) {
            throw new ODataApplicationException("$compute/$select: " + e.getMessage(), HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        EvaluationContext navigation = navigationResolver(custom);
        Entity entity = new ApplyEvaluator(navigation).apply(List.of(r.single()), computed.getApplyOption()).get(0);
        if (uriInfo.getExpandOption() != null) expand(List.of(entity), r.query(), uriInfo.getExpandOption(), custom, 1);
        applySelectOptions(List.of(entity), custom);
        java.util.Set<String> select = DynamicResultWriter.selected(computed.getSelectOption());
        String context = serviceRoot(req) + "$metadata#" + entitySet.getName() + "("
                + DynamicResultWriter.contextSelectList(List.of(entity), select, uriInfo.getExpandOption()) + ")/$entity";
        DynamicResultWriter.Format format = DynamicResultWriter.Format.of(responseFormat);
        byte[] body = DynamicResultWriter.entity(context, entity, select, uriInfo.getExpandOption(), format);
        respond(req, response, new ByteArrayInputStream(body), format.contentType(), r.root());
    }

    /** {@code Kunden(1)/Name}. */
    @Override
    public void readPrimitive(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        PropertyRead p = readProperty(uriInfo, req, response);
        if (p == null) return;
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(p.entitySet()).keyPath(p.keyPath())
                .navOrPropertyPath(p.path()).build();
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

    private record PropertyRead(EdmEntitySet entitySet, String keyPath, EdmProperty edmProperty, Property property, String path) {
    }

    /** {@code Auftraege(1)/Tags}: the elements, with $filter ($it), $search, $orderby, $skip and $top applied. */
    @Override
    public void readPrimitiveCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        PropertyRead p = readCollectionProperty(uriInfo, req, response);
        if (p == null) return;
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(p.entitySet()).keyPath(p.keyPath())
                .navOrPropertyPath(p.path()).build();
        SerializerResult result = odata.createSerializer(responseFormat).primitiveCollection(serviceMetadata,
                (EdmPrimitiveType) p.edmProperty().getType(), p.property(),
                PrimitiveSerializerOptions.with().contextURL(contextUrl).build());
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), null);
    }

    /** {@code Auftraege(1)/Positionen}: like {@link #readPrimitiveCollection}, also with $select of the fields. */
    @Override
    public void readComplexCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        PropertyRead p = readCollectionProperty(uriInfo, req, response);
        if (p == null) return;
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(p.entitySet()).keyPath(p.keyPath())
                .navOrPropertyPath(p.path()).build();
        SerializerResult result = odata.createSerializer(responseFormat).complexCollection(serviceMetadata,
                (org.apache.olingo.commons.api.edm.EdmComplexType) p.edmProperty().getType(), p.property(),
                org.apache.olingo.server.api.serializer.ComplexSerializerOptions.with().contextURL(contextUrl)
                        .select(uriInfo.getSelectOption()).build());
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), null);
    }

    private PropertyRead readCollectionProperty(UriInfo uriInfo, ODataRequest req, ODataResponse response)
            throws ODataApplicationException {
        PropertyRead p = collectionElements(uriInfo, req, response);
        if (p == null) return null;
        List<?> elements = (List<?>) p.property().getValue();
        Map<Entity, Object> byRow = new java.util.IdentityHashMap<>();
        List<Entity> rows = new ArrayList<>();
        for (Object e : elements) {
            Entity row = CollectionValues.row(e);
            byRow.put(row, e);
            rows.add(row);
        }
        List<Object> kept = new ArrayList<>();
        for (Entity row : QueryOptionsEvaluator.apply(rows, uriInfo, null).getEntities()) kept.add(byRow.get(row));
        Property result = new Property(p.property().getType(), p.property().getName(), p.property().getValueType(), kept);
        return new PropertyRead(p.entitySet(), p.keyPath(), p.edmProperty(), result, p.path());
    }

    /** The collection property a path ends with (before an optional {@code /$count}). */
    private PropertyRead collectionElements(UriInfo uriInfo, ODataRequest req, ODataResponse response)
            throws ODataApplicationException {
        List<UriResource> parts = new ArrayList<>(uriInfo.getUriResourceParts());
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof UriResourceCount) parts.remove(parts.size() - 1);
        if (parts.isEmpty() || !(parts.get(parts.size() - 1) instanceof org.apache.olingo.server.api.uri.UriResourceProperty)) {
            throw new ODataApplicationException("Not a property", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        return walkProperty(uriInfo, parts, req, response);
    }

    /** {@code Bestellungen(1)/Kategorien/$count}: number of elements after $filter and $search. */
    @Override
    public void countPrimitiveCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException, ODataLibraryException {
        countCollection(req, response, uriInfo);
    }

    @Override
    public void countComplexCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException, ODataLibraryException {
        countCollection(req, response, uriInfo);
    }

    private void countCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        PropertyRead p = collectionElements(uriInfo, req, response);
        long n = 0;
        if (p != null) {
            List<Entity> rows = new ArrayList<>();
            for (Object e : (List<?>) p.property().getValue()) rows.add(CollectionValues.row(e));
            n = QueryOptionsEvaluator.apply(rows, uriInfo.getSearchOption(), uriInfo.getFilterOption(), null, null, null, null).getCount();
            response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        } else {
            response.setStatusCode(HttpStatusCode.OK.getStatusCode()); // a null collection has no elements
        }
        respond(req, response, new ByteArrayInputStream(String.valueOf(n).getBytes(StandardCharsets.UTF_8)),
                ContentType.TEXT_PLAIN.toContentTypeString(), null);
    }

    /** {@code Bestellungen(1)/Kundendaten}: one complex value, with $select of its fields. */
    @Override
    public void readComplex(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        PropertyRead p = walkProperty(uriInfo, uriInfo.getUriResourceParts(), req, response);
        if (p == null) return;
        ContextURL contextUrl = ContextURL.with().serviceRoot(serviceRoot(req)).entitySet(p.entitySet()).keyPath(p.keyPath()).navOrPropertyPath(p.path()).build();
        SerializerResult result = odata.createSerializer(responseFormat).complex(serviceMetadata,
                (org.apache.olingo.commons.api.edm.EdmComplexType) p.edmProperty().getType(), p.property(),
                org.apache.olingo.server.api.serializer.ComplexSerializerOptions.with().contextURL(contextUrl)
                        .select(uriInfo.getSelectOption()).build());
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), null);
    }

    @Override
    public void updateComplex(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                              ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deleteComplex(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void updatePrimitiveCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                                          ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deletePrimitiveCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void updateComplexCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat,
                                        ContentType responseFormat) throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deleteComplexCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
    }

    /** Resolves the entity in front of a property segment; answers 204 itself when entity or value is null. */
    private PropertyRead readProperty(UriInfo uriInfo, ODataRequest req, ODataResponse response)
            throws ODataApplicationException {
        List<UriResource> parts = new ArrayList<>(uriInfo.getUriResourceParts());
        if (!parts.isEmpty() && parts.get(parts.size() - 1).getKind() == UriResourceKind.value) {
            parts.remove(parts.size() - 1);
        }
        if (parts.isEmpty() || !(parts.get(parts.size() - 1) instanceof UriResourcePrimitiveProperty)) {
            throw new ODataApplicationException("Only primitive properties can be read",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        return walkProperty(uriInfo, parts, req, response);
    }

    /**
     * {@code Set(key)/P}, also through complex values ({@code Bestellungen(1)/Kundendaten/Land}): the entity is
     * resolved from the segments before the first property, then the properties are followed; 204 when the entity or
     * a value on the way is null.
     */
    private PropertyRead walkProperty(UriInfo uriInfo, List<UriResource> parts, ODataRequest req, ODataResponse response)
            throws ODataApplicationException {
        begin(uriInfo);
        int first = parts.size() - 1;
        while (first > 0 && parts.get(first - 1) instanceof org.apache.olingo.server.api.uri.UriResourceComplexProperty) first--;
        Resolved r = resolve(parts.subList(0, first), customOptions(uriInfo), req);
        setCacheHeaders(response, r.root());
        Entity current = r.single();
        Property property = null;
        StringBuilder path = new StringBuilder();
        for (int i = first; i < parts.size() && current != null; i++) {
            org.apache.olingo.server.api.uri.UriResourceProperty segment = (org.apache.olingo.server.api.uri.UriResourceProperty) parts.get(i);
            if (path.length() > 0) path.append('/');
            path.append(segment.getProperty().getName());
            property = current.getProperty(segment.getProperty().getName());
            if (i < parts.size() - 1) {
                current = property != null && property.getValue() instanceof org.apache.olingo.commons.api.data.ComplexValue cv
                        ? CollectionValues.row(cv) : null;
            }
        }
        if (current == null || property == null || property.getValue() == null) {
            response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
            return null;
        }
        String id = r.single().getId() == null ? "" : r.single().getId().toString();
        int open = id.indexOf('(');
        String keyPath = open >= 0 && id.endsWith(")") ? id.substring(open + 1, id.length() - 1) : id;
        EdmProperty edmProperty = ((org.apache.olingo.server.api.uri.UriResourceProperty) parts.get(parts.size() - 1)).getProperty();
        return new PropertyRead(r.entitySet(), keyPath, edmProperty, property, path.toString());
    }

    /**
     * Result of walking a resource path: either a collection ({@code entities}), a single entity ({@code single}) or
     * neither (a single-valued navigation without a related row).
     */
    private record Resolved(Execution root, QueryDefinition query, EdmEntitySet entitySet, List<Entity> entities,
                            Entity single, PushDown pushDown) {
        Resolved(Execution root, QueryDefinition query, EdmEntitySet entitySet, List<Entity> entities, Entity single) {
            this(root, query, entitySet, entities, single, null);
        }
    }

    /**
     * Walks {@code EntitySet[(key)] [/Navigation[(key)]]* [/$count]}. Key lookups and navigations work on the
     * in-memory results; each query on the path runs at most once per request.
     */
    private Resolved resolve(List<UriResource> path, Map<String, String> custom, ODataRequest req)
            throws ODataApplicationException {
        List<UriResource> parts = new ArrayList<>(path);
        if (!parts.isEmpty() && (parts.get(parts.size() - 1) instanceof UriResourceCount
                || parts.get(parts.size() - 1) instanceof UriResourceRef)) {
            parts.remove(parts.size() - 1);
        }
        EdmEntitySet set = parts.isEmpty() ? null : rootSet(parts.get(0));
        if (set == null) {
            throw new ODataApplicationException("Only entity set requests are supported",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        List<UriParameter> rootKeys = parts.get(0) instanceof UriResourceEntitySet es ? es.getKeyPredicates()
                : ((UriResourceFunction) parts.get(0)).getKeyPredicates();
        Execution root = execute(set.getName(), custom, req.getRawQueryPath());
        executions.put(root.query().name(), root);
        QueryDefinition query = root.query();
        List<Entity> entities = EntityMapper.toEntities(query, root.table());
        Entity single = null;
        if (!rootKeys.isEmpty()) {
            single = byKey(entities, rootKeys, set.getName());
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
            // UriResourceNavigation.isCollection() is false once keys follow (Umsaetze(5)); the property decides.
            if (nav.getProperty().isCollection()) {
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

    /** Entity set addressed by the first path segment: {@code Umsatz} or {@code GetUmsatz(...)}; else {@code null}. */
    private static EdmEntitySet rootSet(UriResource first) {
        if (first instanceof UriResourceEntitySet es) return es.getEntitySet();
        if (first instanceof UriResourceFunction fn && fn.getFunctionImport() != null) {
            return fn.getFunctionImport().getReturnedEntitySet();
        }
        return null;
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

    // --- $all, $crossjoin ----------------------------------------------------------------------------------------

    /** Upper bound for the number of combinations a $crossjoin evaluates. */
    static final long MAX_CROSSJOIN_COMBINATIONS = 2_000_000;

    /**
     * {@code $all} and {@code $crossjoin(...)}, which Olingo parses but does not dispatch to processors; the servlet
     * hands them over here with the parsed {@link UriInfo}.
     */
    public void readSpecial(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        begin(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        if (uriInfo.getKind() == UriInfoKind.all) {
            readAll(req, response, uriInfo, custom);
        } else if (uriInfo.getKind() == UriInfoKind.crossjoin) {
            readCrossjoin(req, response, uriInfo, custom);
        } else {
            throw new ODataApplicationException("Not supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
    }

    /** All entities of all visible entity sets, typically with {@code $search}; queries that need parameters are skipped. */
    private void readAll(ODataRequest req, ODataResponse response, UriInfo uriInfo, Map<String, String> custom)
            throws ODataApplicationException {
        List<Entity> all = new ArrayList<>();
        for (EdmEntitySet set : serviceMetadata.getEdm().getEntityContainer().getEntitySets()) {
            Execution exec;
            try {
                exec = execute(set.getName(), custom, "$all");
            } catch (ODataApplicationException e) {
                if (e.getStatusCode() == HttpStatusCode.BAD_REQUEST.getStatusCode()) continue; // missing required parameter
                throw e;
            }
            executions.put(set.getName(), exec);
            all.addAll(EntityMapper.toEntities(exec.query(), exec.table()));
        }
        EntityCollection filtered = QueryOptionsEvaluator.apply(all, uriInfo.getSearchOption(), uriInfo.getFilterOption(),
                uriInfo.getOrderByOption(), uriInfo.getSkipOption(), uriInfo.getTopOption(), navigationResolver(custom));
        Page page = page(req, uriInfo, filtered.getEntities());
        applyPreference(response, page);
        boolean count = uriInfo.getCountOption() != null && uriInfo.getCountOption().getValue();
        byte[] body = DynamicResultWriter.collection(serviceRoot(req) + "$metadata#Collection(Edm.EntityType)", page.rows(),
                count ? filtered.getCount() : null, page.nextLink(), java.util.Set.of(), null, e -> "#" + e.getType());
        respond(req, response, new ByteArrayInputStream(body), "application/json;odata.metadata=minimal", null);
    }

    /** Combinations of rows of several entity sets, restricted by $filter on paths such as {@code Kunden/KundeId}. */
    private void readCrossjoin(ODataRequest req, ODataResponse response, UriInfo uriInfo, Map<String, String> custom)
            throws ODataApplicationException {
        List<String> sets = uriInfo.getEntitySetNames();
        List<List<Entity>> lists = new ArrayList<>();
        long combinations = 1;
        for (String set : sets) {
            Execution exec = execute(set, custom, "$crossjoin");
            executions.put(set, exec);
            List<Entity> rows = EntityMapper.toEntities(exec.query(), exec.table());
            lists.add(rows);
            combinations *= Math.max(1, rows.size());
            if (combinations > MAX_CROSSJOIN_COMBINATIONS) {
                throw new ODataApplicationException("$crossjoin would combine more than " + MAX_CROSSJOIN_COMBINATIONS
                        + " rows; restrict the entity sets or use $expand along relations instead",
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
            }
        }
        if (uriInfo.getExpandOption() != null) {
            for (ExpandItem item : uriInfo.getExpandOption().getExpandItems()) {
                if (item.getExpandOption() != null || item.getFilterOption() != null || item.getApplyOption() != null) {
                    throw new ODataApplicationException("Only $select is supported inside $expand of $crossjoin",
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
                }
            }
        }
        // Filter while combining, so only matching combinations are kept in memory.
        EvaluationContext context = navigationResolver(custom);
        List<Entity> combined = new ArrayList<>();
        int[] index = new int[sets.size()];
        boolean empty = lists.stream().anyMatch(List::isEmpty);
        while (!empty) {
            Entity row = new Entity();
            for (int i = 0; i < sets.size(); i++) {
                Link link = new Link();
                link.setTitle(sets.get(i));
                link.setInlineEntity(lists.get(i).get(index[i]));
                row.getNavigationLinks().add(link);
            }
            if (uriInfo.getFilterOption() == null
                    || !QueryOptionsEvaluator.apply(List.of(row), uriInfo.getFilterOption(), null, null, null, context)
                            .getEntities().isEmpty()) {
                combined.add(row);
            }
            int k = sets.size() - 1;
            while (k >= 0 && ++index[k] == lists.get(k).size()) {
                index[k] = 0;
                k--;
            }
            if (k < 0) break;
        }
        EntityCollection result = QueryOptionsEvaluator.apply(combined, uriInfo.getSearchOption(), null,
                uriInfo.getOrderByOption(), uriInfo.getSkipOption(), uriInfo.getTopOption(), context);
        Page page = page(req, uriInfo, result.getEntities());
        applyPreference(response, page);
        boolean count = uriInfo.getCountOption() != null && uriInfo.getCountOption().getValue();
        byte[] body = DynamicResultWriter.crossjoin(serviceRoot(req) + "$metadata#Collection(Edm.ComplexType)", sets, page.rows(), count ? result.getCount() : null, page.nextLink(),
                DynamicResultWriter.selected(uriInfo.getSelectOption()), uriInfo.getExpandOption());
        respond(req, response, new ByteArrayInputStream(body), "application/json;odata.metadata=minimal", null);
    }

    // --- $ref -----------------------------------------------------------------------------------------------------

    /** {@code Kunden(1)/Umsaetze/$ref}, {@code Kunden/$ref}: entity ids instead of entities, with collection options. */
    @Override
    public void readReferenceCollection(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        rejectUnsupportedOptions(uriInfo);
        begin(uriInfo);
        Map<String, String> custom = customOptions(uriInfo);
        Resolved r = resolve(uriInfo.getUriResourceParts(), custom, req);
        if (r.entities() == null) {
            throw new ODataApplicationException("$ref needs a collection here", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        EntityCollection collection = QueryOptionsEvaluator.apply(r.entities(), uriInfo, navigationResolver(custom));
        Page page = page(req, uriInfo, collection.getEntities());
        collection.getEntities().clear();
        collection.getEntities().addAll(page.rows());
        if (page.nextLink() != null) collection.setNext(java.net.URI.create(page.nextLink()));
        applyPreference(response, page);
        ReferenceCollectionSerializerOptions options = ReferenceCollectionSerializerOptions.with()
                .contextURL(ContextURL.with().serviceRoot(serviceRoot(req)).suffix(ContextURL.Suffix.REFERENCE).asCollection().build())
                .count(uriInfo.getCountOption()).build();
        SerializerResult result = odata.createSerializer(responseFormat)
                .referenceCollection(serviceMetadata, r.entitySet(), collection, options);
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), r.root());
    }

    /** {@code Umsatz(1)/Kunde/$ref}, {@code Kunden(1)/$ref}. */
    @Override
    public void readReference(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, ODataLibraryException {
        begin(uriInfo);
        Resolved r = resolve(uriInfo.getUriResourceParts(), customOptions(uriInfo), req);
        setCacheHeaders(response, r.root());
        if (r.single() == null) {
            response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
            return;
        }
        ReferenceSerializerOptions options = ReferenceSerializerOptions.with()
                .contextURL(ContextURL.with().serviceRoot(serviceRoot(req)).suffix(ContextURL.Suffix.REFERENCE).build()).build();
        SerializerResult result = odata.createSerializer(responseFormat).reference(serviceMetadata, r.entitySet(), r.single(), options);
        respond(req, response, result.getContent(), responseFormat.toContentTypeString(), r.root());
    }

    /**
     * Absolute service root for context URLs: Power Query rejects a relative top-level context URL
     * ("should be an absolute Uri"), and $ref context URLs must not depend on the depth of the request path.
     */
    private static java.net.URI serviceRoot(ODataRequest req) {
        String base = req.getRawBaseUri();
        return java.net.URI.create(base.endsWith("/") ? base : base + "/");
    }

    @Override
    public void createReference(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat)
            throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void updateReference(ODataRequest req, ODataResponse response, UriInfo uriInfo, ContentType requestFormat)
            throws ODataApplicationException {
        throw readOnly();
    }

    @Override
    public void deleteReference(ODataRequest req, ODataResponse response, UriInfo uriInfo) throws ODataApplicationException {
        throw readOnly();
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

    /** One query run: rows, cache outcome and, for push-down, the database's {@code COUNT(*)}. */
    private record Execution(QueryDefinition query, ResultTable table, CacheStatus cacheStatus, Long cacheAgeSeconds,
                             Long pushedCount) {
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
            for (String navName : navigationNames(source, item)) {
                int levels = 1;
                if (item.getLevelsOption() != null) {
                    levels = item.getLevelsOption().isMax() ? MAX_LEVELS : Math.min(item.getLevelsOption().getValue(), MAX_LEVELS);
                }
                expandNavigation(entities, source, item, navName, custom, depth, levels, null);
            }
        }
    }

    /** Upper bound for {@code $levels=max} and explicit level counts. */
    static final int MAX_LEVELS = 8;

    /** Upper bound for entities attached by $expand in one request (protects against $levels explosions). */
    static final int MAX_EXPANDED_ENTITIES = 200_000;

    private int expandedEntities;

    /**
     * @param visited for $levels below the first level: ids of entities already on earlier levels; they are not
     *                expanded again, so cycles (A → B → A) end instead of growing without bound
     */
    private void expandNavigation(List<Entity> entities, QueryDefinition source, ExpandItem item, String navName,
                                  Map<String, String> custom, int depth, int levels, java.util.Set<String> visited)
            throws ODataApplicationException {
        {
                QueryRelation relation = source.relations().stream().filter(r -> r.name().equals(navName)).findFirst()
                        .orElseThrow(() -> new ODataApplicationException("Unknown navigation property '" + navName + "'",
                                HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE));
                if (relation.localColumns().isEmpty() || relation.localColumns().size() != relation.targetColumns().size()) {
                    throw new ODataApplicationException("Navigation property '" + navName
                            + "' has no join columns and cannot be expanded", HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
                }
                Execution target = target(relation, custom, "$expand=" + navName + " from " + source.name());
                if (levels > 1 && !relation.targetQuery().equals(source.name())) {
                    throw new ODataApplicationException("$levels needs a navigation property that leads back to the same "
                            + "entity set ('" + navName + "' leads to '" + relation.targetQuery() + "')",
                            HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
                }
                RelationJoin join = new RelationJoin(relation, EntityMapper.toEntities(target.query(), target.table()));

                // Matches per source entity; nested options are applied per collection, nested $expand once on
                // the distinct target entities (a customer shared by many rows is expanded only once).
                Map<Entity, EntityCollection> inlineSets = new IdentityHashMap<>();
                Map<Entity, Entity> inlineEntities = new IdentityHashMap<>();
                Map<Entity, Boolean> distinct = new IdentityHashMap<>();
                for (Entity e : entities) {
                    List<Entity> matches = join.matches(e);
                    if (visited != null) {
                        matches = matches.stream().filter(t -> t.getId() == null || !visited.contains(t.getId().toString())).toList();
                    }
                    expandedEntities += matches.size();
                    if (expandedEntities > MAX_EXPANDED_ENTITIES) {
                        throw new ODataApplicationException("$expand would attach more than " + MAX_EXPANDED_ENTITIES
                                + " entities; reduce $levels or add $top/$filter inside $expand",
                                HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
                    }
                    if (relation.toMany()) {
                        if (item.getApplyOption() != null) {
                            matches = new ApplyEvaluator(navigationResolver(custom)).apply(matches, item.getApplyOption());
                        }
                        EntityCollection c;
                        if (item.hasCountPath()) {
                            // $expand=Nav/$count($filter=…): only the number is written
                            c = QueryOptionsEvaluator.apply(matches, item.getSearchOption(), item.getFilterOption(),
                                    null, null, null, navigationResolver(custom));
                            c.setCount(c.getEntities().size());
                        } else {
                            c = QueryOptionsEvaluator.apply(matches, item.getSearchOption(), item.getFilterOption(),
                                    item.getOrderByOption(), item.getSkipOption(), item.getTopOption(), navigationResolver(custom));
                        }
                        inlineSets.put(e, c);
                        c.getEntities().forEach(t -> distinct.put(t, Boolean.TRUE));
                    } else if (!matches.isEmpty()) {
                        inlineEntities.put(e, matches.get(0));
                        distinct.put(matches.get(0), Boolean.TRUE);
                    }
                }
                if (item.getExpandOption() != null && !distinct.isEmpty() && !item.isRef() && !item.hasCountPath()) {
                    List<Entity> typed = distinct.keySet().stream().filter(t -> t.getType() != null).toList();
                    if (!typed.isEmpty()) expand(typed, target.query(), item.getExpandOption(), custom, depth + 1);
                }
                if (levels > 1 && !distinct.isEmpty()) {
                    // Recursive navigation (e.g. Mitarbeiter/Vorgesetzter): expand the same property one level less.
                    java.util.Set<String> seen = visited == null ? new java.util.HashSet<>() : new java.util.HashSet<>(visited);
                    for (Entity e : entities) if (e.getId() != null) seen.add(e.getId().toString());
                    for (Entity t : distinct.keySet()) if (t.getId() != null) seen.add(t.getId().toString());
                    expandNavigation(new ArrayList<>(distinct.keySet()), target.query(), item, navName, custom, depth,
                            levels - 1, seen);
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

            @Override
            public List<Expression[]> caseBranches(String name, Entity entity) throws ODataApplicationException {
                return QueryEntityCollectionProcessor.this.caseBranches(name, entity, Map.of(), custom);
            }

            @Override
            public List<Expression[]> caseBranches(String name, Entity entity, Map<String, Entity> lambdaVariables)
                    throws ODataApplicationException {
                return QueryEntityCollectionProcessor.this.caseBranches(name, entity, lambdaVariables, custom);
            }

            @Override
            public org.apache.olingo.server.api.uri.queryoption.search.SearchExpression search(String name)
                    throws ODataApplicationException {
                return QueryEntityCollectionProcessor.this.search(name, custom);
            }

            @Override
            public List<Entity> entitySet(String name) throws ODataApplicationException {
                Execution exec = executions.get(name);
                if (exec == null) {
                    exec = execute(name, custom, "$root/" + name);
                    executions.put(name, exec);
                }
                return EntityMapper.toEntities(exec.query(), exec.table());
            }

            @Override
            public Entity lookup(String entitySet, List<UriParameter> keys) throws ODataApplicationException {
                StringBuilder cacheKey = new StringBuilder(entitySet);
                for (UriParameter k : keys) cacheKey.append('|').append(k.getName()).append('=').append(k.getText());
                Entity cached = rootLookups.get(cacheKey.toString());
                if (cached != null) return cached;
                Execution exec = executions.get(entitySet);
                if (exec == null) {
                    exec = execute(entitySet, custom, "$root/" + entitySet);
                    executions.put(entitySet, exec);
                }
                Entity found = byKey(EntityMapper.toEntities(exec.query(), exec.table()), keys, entitySet);
                rootLookups.put(cacheKey.toString(), found);
                return found;
            }
        };
    }

    /** Searches parsed by {@link SearchSyntax}, per placeholder. */
    private final Map<String, org.apache.olingo.server.api.uri.queryoption.search.SearchExpression> searches = new HashMap<>();

    private org.apache.olingo.server.api.uri.queryoption.search.SearchExpression search(String name, Map<String, String> custom)
            throws ODataApplicationException {
        org.apache.olingo.server.api.uri.queryoption.search.SearchExpression cached = searches.get(name);
        if (cached != null) return cached;
        String text = custom.get(QueryRewriter.SEARCH_OPTION + name.substring(QueryRewriter.SEARCH_ALIAS.length()));
        if (text == null) return null;
        try {
            org.apache.olingo.server.api.uri.queryoption.search.SearchExpression e = SearchSyntax.parse(text);
            searches.put(name, e);
            return e;
        } catch (IllegalArgumentException e) {
            throw new ODataApplicationException("$search: " + e.getMessage(), HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
    }

    /** Parsed case branches per placeholder and row shape (type plus property names). */
    private final Map<String, List<Expression[]>> caseCache = new HashMap<>();

    private List<Expression[]> caseBranches(String name, Entity entity, Map<String, Entity> lambdaVariables,
                                            Map<String, String> custom) throws ODataApplicationException {
        String body = custom.get(ExpressionRewriter.CASE_OPTION + name.substring(ExpressionRewriter.CASE_ALIAS.length()));
        if (body == null) return null;
        StringBuilder shape = new StringBuilder(name).append('|').append(entity.getType());
        for (Property p : entity.getProperties()) shape.append(',').append(p.getName());
        for (Link l : entity.getNavigationLinks()) shape.append(",/").append(l.getTitle());
        java.util.TreeMap<String, Entity> variables = new java.util.TreeMap<>(lambdaVariables);
        for (Map.Entry<String, Entity> v : variables.entrySet()) {
            shape.append('|').append(v.getKey()).append(':').append(v.getValue().getType());
            for (Property p : v.getValue().getProperties()) shape.append(',').append(p.getName());
        }
        List<Expression[]> cached = caseCache.get(shape.toString());
        if (cached != null) return cached;
        org.apache.olingo.commons.api.edm.EdmStructuredType type = rowType(List.of(entity));
        // lambda variables in scope become single-valued navigations of the row type (resolved again on evaluation)
        for (Map.Entry<String, Entity> v : variables.entrySet()) {
            type = RowType.withNavigation(type, v.getKey(), (EdmEntityType) rowType(List.of(v.getValue())), false);
        }
        List<Expression[]> branches = new ArrayList<>();
        try {
            for (String[] b : ExpressionRewriter.caseBranches(body)) {
                branches.add(new Expression[] {parseExpression(b[0], type), parseExpression(b[1], type)});
            }
        } catch (IllegalArgumentException e) {
            throw new ODataApplicationException("case(): " + e.getMessage(), HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        caseCache.put(shape.toString(), branches);
        return branches;
    }

    /** Olingo's type for rows of this shape: the declared entity type plus dynamic properties and navigations. */
    org.apache.olingo.commons.api.edm.EdmStructuredType rowType(List<Entity> rows) {
        String typeName = rows.stream().map(Entity::getType).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        EdmEntityType declared = typeName == null ? null
                : serviceMetadata.getEdm().getEntityType(new org.apache.olingo.commons.api.edm.FullQualifiedName(typeName));
        return RowType.of(rows, declared, serviceMetadata.getEdm());
    }

    /** One expression in the context of {@code type} (aliases of the request are known). */
    Expression parseExpression(String text, org.apache.olingo.commons.api.edm.EdmType type) throws ODataApplicationException {
        try {
            org.apache.olingo.server.core.uri.parser.UriTokenizer tokenizer = new org.apache.olingo.server.core.uri.parser.UriTokenizer(text);
            Expression e = new org.apache.olingo.server.core.uri.parser.ExpressionParser(serviceMetadata.getEdm(), odata)
                    .parse(tokenizer, type, null, aliases);
            if (!tokenizer.next(org.apache.olingo.server.core.uri.parser.UriTokenizer.TokenKind.EOF)) {
                throw new ODataApplicationException("Unexpected text after expression '" + text + "'",
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
            }
            return e;
        } catch (org.apache.olingo.server.core.uri.parser.UriParserException
                 | org.apache.olingo.server.core.uri.validator.UriValidationException e) {
            throw new ODataApplicationException("Cannot parse '" + text + "': " + e.getMessage(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
    }

    private List<Entity> navigate(Entity source, String navName, Map<String, String> custom) throws ODataApplicationException {
        {
            // Aliases of join/nest and inline results of the expand transformation travel as links on the row.
            Link inline = source.getNavigationLink(navName);
            if (inline instanceof ApplyLink) {
                if (inline.getInlineEntitySet() != null) return inline.getInlineEntitySet().getEntities();
                return inline.getInlineEntity() == null ? List.of() : List.of(inline.getInlineEntity());
            }
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

    /** Expand items that appear in the context URL: {@code Nav/$count} does not (Olingo would write "Nav/null"). */
    static ExpandOption contextExpand(ExpandOption expand) {
        if (expand == null || expand.getExpandItems().stream().noneMatch(ExpandItem::hasCountPath)) return expand;
        org.apache.olingo.server.core.uri.queryoption.ExpandOptionImpl out = new org.apache.olingo.server.core.uri.queryoption.ExpandOptionImpl();
        for (ExpandItem item : expand.getExpandItems()) if (!item.hasCountPath()) out.addExpandItem(item);
        return out.getExpandItems().isEmpty() ? null : out;
    }

    /**
     * The expand option for Olingo's serializer: {@code $expand=*&#47;$ref} becomes one {@code Nav/$ref} item per
     * navigation property, because the serializer only honours {@code $ref} on named items.
     */
    static ExpandOption serializerExpand(ExpandOption expand, EdmEntityType type) {
        if (expand == null || expand.getExpandItems().stream().noneMatch(i -> i.isStar() && i.isRef())) return expand;
        org.apache.olingo.server.core.uri.queryoption.ExpandOptionImpl out = new org.apache.olingo.server.core.uri.queryoption.ExpandOptionImpl();
        java.util.Set<String> named = new java.util.HashSet<>();
        for (ExpandItem item : expand.getExpandItems()) {
            String n = DynamicResultWriter.navName(item);
            if (n != null) named.add(n);
        }
        for (ExpandItem item : expand.getExpandItems()) {
            if (!(item.isStar() && item.isRef())) {
                out.addExpandItem(item);
                continue;
            }
            for (String n : type.getNavigationPropertyNames()) {
                if (named.contains(n)) continue;
                org.apache.olingo.server.core.uri.UriInfoImpl path = new org.apache.olingo.server.core.uri.UriInfoImpl()
                        .setKind(org.apache.olingo.server.api.uri.UriInfoKind.resource)
                        .addResourcePart(new org.apache.olingo.server.core.uri.UriResourceNavigationPropertyImpl(type.getNavigationProperty(n)));
                out.addExpandItem(new org.apache.olingo.server.core.uri.queryoption.ExpandItemImpl().setResourcePath(path).setIsRef(true));
            }
        }
        return out;
    }

    /** Navigation property names an expand item stands for ({@code *} = all of the source type). */
    private List<String> navigationNames(QueryDefinition source, ExpandItem item) throws ODataApplicationException {
        if (item.isStar()) {
            EdmEntityType type = serviceMetadata.getEdm().getEntityType(DynamicEdmProvider.typeName(source.name()));
            return type == null ? List.of() : type.getNavigationPropertyNames();
        }
        List<UriResource> parts = new ArrayList<>(item.getResourcePath() == null ? List.of() : item.getResourcePath().getUriResourceParts());
        if (!parts.isEmpty() && (parts.get(parts.size() - 1) instanceof UriResourceRef
                || parts.get(parts.size() - 1) instanceof UriResourceCount)) {
            parts.remove(parts.size() - 1); // Nav/$ref, Nav/$count
        }
        if (parts.size() != 1 || !(parts.get(0) instanceof UriResourceNavigation nav)) {
            throw new ODataApplicationException("Only $expand=<NavigationProperty>[/$ref|/$count] (optionally with nested "
                    + "options) is supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        }
        return List.of(nav.getProperty().getName());
    }

    private Execution execute(String queryName, Map<String, String> custom, String optionsForLog)
            throws ODataApplicationException {
        return execute(queryName, custom, optionsForLog, null, false, false);
    }

    /**
     * @param wrapper    push-down SQL around the query (bypasses the result cache), or {@code null}
     * @param countOnly  only run {@code SELECT COUNT(*)} (for /$count with a fully pushed filter)
     * @param withCount  additionally run {@code COUNT(*)} (for $count=true when paging was pushed)
     */
    private Execution execute(String queryName, Map<String, String> custom, String optionsForLog, SqlWrapper wrapper,
                              boolean countOnly, boolean withCount) throws ODataApplicationException {
        Instant started = Instant.now();
        long t0 = System.nanoTime();
        QueryDefinition query = support.queryService().findEnabledByName(queryName)
                .orElseThrow(() -> new ODataApplicationException("Entity set '" + queryName + "' not found",
                        HttpStatusCode.NOT_FOUND.getStatusCode(), LOCALE));
        Datasource ds = support.datasourceService().findById(query.datasourceId()).orElse(null);
        LogDraft draft = new LogDraft(started, query, ds, maskedOptions(query, optionsForLog));
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
            if (wrapper != null) {
                long s = System.nanoTime();
                ResultTable table = countOnly ? new ResultTable(List.of(), List.of(), false)
                        : support.executor().execute(query, ds, identity, params,
                                support.executor().effectiveMaxRows(query, ds), support.executor().effectiveTimeoutSec(query, ds),
                                wrapper);
                Long count = countOnly || withCount
                        ? support.executor().count(query, ds, identity, params, support.executor().effectiveTimeoutSec(query, ds), wrapper)
                        : null;
                dbNanos.set(System.nanoTime() - s);
                exec = new Execution(query, table, CacheStatus.DISABLED, null, count);
            } else if (!query.cacheEnabled()) {
                exec = new Execution(query, run.get(), CacheStatus.DISABLED, null, null);
            } else {
                CacheKey key = CacheKey.of(query.id(), query.version(), identity.dbUsername(), params,
                        SessionSettings.effective(ds, query));
                Duration ttl = Duration.ofSeconds(Math.max(1, query.cacheTtlSec()));
                if (refresh) {
                    ResultTable table = run.get();
                    support.cache().put(key, maskedParameterMap(query, params), table, ttl);
                    exec = new Execution(query, table, CacheStatus.BYPASS, null, null);
                } else {
                    ResultCache.CachedResult r = support.cache().getOrLoad(key, maskedParameterMap(query, params), ttl, run);
                    if (r.status() == CacheStatus.HIT
                            && ExecutionIdentityResolver.isUnverifiedPassThrough(request.identity(), identity)) {
                        // The key holds only the DB user name. A hit must not bypass the database login, so the
                        // caller's password is checked against the datasource before the cached rows go out.
                        support.identityResolver().verifyLogin(ds, identity.dbUsername(), identity.dbPassword());
                    }
                    exec = new Execution(query, r.cached().table(), r.status(),
                            r.status() == CacheStatus.HIT ? r.cached().ageSeconds() : null, null);
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
            support.loginAudit().feedLoginRejected(request.identity().caller(), request.clientIp());
            support.identityResolver().onRejectedLogin(request.identity());
            throw new ODataApplicationException(DbAuthenticationException.CLIENT_MESSAGE,
                    HttpStatusCode.UNAUTHORIZED.getStatusCode(), LOCALE, e);
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
            // vendor messages can name hosts and SQL fragments: fine for a database user, not for the public
            String detail = request.identity().isAnonymous() ? "" : ": " + e.getMessage();
            throw new ODataApplicationException("Database error while executing '" + queryName + "' (ref " + ref
                    + ")" + detail, status, LOCALE, e);
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
        return CacheKey.canonical(maskedParameterMap(query, params));
    }

    /** Parameter values as shown to admins (execution log, cache list): sensitive ones as {@code ***}. */
    static Map<String, Object> maskedParameterMap(QueryDefinition query, Map<String, Object> params) {
        Map<String, Object> masked = new LinkedHashMap<>();
        for (QueryParameter p : query.parameters()) {
            Object v = params.get(p.name());
            masked.put(p.name(), p.sensitive() && v != null ? "***" : v);
        }
        return masked;
    }

    /** The raw query string for the execution log, with values of sensitive parameters replaced by {@code ***}. */
    static String maskedOptions(QueryDefinition query, String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return rawQuery;
        Set<String> sensitive = query.parameters().stream().filter(QueryParameter::sensitive).map(QueryParameter::name)
                .collect(Collectors.toSet());
        if (sensitive.isEmpty()) return rawQuery;
        StringBuilder sb = new StringBuilder();
        for (String part : rawQuery.split("&", -1)) {
            if (!sb.isEmpty()) sb.append('&');
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            String decoded;
            try {
                decoded = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                decoded = name;
            }
            sb.append(eq >= 0 && sensitive.contains(decoded) ? name + "=***" : part);
        }
        return sb.toString();
    }

    /** $deltatoken outside entity collections (single entities, $count, properties): 400 instead of ignoring it. */
    private static void rejectUnsupportedOptions(UriInfo uriInfo) throws ODataApplicationException {
        if (uriInfo.getDeltaTokenOption() != null) {
            throw new ODataApplicationException("$deltatoken applies to entity collections only",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
    }

    /**
     * Query parameters of the request: custom query options ({@code ?p_jahr=2025}) and, for
     * {@code GetUmsatz(p_jahr=2025)}, the function parameters, which win over a custom option of the same name.
     * {@code null} as function argument means "use the default", like an omitted option.
     */
    private static Map<String, String> customOptions(UriInfo uriInfo) {
        Map<String, String> options = new HashMap<>();
        for (CustomQueryOption o : uriInfo.getCustomQueryOptions()) {
            options.put(o.getName(), o.getText());
        }
        List<UriResource> parts = uriInfo.getUriResourceParts();
        if (parts != null && !parts.isEmpty() && parts.get(0) instanceof UriResourceFunction fn) {
            for (UriParameter p : fn.getParameters()) {
                String text = p.getAlias() != null ? uriInfo.getValueForAlias(p.getAlias()) : p.getText();
                String value = functionArgument(text);
                if (value == null) options.remove(p.getName());
                else options.put(p.getName(), value);
            }
        }
        return options;
    }

    /** OData literal → raw parameter text: quotes removed from strings, {@code null} stays {@code null}. */
    static String functionArgument(String literal) {
        if (literal == null) return null;
        String t = literal.strip();
        if (t.equals("null")) return null;
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) return t.substring(1, t.length() - 1).replace("''", "'");
        return t;
    }
}
