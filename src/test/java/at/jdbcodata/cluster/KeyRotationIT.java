package at.jdbcodata.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.cluster.TwoNodes.Node;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.FixedCredentialService.FixedCredentialSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Replacing the master key, as described in the operations guide. */
@Testcontainers(disabledWithoutDocker = true)
class KeyRotationIT {

    private static final String KEY_A = key(1);
    private static final String KEY_B = key(2);
    private static final String KEY_C = key(3);
    private static final String PASSWORD = "stored-db-password";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static String key(int fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Node node(String key, String previous) throws Exception {
        return TwoNodes.start(postgres, "rotation", Map.of("jdbcodata.master-key", key, "jdbcodata.master-key-previous", previous));
    }

    @Test
    void aKeyCanBeReplacedStepByStep() throws Exception {
        UUID account;
        String keyIdA;
        try (Node withA = node(KEY_A, "")) {
            account = withA.bean(FixedCredentialService.class).create(new FixedCredentialSpec("rotated", null,
                    withA.bean(DatasourceService.class).findAll().getFirst().id(), "dbuser", PASSWORD)).id();
            keyIdA = storedKeyId();
        }

        // 1. every node learns the new key while A stays in use: nothing changes yet
        try (Node learning = node(KEY_A, KEY_B)) {
            assertThat(login(learning, account)).isEqualTo(PASSWORD);
            assertThat(storedKeyId()).isEqualTo(keyIdA);
        }
        // 2. B becomes the key in use, stored passwords move to it
        String keyIdB;
        try (Node switched = node(KEY_B, KEY_A)) {
            assertThat(login(switched, account)).isEqualTo(PASSWORD);
            keyIdB = storedKeyId();
            assertThat(keyIdB).isNotEqualTo(keyIdA);
        }
        // 3. A is gone
        try (Node done = node(KEY_B, "")) {
            assertThat(login(done, account)).isEqualTo(PASSWORD);
            assertThat(storedKeyId()).isEqualTo(keyIdB);
        }
        // a wrong key leaves the passwords unusable but intact, and the service starts
        try (Node wrong = node(KEY_C, "")) {
            UUID id = account;
            assertThatThrownBy(() -> login(wrong, id)).isInstanceOf(IllegalStateException.class);
            assertThat(storedKeyId()).isEqualTo(keyIdB);
        }
        try (Node back = node(KEY_B, "")) {
            assertThat(login(back, account)).isEqualTo(PASSWORD);
        }
    }

    private static String login(Node node, UUID account) {
        return node.bean(FixedCredentialService.class).resolveLogin(account).password();
    }

    private static String storedKeyId() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT key_id FROM fixed_credential WHERE name = 'rotated'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
