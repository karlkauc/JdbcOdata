package at.jdbcodata.odata;

import at.jdbcodata.identity.BasicCredentials;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.identity.IdentityContext;
import at.jdbcodata.identity.ServiceAuthenticationException;
import at.jdbcodata.query.QueryDefinition;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.apache.olingo.server.api.OData;
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

    public ODataServlet(EdmRegistry edmRegistry, ExecutionIdentityResolver identityResolver, ProcessorSupport support) {
        this.edmRegistry = edmRegistry;
        this.identityResolver = identityResolver;
        this.support = support;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<BasicCredentials> credentials = BasicCredentials.fromHeader(req.getHeader("Authorization"));
        if (credentials.isEmpty()) {
            unauthorized(resp, "Authentication required. Send HTTP Basic credentials for the target database.");
            return;
        }
        try {
            IdentityContext context;
            try {
                context = identityResolver.authenticate(credentials.get());
            } catch (ServiceAuthenticationException e) {
                unauthorized(resp, e.getMessage());
                return;
            }
            List<QueryDefinition> visible = identityResolver.visibleQueries(context);
            ServiceMetadata metadata = edmRegistry.serviceMetadata(visible);
            ODataHttpHandler handler = odata.createHandler(metadata);
            handler.register(new QueryEntityCollectionProcessor(support,
                    new RequestContext(context, req.getHeader("User-Agent"), clientIp(req))));
            handler.register(new JsonErrorProcessor());
            handler.process(req, resp);
        } catch (RuntimeException e) {
            log.error("OData request {} failed outside Olingo", req.getRequestURI(), e);
            if (!resp.isCommitted()) {
                resp.reset();
                writeJsonError(resp, 500, "Internal server error");
            }
        }
    }

    /** Caller, user agent and address of one OData request. */
    public record RequestContext(IdentityContext identity, String userAgent, String clientIp) {
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
