package at.jdbcodata.identity;

import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionIdentityResolverTest {

    private final ExecutionIdentityResolver resolver = new ExecutionIdentityResolver();

    @Test
    void passesCredentialsThrough() {
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(), KeyMode.ROWID);
        ExecutionIdentity id = resolver.resolve(q, new BasicCredentials("maria", "s3cret"));
        assertThat(id.caller()).isEqualTo("maria");
        assertThat(id.dbUsername()).isEqualTo("maria");
        assertThat(id.dbPassword()).isEqualTo("s3cret");
        assertThat(id.mode()).isEqualTo(ExecutionMode.CALLER);
        assertThat(id.toString()).doesNotContain("s3cret");
    }

    @Test
    void fixedModeIsNotAvailableYet() {
        QueryDefinition fixed = new QueryDefinition(UUID.randomUUID(), "F", null, UUID.randomUUID(), "ds", "SELECT 1",
                ExecutionMode.FIXED, false, 0, null, null, KeyMode.ROWID, 1, true, List.of(), List.of(),
                Instant.now(), Instant.now());
        assertThatThrownBy(() -> resolver.resolve(fixed, new BasicCredentials("a", "b")))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("M3");
    }
}
