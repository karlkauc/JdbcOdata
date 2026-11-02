package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.InvalidRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The configuration option reaches saving and the connection test, also for a datasource stored before it was set. */
@SpringBootTest(properties = {"jdbcodata.security.allow-plain-http=true", "jdbcodata.security.require-datasource-tls=true"})
class DatasourceTlsOptionTest {

    @Autowired
    DatasourceService datasources;
    @Autowired
    DatasourceRepository repository;

    @Test
    void refusesToSaveADatasourceWithoutTls() {
        String name = uniqueName();
        assertThatThrownBy(() -> datasources.create(spec(name, "jdbc:postgresql://db/x?sslmode=require")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("TLS is required");
        assertThat(datasources.findByName(name)).isEmpty();
    }

    @Test
    void savesAVerifiedDatasource() {
        String name = uniqueName();
        assertThat(datasources.create(spec(name, "jdbc:postgresql://db/x?sslmode=verify-full")).name()).isEqualTo(name);
    }

    @Test
    void connectionTestRefusesAStoredDatasourceWithoutTls() {
        Datasource ds = datasources.create(spec(uniqueName(), "jdbc:postgresql://db/x?sslmode=verify-full"));
        DatasourceEntity entity = repository.findById(ds.id()).orElseThrow();
        entity.setJdbcUrl("jdbc:postgresql://db/x");
        repository.save(entity);

        ConnectionTestResult result = datasources.testConnection(ds.id(), "user", "password");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("TLS is required");
    }

    /** The test database outlives this context. */
    private static String uniqueName() {
        return "tls-" + UUID.randomUUID();
    }

    private static DatasourceSpec spec(String name, String url) {
        return new DatasourceSpec(name, null, url, null, null, 30, 1000, 10, true);
    }
}
