package at.jdbcodata.odata;

import at.jdbcodata.config.JdbcOdataProperties;
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
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<BasicCredentials> credentials = BasicCredentials.fromHeader(req.getHeader("Authorization"));
        if (credentials.isEmpty() && anonymousUser.isEmpty()) {
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
                    support.rateLimiter().recordFailure(clientIp(req), credentials.get().username());
                    unauthorized(resp, e.getMessage());
                } else {
                    log.warn("Anonymous OData access is configured for service user '{}' but the user is missing, "
                            + "disabled or flagged force-passthrough", anonymousUser);
                    unauthorized(resp, "Authentication required. Send HTTP Basic credentials for the target database.");
                }
                return;
            }
            EntityExpressionVisitor.resetRegexBudget();
            List<QueryDefinition> visible = identityResolver.visibleQueries(context);
            ServiceMetadata metadata = edmRegistry.serviceMetadata(visible);
            // Per-caller content (row-level security), revalidated via ETag instead of never stored.
            resp.setHeader("Cache-Control", "private, no-cache");
            resp.setHeader("Vary", "Authorization");
            QueryEntityCollectionProcessor processor = new QueryEntityCollectionProcessor(support,
                    new RequestContext(context, req.getHeader("User-Agent"), clientIp(req)));
            if (isSpecialResource(req)) {
                serveSpecial(req, resp, metadata, processor);
                return;
            }
            ODataHttpHandler handler = odata.createHandler(metadata);
            handler.register(processor);
            handler.register(new JsonErrorProcessor());
            handler.process(olingoRequest(req, metadata.getEdm()), resp);
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
        PathFilterRewriter.Result pathFilters = PathFilterRewriter.extract(original);
        String odataPath = KeySegmentRewriter.rewrite(pathFilters.path(), edm);
        boolean singleEntity = "GET".equals(req.getMethod()) && KeySegmentRewriter.addressesSingleEntity(odataPath, edm);
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
                return QueryRewriter.rewrite(PathFilterRewriter.mergeIntoQuery(super.getQueryString(), pathFilters.filters()),
                        singleEntity, KeySegmentRewriter.targetType(odataPath, edm));
            }
        };
    }

    static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return req.getRemoteAddr();
    }

    private static void unauthorized(HttpServletResponse resp, String message) throws IOException {
        resp.setHeader("WWW-Authenticate", JsonErrorProcessor.BASIC_CHALLENGE);
        writeJsonError(resp, 401, message);
    }

    private static void writeJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write("{\"error\":{\"code\":\"" + status + "\",\"message\":\"" + message.replace("\"", "'") + "\"}}");
    }
}
