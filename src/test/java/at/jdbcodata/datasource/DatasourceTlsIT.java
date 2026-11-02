package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The option against a PostgreSQL that really speaks TLS, with a certificate for "localhost" from a throwaway CA. */
class DatasourceTlsIT {

    private static final String STORE_PASSWORD = "changeit";

    @TempDir
    static Path dir;

    private static PostgreSQLContainer postgres;
    private static Path ca;
    private static Path otherCa;

    private final IdentityPoolManager pools = new IdentityPoolManager(new JdbcUrlPolicy(List.of(), false, true));

    @BeforeAll
    static void start() throws Exception {
        ca = authority("ca");
        otherCa = authority("other");
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", store("server"), "-storepass", STORE_PASSWORD);
        keytool("-certreq", "-alias", "server", "-keystore", store("server"), "-storepass", STORE_PASSWORD,
                "-file", dir.resolve("server.csr").toString());
        keytool("-gencert", "-alias", "ca", "-keystore", store("ca"), "-storepass", STORE_PASSWORD, "-rfc",
                "-ext", "san=dns:localhost", "-validity", "2", "-infile", dir.resolve("server.csr").toString(),
                "-outfile", dir.resolve("server.crt").toString());

        // PostgreSQL only accepts a key that belongs to its own user, and a copied file belongs to root
        String entrypoint = "cp /certs/server.* /tmp/ && chown postgres /tmp/server.* && chmod 600 /tmp/server.key && "
                + "exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key";
        postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(dir.resolve("server.crt"))), "/certs/server.crt")
                .withCopyToContainer(Transferable.of(privateKeyPem()), "/certs/server.key")
                .withCreateContainerCmdModifier(c -> c.withEntrypoint("sh", "-c", entrypoint).withCmd(List.of()));
        postgres.start();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @AfterEach
    void closePools() {
        pools.closeAll();
    }

    @Test
    void verifiedConnectionIsEncrypted() throws Exception {
        Datasource ds = datasource("localhost", "?sslmode=verify-full&sslrootcert=" + ca);
        try (Connection c = pools.connect(ds, postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }

    @Test
    void refusesAStoredDatasourceWithoutTlsBeforeConnecting() {
        Datasource ds = datasource("localhost", "");
        assertThatThrownBy(() -> pools.connect(ds, postgres.getUsername(), postgres.getPassword()))
                .isInstanceOf(DbExecutionException.class).hasMessageContaining("TLS is required");
        assertThat(pools.activePoolCount()).isZero();
    }

    @Test
    void certificateFromAnotherAuthorityFails() {
        Datasource ds = datasource("localhost", "?sslmode=verify-full&sslrootcert=" + otherCa);
        assertThatThrownBy(() -> pools.connect(ds, postgres.getUsername(), postgres.getPassword()))
                .isInstanceOf(DbExecutionException.class);
    }

    @Test
    void onlyVerifyFullChecksTheHostName() throws Exception {
        Datasource wrongName = datasource("127.0.0.1", "?sslmode=verify-full&sslrootcert=" + ca);
        assertThatThrownBy(() -> pools.connect(wrongName, postgres.getUsername(), postgres.getPassword()))
                .isInstanceOf(DbExecutionException.class);

        Datasource authorityOnly = datasource("127.0.0.1", "?sslmode=verify-ca&sslrootcert=" + ca);
        try (Connection c = pools.connect(authorityOnly, postgres.getUsername(), postgres.getPassword())) {
            assertThat(c.isValid(1)).isTrue();
        }
    }

    private static Datasource datasource(String host, String options) {
        String url = "jdbc:postgresql://" + host + ":" + postgres.getFirstMappedPort() + "/" + postgres.getDatabaseName() + options;
        return new Datasource(UUID.randomUUID(), "tls", null, url, null, null, 30, 10, 10, true, Instant.now(), Instant.now(),
                Map.of(), Map.of(), 2);
    }

    private static Path authority(String name) throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=" + name, "-keyalg", "RSA", "-keysize", "2048", "-ext", "bc:c",
                "-validity", "2", "-keystore", store(name), "-storepass", STORE_PASSWORD);
        Path certificate = dir.resolve(name + ".crt");
        keytool("-exportcert", "-alias", "ca", "-rfc", "-keystore", store(name), "-storepass", STORE_PASSWORD,
                "-file", certificate.toString());
        return certificate;
    }

    private static byte[] privateKeyPem() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(store("server")))) {
            store.load(in, STORE_PASSWORD.toCharArray());
        }
        byte[] key = store.getKey("server", STORE_PASSWORD.toCharArray()).getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key)
                + "\n-----END PRIVATE KEY-----\n";
        return pem.getBytes(StandardCharsets.US_ASCII);
    }

    private static String store(String name) {
        return dir.resolve(name + ".p12").toString();
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("keytool failed: " + output);
    }
}
