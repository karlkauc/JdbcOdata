package at.jdbcodata.identity;

import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.QueryDefinition;
import org.springframework.stereotype.Component;

/**
 * Resolves the database identity for a request. Milestone M1 implements pass-through only: the Basic Auth
 * credentials are used unchanged as the database login. Service users, stored credentials and the FIXED mode
 * follow in M3 (see ARCHITECTURE.md §5).
 */
@Component
public class ExecutionIdentityResolver {

    public ExecutionIdentity resolve(QueryDefinition query, BasicCredentials credentials) {
        if (query.executionMode() == ExecutionMode.FIXED) {
            throw new UnsupportedOperationException("Execution mode FIXED is not available yet (milestone M3)");
        }
        return new ExecutionIdentity(credentials.username(), credentials.username(), credentials.password(),
                ExecutionMode.CALLER);
    }
}
