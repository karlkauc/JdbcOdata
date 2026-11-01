package at.jdbcodata.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A caller with an account on only one database must not fail a login on the other with every request. */
class ExecutionIdentityResolverRejectedLoginTest {

    private final QueryService queries = mock(QueryService.class);
    private final DatasourceService datasources = mock(DatasourceService.class);
    private final IdentityPoolManager pools = spy(new IdentityPoolManager());
    private final ExecutionIdentityResolver resolver = new ExecutionIdentityResolver(
            mock(ServiceUserService.class), mock(FixedCredentialService.class), queries, datasources, pools, null);
    private final IdentityContext caller = new IdentityContext(new BasicCredentials("anna", "secret"), null);

    private final Datasource own = datasource("own");
    private final Datasource foreign = datasource("foreign");
    private final QueryDefinition onOwn = query(own);
    private final QueryDefinition onForeign = query(foreign);

    @BeforeEach
    void setUp() throws Exception {
        when(queries.isVisible(any(), any())).thenReturn(true);
        when(queries.findAllEnabled()).thenReturn(List.of(onOwn, onForeign));
        when(datasources.require(own.id())).thenReturn(own);
        when(datasources.require(foreign.id())).thenReturn(foreign);
        doReturn(mock(Connection.class)).when(pools).connect(eq(own), any(), any());
        doThrow(new SQLException("password authentication failed", "28P01")).when(pools).connect(eq(foreign), any(), any());
    }

    @Test
    void aRejectedLoginIsNotTriedAgain() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(resolver.visibleQueries(caller)).containsExactly(onOwn);
        }
        verify(pools, times(1)).connect(foreign, "anna", "secret");
        verify(pools, times(3)).connect(own, "anna", "secret");
    }

    @Test
    void anotherPasswordIsTried() throws Exception {
        resolver.visibleQueries(caller);
        clearInvocations(pools);
        resolver.visibleQueries(new IdentityContext(new BasicCredentials("anna", "changed"), null));
        verify(pools, times(1)).connect(foreign, "anna", "changed");
    }

    @Test
    void anotherUserIsTried() throws Exception {
        resolver.visibleQueries(caller);
        clearInvocations(pools);
        resolver.visibleQueries(new IdentityContext(new BasicCredentials("bernd", "secret"), null));
        verify(pools, times(1)).connect(foreign, "bernd", "secret");
    }

    @Test
    void savingTheDatasourceEndsTheWait() throws Exception {
        resolver.visibleQueries(caller);
        pools.closePoolsFor(foreign.id());
        clearInvocations(pools);
        resolver.visibleQueries(caller);
        verify(pools, times(1)).connect(foreign, "anna", "secret");
    }

    @Test
    void aRememberedRejectionStillAnswersLikeARejection() throws Exception {
        when(queries.findAllEnabled()).thenReturn(List.of(onForeign));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resolver.visibleQueries(caller)).isInstanceOf(DbAuthenticationException.class);
        }
        verify(pools, times(1)).connect(foreign, "anna", "secret");
    }

    @Test
    void anOutageIsNotRemembered() throws Exception {
        doThrow(new SQLException("Connection refused", "08001")).when(pools).connect(eq(foreign), any(), any());
        resolver.visibleQueries(caller);
        resolver.visibleQueries(caller);
        verify(pools, times(2)).connect(foreign, "anna", "secret");
        verify(pools, never()).rememberRejected(any(), any(), any());
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
