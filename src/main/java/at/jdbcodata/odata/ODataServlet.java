package at.jdbcodata.odata;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.identity.BasicCredentials;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.identity.IdentityContext;
import at.jdbcodata.identity.ServiceAuthenticationException;
import at.jdbcodata.query.QueryDefinition;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.apache.olingo.commons.api.http.HttpMethod;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.core.uri.parser.Parser;
import org.apache.olingo.server.core.uri.parser.UriParserException;
import org.apache.olingo.server.core.uri.validator.UriValidationException;
import org.apache.olingo.server.api.ODataHttpHandler;
import org.apache.olingo.server.api.ServiceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry point for {@code /odata/*}. Requires HTTP Basic credentials on every request (this is what makes Excel
 * prompt for them), authenticates service users, builds the personalised model and hands over to Olingo with a
 * per-request processor carrying the identity context.
 */
@Component
public class ODataServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ODataServlet.class);

    private final transient EdmRegistry edmRegistry;
    private final transient ExecutionIdentityResolver identityResolver;
    private final transient ProcessorSupport support;
    private final transient OData odata = OData.newInstance();
    /** Service user for requests without credentials; empty = Basic Auth is mandatory. */
    private final String anonymousUser;

    public ODataServlet(EdmRegistry edmRegistry, ExecutionIdentityResolver identityResolver, ProcessorSupport support,
                        JdbcOdataProperties properties) {
        this.edmRegistry = edmRegistry;
        this.identityResolver = identityResolver;
        this.support = support;
        this.anonymousUser = properties.odata().anonymousUser() == null ? "" : properties.odata().anonymousUser().trim();
    }

    @Override
    protected void service(HttpServletRequest rawReq, HttpServletResponse resp) throws IOException {
        if (BoundedRequest.declaredTooLarge(rawReq)) {
            writeJsonError(resp, 413, "Request body larger than " + BoundedRequest.MAX_BODY_BYTES + " bytes");
            return;
        }
        HttpServletRequest req = new BoundedRequest(rawReq);
        String authorization = req.getHeader("Authorization");
        Optional<BasicCredentials> credentials = BasicCredentials.fromHeader(authorization);
        // a client that tried to log in must not silently get the anonymous user's data
        boolean loginUnreadable = credentials.isEmpty() && authorization != null && !authorization.isBlank();
        if (credentials.isEmpty() && (anonymousUser.isEmpty() || loginUnreadable)) {
            unauthorized(resp, "Authentication required. Send HTTP Basic credentials for the target database.");
            return;
        }
        try {
            IdentityContext context;
            try {
                context = credentials.isPresent()
                        ? identityResolver.authenticate(credentials.get())
                        : identityResolver.anonymous(anonymousUser);
            } catch (ServiceAuthenticationException e) {
                if (credentials.isPresent()) {
                    support.rateLimiter().recordFailure(req.getRemoteAddr(), credentials.get().username());
                    support.loginAudit().feedLoginRejected(credentials.get().username(), req.getRemoteAddr());
                    // same answer as a rejected database login, so service user names cannot be probed
                    unauthorized(resp, DbAuthenticationException.CLIENT_MESSAGE);
                } else {
                    log.warn("Anonymous OData access is configured for service user '{}' but the user is missing, "
                            + "disabled or flagged force-passthrough", anonymousUser);
                    unauthorized(resp, "Authentication required. Send HTTP Basic credentials for the target database.");
                }
                return;
            }
            EntityExpressionVisitor.resetRegexBudget();
            List<QueryDefinition> visible;
            try {
                visible = identityResolver.visibleQueries(context);
            } catch (DbAuthenticationException e) {
                log.debug("Pass-through login of '{}' rejected: {}", context.caller(), e.getMessage());
                identityResolver.onRejectedLogin(context);
                support.rateLimiter().recordFailure(req.getRemoteAddr(), context.caller());
                support.loginAudit().feedLoginRejected(context.caller(), req.getRemoteAddr());
                unauthorized(resp, DbAuthenticationException.CLIENT_MESSAGE);
                return;
            } catch (DbExecutionException e) {
                log.warn("Login of '{}' could not be verified: {}", context.caller(), e.getMessage());
                writeJsonError(resp, 503, "Database temporarily unavailable");
                return;
            }
            ServiceMetadata metadata = edmRegistry.serviceMetadata(visible);
            // Per-caller content (row-level security), revalidated via ETag instead of never stored.
            resp.setHeader("Cache-Control", "private, no-cache");
            resp.setHeader("Vary", "Authorization");
            RequestContext requestContext = new RequestContext(context, req.getHeader("User-Agent"), req.getRemoteAddr());
            String path = odataPath(req);
            if (path.startsWith(ASYNC_MONITOR)) {
                serveMonitor(req, resp, context.caller(), path.substring(ASYNC_MONITOR.length()));
                return;
            }
            if (wantsAsync(req) && ("GET".equals(req.getMethod()) || ("POST".equals(req.getMethod()) && "/$batch".equals(path)))) {
                serveAsync(req, resp, metadata, requestContext);
                return;
            }
            QueryEntityCollectionProcessor processor = new QueryEntityCollectionProcessor(support, requestContext);
            try {
                if (isSpecialResource(req)) {
                    serveSpecial(req, resp, metadata, processor);
                    return;
                }
                ODataHttpHandler handler = odata.createHandler(metadata);
                handler.register(processor);
                handler.register(new JsonErrorProcessor());
                handler.process(olingoRequest(req, metadata.getEdm()), resp);
            } finally {
                processor.releaseResponseBudget();
            }
        } catch (RuntimeException e) {
            log.error("OData request {} failed outside Olingo", req.getRequestURI(), e);
            if (!resp.isCommitted()) {
                resp.reset();
                writeJsonError(resp, 500, "Internal server error");
            }
        }
    }

    /** {@code /$all} and {@code /$crossjoin(...)}: parsed by Olingo, but not dispatched to processors by it. */
    static boolean isSpecialResource(HttpServletRequest req) {
        String path = odataPath(req);
        return "GET".equals(req.getMethod()) && ("/$all".equals(path) || path.startsWith("/$crossjoin("));
    }

    private static String odataPath(HttpServletRequest req) {
        String prefix = req.getContextPath() + req.getServletPath();
        String uri = req.getRequestURI();
        return uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
    }

    private void serveSpecial(HttpServletRequest req, HttpServletResponse resp, ServiceMetadata metadata,
                              QueryEntityCollectionProcessor processor) throws IOException {
        String base = req.getRequestURL().toString();
        String path = odataPath(req);
        base = base.substring(0, base.length() - path.length());
        String query = QueryRewriter.rewrite(req.getQueryString());
        try {
            UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri(path, query, null, base);
            ODataRequest request = new ODataRequest();
            request.setMethod(HttpMethod.GET);
            request.setRawBaseUri(base);
            request.setRawODataPath(path);
            request.setRawQueryPath(query);
            request.setRawRequestUri(base + path + (query == null ? "" : "?" + query));
            for (String header : java.util.Collections.list(req.getHeaderNames())) {
                request.addHeader(header, java.util.Collections.list(req.getHeaders(header)));
            }
            ODataResponse response = new ODataResponse();
            processor.init(odata, metadata);
            processor.readSpecial(request, response, uriInfo);
            resp.setStatus(response.getStatusCode());
            response.getAllHeaders().forEach((name, values) -> values.forEach(v -> resp.addHeader(name, v)));
            if (response.getContent() != null) {
                try (var in = response.getContent()) {
                    in.transferTo(resp.getOutputStream());
                }
            }
        } catch (UriParserException | UriValidationException e) {
            writeJsonError(resp, 400, e.getMessage());
        } catch (ODataApplicationException e) {
            writeJsonError(resp, e.getStatusCode(), e.getMessage());
        }
    }

    // --- asynchronous requests (Prefer: respond-async) ---------------------------------------------------------

    /** Path of the status monitors: {@code /odata/$async/<id>}. */
    static final String ASYNC_MONITOR = "/$async/";

    static boolean wantsAsync(HttpServletRequest req) {
        for (String prefer : java.util.Collections.list(req.getHeaders("Prefer"))) {
            for (String p : prefer.split(",")) {
                if (p.strip().equalsIgnoreCase("respond-async")) return true;
            }
        }
        return false;
    }

    /**
     * Runs the request in the background and answers 202 with the status monitor in {@code Location}. When the
     * caller already has too many running jobs, the request is answered synchronously (the preference is optional).
     */
    private void serveAsync(HttpServletRequest req, HttpServletResponse resp, ServiceMetadata metadata,
                            RequestContext requestContext) throws IOException {
        String path = odataPath(req);
        String base = req.getRequestURL().toString();
        base = base.substring(0, base.length() - path.length());
        ODataRequest request = new ODataRequest();
        request.setMethod(HttpMethod.valueOf(req.getMethod()));
        request.setProtocol(req.getProtocol());
        boolean special = isSpecialResource(req);
        Rewritten rw = "/$batch".equals(path) ? new Rewritten(path, req.getQueryString())
                : special ? new Rewritten(path, QueryRewriter.rewrite(req.getQueryString()))
                : rewrite(req.getMethod(), path, req.getQueryString(), metadata.getEdm());
        request.setRawBaseUri(base);
        request.setRawODataPath(rw.path());
        request.setRawQueryPath(rw.query() == null ? "" : rw.query());
        request.setRawRequestUri(base + rw.path() + (rw.query() == null ? "" : "?" + rw.query()));
        for (String header : java.util.Collections.list(req.getHeaderNames())) {
            request.addHeader(header, java.util.Collections.list(req.getHeaders(header)));
        }
        request.setBody(new java.io.ByteArrayInputStream(req.getInputStream().readAllBytes()));
        java.util.concurrent.Callable<AsyncRequests.Result> work = () -> {
            EntityExpressionVisitor.resetRegexBudget();
            QueryEntityCollectionProcessor processor = new QueryEntityCollectionProcessor(support, requestContext);
            try {
                ODataResponse response;
                if (special) {
                    response = new ODataResponse();
                    try {
                        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri(rw.path(), rw.query(), null, request.getRawBaseUri());
                        processor.init(odata, metadata);
                        processor.readSpecial(request, response, uriInfo);
                    } catch (UriParserException | UriValidationException e) {
                        return jsonError(400, e.getMessage());
                    } catch (ODataApplicationException e) {
                        return jsonError(e.getStatusCode(), e.getMessage());
                    }
                } else {
                    ODataHttpHandler handler = odata.createHandler(metadata);
                    handler.register(processor);
                    handler.register(new JsonErrorProcessor());
                    response = handler.process(request);
                }
                byte[] body = response.getContent() == null ? new byte[0] : response.getContent().readAllBytes();
                return new AsyncRequests.Result(response.getStatusCode(), response.getAllHeaders(), body);
            } finally {
                processor.releaseResponseBudget();
            }
        };
        String id = support.asyncRequests().submit(requestContext.identity().caller(), work);
        if (id == null) {
            try {
                write(resp, work.call());
            } catch (Exception e) {
                log.error("OData request {} failed", req.getRequestURI(), e);
                writeJsonError(resp, 500, "Internal server error");
            }
            return;
        }
        resp.setStatus(202);
        resp.setHeader("Location", base + ASYNC_MONITOR + id);
        resp.setHeader("Preference-Applied", "respond-async");
        resp.setHeader("Retry-After", "1");
    }

    /**
     * Status monitor: 202 while the job runs, then 200 with the response as {@code application/http}; DELETE
     * cancels the job. Only the caller who started it can see it.
     */
    private void serveMonitor(HttpServletRequest req, HttpServletResponse resp, String caller, String id) throws IOException {
        if ("DELETE".equals(req.getMethod())) {
            if (support.asyncRequests().cancel(id, caller)) resp.setStatus(204);
            else writeJsonError(resp, 404, "Unknown asynchronous request");
            return;
        }
        if (!"GET".equals(req.getMethod())) {
            writeJsonError(resp, 405, "Only GET and DELETE on a status monitor");
            return;
        }
        AsyncRequests.Job job = support.asyncRequests().find(id, caller);
        if (job == null) {
            writeJsonError(resp, 404, "Unknown or expired asynchronous request");
            return;
        }
        if (!job.future().isDone()) {
            resp.setStatus(202);
            resp.setHeader("Location", req.getRequestURL().toString());
            resp.setHeader("Retry-After", "1");
            return;
        }
        AsyncRequests.Result result;
        try {
            result = job.future().get();
        } catch (java.util.concurrent.CancellationException e) {
            writeJsonError(resp, 404, "The asynchronous request was cancelled");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeJsonError(resp, 500, "Internal server error");
            return;
        } catch (java.util.concurrent.ExecutionException e) {
            log.error("Asynchronous OData request {} failed", id, e.getCause());
            result = jsonError(500, "Internal server error");
        }
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(result.status()).append(' ')
                .append(reason(result.status())).append("\r\n");
        result.headers().forEach((name, values) -> values.forEach(v -> head.append(name).append(": ").append(v).append("\r\n")));
        head.append("\r\n");
        resp.setStatus(200);
        resp.setContentType("application/http");
        resp.setHeader("Content-Transfer-Encoding", "binary");
        resp.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
        resp.getOutputStream().write(result.body());
    }

    private static String reason(int status) {
        org.apache.olingo.commons.api.http.HttpStatusCode code = org.apache.olingo.commons.api.http.HttpStatusCode.fromStatusCode(status);
        return code == null ? "" : code.getInfo();
    }

    private static AsyncRequests.Result jsonError(int status, String message) {
        String body = "{\"error\":{\"code\":\"" + status + "\",\"message\":\"" + String.valueOf(message).replace("\"", "'") + "\"}}";
        return new AsyncRequests.Result(status, java.util.Map.of("Content-Type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpServletResponse resp, AsyncRequests.Result result) throws IOException {
        resp.setStatus(result.status());
        result.headers().forEach((name, values) -> values.forEach(v -> resp.addHeader(name, v)));
        resp.getOutputStream().write(result.body());
    }

    /** Caller, user agent and address of one OData request. */
    public record RequestContext(IdentityContext identity, String userAgent, String clientIp) {
    }

    /**
     * Olingo derives the OData path by searching the servlet path inside the full request URL
     * ({@code indexOf}). Behind a reverse proxy the URL carries the public host, and a host such as
     * {@code odata.example.org} already contains {@code /odata}, which makes every request "malformed".
     * This wrapper hands Olingo an empty servlet and context path plus the path below the mapping, so it
     * uses the request URI directly and still builds absolute links from the forwarded host.
     */
    static HttpServletRequest olingoRequest(HttpServletRequest req) {
        return olingoRequest(req, null);
    }

    /** As above; with an EDM, key segments ({@code Kunden/1}) are also rewritten to {@code Kunden(1)}. */
    static HttpServletRequest olingoRequest(HttpServletRequest req, org.apache.olingo.commons.api.edm.Edm edm) {
        String prefix = req.getContextPath() + req.getServletPath();
        String uri = req.getRequestURI();
        String original = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
        Rewritten rewritten = rewrite(req.getMethod(), original, req.getQueryString(), edm);
        String odataPath = rewritten.path();
        // Olingo takes the base URI as request URL minus OData path, so the URL must end with the rewritten path.
        String url = req.getRequestURL().toString();
        StringBuffer requestUrl = new StringBuffer(odataPath.equals(original) || !url.endsWith(original) ? url
                : url.substring(0, url.length() - original.length()) + odataPath);
        return new HttpServletRequestWrapper(req) {
            @Override
            public String getServletPath() {
                return "";
            }

            @Override
            public String getContextPath() {
                return "";
            }

            @Override
            public String getRequestURI() {
                return odataPath;
            }

            @Override
            public StringBuffer getRequestURL() {
                return new StringBuffer(requestUrl);
            }

            @Override
            public String getQueryString() {
                return rewritten.query();
            }
        };
    }

    /** OData path and query string after all rewrites that close Olingo 5 parser gaps. */
    record Rewritten(String path, String query) {
    }

    /**
     * Path filter segments, key segments and query-option rewrites for one request; used for top-level requests
     * and for every part of a {@code $batch}.
     */
    static Rewritten rewrite(String method, String path, String query, org.apache.olingo.commons.api.edm.Edm edm) {
        PathFilterRewriter.Result pathFilters = PathFilterRewriter.extract(path);
        String odataPath = KeySegmentRewriter.rewrite(pathFilters.path(), edm);
        boolean singleEntity = "GET".equals(method) && KeySegmentRewriter.addressesSingleEntity(odataPath, edm);
        String q = QueryRewriter.rewrite(PathFilterRewriter.mergeIntoQuery(query, pathFilters.filters()),
                singleEntity, KeySegmentRewriter.targetType(odataPath, edm));
        return new Rewritten(odataPath, q);
    }

    private static void unauthorized(HttpServletResponse resp, String message) throws IOException {
        resp.setHeader("WWW-Authenticate", JsonErrorProcessor.BASIC_CHALLENGE);
        writeJsonError(resp, 401, message);
    }

    private static void writeJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(JsonErrorProcessor.errorJson(status, message));
    }
}
