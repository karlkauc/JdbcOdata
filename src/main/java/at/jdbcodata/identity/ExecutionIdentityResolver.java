package at.jdbcodata.identity;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.DbErrorClassifier;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /**
     * Identity for a request without credentials: the configured anonymous service user. No password is involved,
     * so the user must be enabled and must not depend on pass-through credentials.
     *
     * @throws ServiceAuthenticationException the user does not exist, is disabled or is flagged force-passthrough
     */
    public IdentityContext anonymous(String username) {
        ServiceUser user = serviceUsers.findByUsername(username)
                .filter(ServiceUser::enabled)
                .filter(u -> !u.forcePassthrough())
                .orElseThrow(() -> new ServiceAuthenticationException(username));
        return IdentityContext.anonymous(user);
    }

    /**
     * Enabled queries with a snapshot that the caller may see (personalised service document, FR-35). A pass-through
     * caller has proven nothing yet, so each datasource behind these queries must accept the Basic login first
     * (pooled, so cheap after the first request); queries of datasources that reject it are left out. A datasource
     * that is merely unreachable keeps its queries, they fail when executed.
     *
     * @throws DbAuthenticationException every datasource rejected the pass-through login
     */
    public List<QueryDefinition> visibleQueries(IdentityContext context) {
        List<QueryDefinition> visible = queryService.findAllEnabled().stream()
                .filter(QueryDefinition::hasSnapshot)
                .filter(q -> queryService.isVisible(q, context))
                .toList();
        if (context.isServiceUser() || context.isAnonymous() || visible.isEmpty()) return visible;
        Set<UUID> accepted = new HashSet<>();
        DbAuthenticationException rejected = null;
        RuntimeException unreachable = null;
        for (UUID datasourceId : visible.stream().map(QueryDefinition::datasourceId).collect(Collectors.toSet())) {
            try {
                verifyLogin(datasourceService.require(datasourceId), context.credentials().username(),
                        context.credentials().password());
                accepted.add(datasourceId);
            } catch (DbAuthenticationException e) {
                rejected = e;
            } catch (RuntimeException e) {
                // an unreachable database proves nothing about the login, so its schema stays hidden
                unreachable = e;
            }
        }
        if (accepted.isEmpty() && rejected != null) throw rejected;
        if (accepted.isEmpty() && unreachable != null) throw unreachable;
        return visible.stream().filter(q -> accepted.contains(q.datasourceId())).toList();
    }

    public ExecutionIdentity resolve(QueryDefinition query, IdentityContext context) {
        BasicCredentials creds = context.credentials();
        String caller = context.caller();
        if (query.executionMode() == ExecutionMode.FIXED) {
            if (!queryService.isVisible(query, context)) {
                throw new QueryNotVisibleException(query.name());
            }
            if (query.fixedCredentialId() == null) {
                throw new IllegalStateException("Query '" + query.name() + "' is FIXED but has no technical account");
            }
            if (!context.isServiceUser()) {
                verifyPassThroughLogin(query, creds);
            }
            ServiceUserService.DbLogin login = fixedCredentials.resolveLogin(query.fixedCredentialId());
            return new ExecutionIdentity(caller, login.username(), login.password(), ExecutionMode.FIXED);
        }
        ServiceUser user = context.serviceUser();
        if (user != null && !user.forcePassthrough()) {
            Optional<ServiceUserService.DbLogin> stored = serviceUsers.resolveStoredLogin(user.id(), query.datasourceId());
            if (stored.isPresent()) {
                return new ExecutionIdentity(caller, stored.get().username(), stored.get().password(),
                        ExecutionMode.CALLER);
            }
        }
        if (context.isAnonymous()) {
            // No stored credentials for this datasource and nothing to pass through: the anonymous user may not
            // run this query. Surfaces as 401 so clients know that credentials would help.
            throw new ServiceAuthenticationException(IdentityContext.ANONYMOUS);
        }
        return new ExecutionIdentity(caller, creds.username(), creds.password(), ExecutionMode.CALLER);
    }

    /** Pass-through callers of FIXED queries are authenticated by opening a connection with their own login. */
    private void verifyPassThroughLogin(QueryDefinition query, BasicCredentials creds) {
        verifyLogin(datasourceService.require(query.datasourceId()), creds.username(), creds.password());
    }

    /** After a database rejected a pass-through login: see {@link ServiceUserService#spendPasswordCheck}. */
    public void onRejectedLogin(IdentityContext context) {
        if (!context.isServiceUser() && !context.isAnonymous()) serviceUsers.spendPasswordCheck(context.credentials().password());
    }

    /**
     * True if the caller's own Basic Auth password is the only credential behind this identity: nobody has verified
     * it yet except the target database. Service users were authenticated against their service password.
     */
    public static boolean isUnverifiedPassThrough(IdentityContext context, ExecutionIdentity identity) {
        return !context.isServiceUser() && identity.mode() == ExecutionMode.CALLER;
    }

    /**
     * Opens (and immediately returns) a pooled connection with the given login. A wrong password surfaces as
     * {@link at.jdbcodata.datasource.DbAuthenticationException}. Used so that a cache hit never hands out a result
     * to a caller who did not prove the DB password.
     */
    public void verifyLogin(Datasource ds, String username, String password) {
        try (Connection c = pools.connect(ds, username, password)) {
            c.isValid(2);
        } catch (SQLException e) {
            throw DbErrorClassifier.translate(e, ds.name());
        }
    }
}
