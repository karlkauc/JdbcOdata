package at.jdbcodata.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A database that cannot be reached proves nothing about a pass-through login, so nothing of it is shown. */
class ExecutionIdentityResolverOutageTest {

    private final QueryService queries = mock(QueryService.class);
    private final DatasourceService datasources = mock(DatasourceService.class);
    private final IdentityPoolManager pools = mock(IdentityPoolManager.class);
    private final ExecutionIdentityResolver resolver = new ExecutionIdentityResolver(
            mock(ServiceUserService.class), mock(FixedCredentialService.class), queries, datasources, pools, null);
    private final IdentityContext caller = new IdentityContext(new BasicCredentials("bogus", "bogus"), null);

    private final Datasource down = datasource("down");
    private final Datasource up = datasource("up");
    private final QueryDefinition onDown = query(down);
    private final QueryDefinition onUp = query(up);

    @BeforeEach
    void setUp() throws Exception {
        when(queries.isVisible(any(), any())).thenReturn(true);
        when(datasources.require(down.id())).thenReturn(down);
        when(datasources.require(up.id())).thenReturn(up);
        when(pools.connect(eq(down), any(), any())).thenThrow(new SQLException("Connection refused", "08001"));
        when(pools.connect(eq(up), any(), any())).thenReturn(mock(Connection.class));
    }

    @Test
    void unreachableDatabaseStaysHiddenIfAnotherOneAcceptedTheLogin() {
        when(queries.findAllEnabled()).thenReturn(List.of(onDown, onUp));
        assertThat(resolver.visibleQueries(caller)).containsExactly(onUp);
    }

    @Test
    void noDatabaseReachableIsAnOutageNotAnEmptyService() {
        when(queries.findAllEnabled()).thenReturn(List.of(onDown));
        assertThatThrownBy(() -> resolver.visibleQueries(caller)).isInstanceOf(DbExecutionException.class);
    }

    private static Datasource datasource(String name) {
        return new Datasource(UUID.randomUUID(), name, null, "jdbc:postgresql://db/" + name, null, null, 60, 1000, 1, true,
                null, null, null, null);
    }

    private static QueryDefinition query(Datasource ds) {
        QueryDefinition q = mock(QueryDefinition.class);
        when(q.hasSnapshot()).thenReturn(true);
        when(q.datasourceId()).thenReturn(ds.id());
        return q;
    }
}
