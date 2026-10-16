package at.jdbcodata.odata;

import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.BasicCredentials;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.query.QueryExecutor;
import at.jdbcodata.query.QueryService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataHttpHandler;
import org.apache.olingo.server.api.ServiceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry point for {@code /odata/*}. Requires HTTP Basic credentials on every request (this is what makes Excel
 * prompt for them), then hands over to Olingo with a per-request processor carrying those credentials.
 */
@Component
public class ODataServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ODataServlet.class);

    private final transient EdmRegistry edmRegistry;
    private final transient QueryService queryService;
    private final transient DatasourceService datasourceService;
    private final transient ExecutionIdentityResolver identityResolver;
    private final transient QueryExecutor executor;
    private final transient OData odata = OData.newInstance();

    public ODataServlet(EdmRegistry edmRegistry, QueryService queryService, DatasourceService datasourceService,
                        ExecutionIdentityResolver identityResolver, QueryExecutor executor) {
        this.edmRegistry = edmRegistry;
        this.queryService = queryService;
        this.datasourceService = datasourceService;
        this.identityResolver = identityResolver;
        this.executor = executor;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<BasicCredentials> credentials = BasicCredentials.fromHeader(req.getHeader("Authorization"));
        if (credentials.isEmpty()) {
            unauthorized(resp, "Authentication required. Send HTTP Basic credentials for the target database.");
            return;
        }
        try {
            ServiceMetadata metadata = edmRegistry.serviceMetadata();
            ODataHttpHandler handler = odata.createHandler(metadata);
            handler.register(new QueryEntityCollectionProcessor(queryService, datasourceService, identityResolver,
                    executor, credentials.get()));
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

    private static void unauthorized(HttpServletResponse resp, String message) throws IOException {
        resp.setHeader("WWW-Authenticate", JsonErrorProcessor.BASIC_CHALLENGE);
        writeJsonError(resp, 401, message);
    }

    private static void writeJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write("{\"error\":{\"code\":\"" + status + "\",\"message\":\"" + message + "\"}}");
    }
}
