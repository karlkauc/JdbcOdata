package at.jdbcodata.identity;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbErrorClassifier;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves who calls and under which database identity a query runs (ARCHITECTURE.md §5):
 * <ol>
 *   <li>If the Basic username is a service user, the service password is verified. Otherwise the caller is a
 *       pass-through user.</li>
 *   <li>FIXED queries need a grant; they run under the technical account. Pass-through callers must additionally
 *       prove a valid login against the query's datasource.</li>
 *   <li>CALLER queries use the stored credentials of the service user for the datasource, if any, otherwise
 *       the Basic credentials unchanged ("pass-through"). A service user flagged force-passthrough always uses
 *       the Basic credentials.</li>
 * </ol>
 */
@Component
public class ExecutionIdentityResolver {

    private final ServiceUserService serviceUsers;
    private final FixedCredentialService fixedCredentials;
    private final QueryService queryService;
    private final DatasourceService datasourceService;
    private final IdentityPoolManager pools;

    public ExecutionIdentityResolver(ServiceUserService serviceUsers, FixedCredentialService fixedCredentials,
                                     QueryService queryService, DatasourceService datasourceService,
                                     IdentityPoolManager pools) {
        this.serviceUsers = serviceUsers;
        this.fixedCredentials = fixedCredentials;
        this.queryService = queryService;
        this.datasourceService = datasourceService;
        this.pools = pools;
    }

    /** @throws ServiceAuthenticationException wrong service password */
    public IdentityContext authenticate(BasicCredentials credentials) {
        Optional<ServiceUser> user = serviceUsers.authenticate(credentials.username(), credentials.password());
        return new IdentityContext(credentials, user.orElse(null));
    }

    /** Enabled queries with a snapshot that the caller may see (personalised service document, FR-35). */
    public List<QueryDefinition> visibleQueries(IdentityContext context) {
        return queryService.findAllEnabled().stream()
                .filter(QueryDefinition::hasSnapshot)
                .filter(q -> queryService.isVisible(q, context.serviceUser()))
                .toList();
    }

    public ExecutionIdentity resolve(QueryDefinition query, IdentityContext context) {
        BasicCredentials creds = context.credentials();
        if (query.executionMode() == ExecutionMode.FIXED) {
            if (!queryService.isVisible(query, context.serviceUser())) {
                throw new QueryNotVisibleException(query.name());
            }
            if (query.fixedCredentialId() == null) {
                throw new IllegalStateException("Query '" + query.name() + "' is FIXED but has no technical account");
            }
            if (!context.isServiceUser()) {
                verifyPassThroughLogin(query, creds);
            }
            ServiceUserService.DbLogin login = fixedCredentials.resolveLogin(query.fixedCredentialId());
            return new ExecutionIdentity(creds.username(), login.username(), login.password(), ExecutionMode.FIXED);
        }
        ServiceUser user = context.serviceUser();
        if (user != null && !user.forcePassthrough()) {
            Optional<ServiceUserService.DbLogin> stored = serviceUsers.resolveStoredLogin(user.id(), query.datasourceId());
            if (stored.isPresent()) {
                return new ExecutionIdentity(creds.username(), stored.get().username(), stored.get().password(),
                        ExecutionMode.CALLER);
            }
        }
        return new ExecutionIdentity(creds.username(), creds.username(), creds.password(), ExecutionMode.CALLER);
    }

    /** Pass-through callers of FIXED queries are authenticated by opening a connection with their own login. */
    private void verifyPassThroughLogin(QueryDefinition query, BasicCredentials creds) {
        Datasource ds = datasourceService.require(query.datasourceId());
        try (Connection c = pools.connect(ds, creds.username(), creds.password())) {
            c.isValid(2);
        } catch (SQLException e) {
            throw DbErrorClassifier.translate(e, ds.name());
        }
    }
}
