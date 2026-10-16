package at.jdbcodata.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.SchemaSnapshotService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

class SeedLoaderTest extends DemoProfileTest {

    @Autowired
    ResourceLoader resourceLoader;
    @Autowired
    Environment environment;
    @Autowired
    DatasourceService datasourceService;
    @Autowired
    QueryService queryService;
    @Autowired
    SchemaSnapshotService snapshotService;

    @TempDir
    Path dir;

    private SeedLoader loader(String file) {
        JdbcOdataProperties props = new JdbcOdataProperties(new JdbcOdataProperties.Admin("a", "b"),
                new JdbcOdataProperties.OData(10, 10), new JdbcOdataProperties.Seed(file));
        return new SeedLoader(props, resourceLoader, environment, datasourceService, queryService, snapshotService);
    }

    @Test
    void skipsWhenNotConfiguredOrMissing() throws Exception {
        loader("").run(null);
        loader(dir.resolve("missing.yml").toString()).run(null);
        Path empty = Files.writeString(dir.resolve("empty.yml"), "");
        loader(empty.toString()).run(null);
    }

    @Test
    void upsertsAndReportsSnapshotProblems() throws Exception {
        Path seed = Files.writeString(dir.resolve("seed.yml"), """
                datasources:
                  - name: seed_ds
                    jdbcUrl: ${jdbcodata.demo.jdbc-url}
                    maxRows: "500"
                queries:
                  - name: SeedNoCreds
                    datasource: seed_ds
                    sql: SELECT customer FROM sales WHERE sale_year = :y
                    parameters:
                      - name: y
                        type: int
                        required: true
                  - name: SeedWrongCreds
                    datasource: seed_ds
                    sql: SELECT customer FROM sales
                    snapshotCredentials: {username: demo, password: wrong}
                  - name: SeedOk
                    datasource: seed_ds
                    sql: SELECT customer FROM sales
                    keyMode: rowid
                    executionMode: caller
                    snapshotCredentials: {username: demo, password: demo}
                """);
        loader(seed.toString()).run(null);

        var ds = datasourceService.findByName("seed_ds").orElseThrow();
        assertThat(ds.jdbcUrl()).startsWith("jdbc:h2:mem:demo-test");
        assertThat(ds.maxRows()).isEqualTo(500);
        assertThat(queryService.findEnabledByName("SeedNoCreds").orElseThrow().hasSnapshot()).isFalse();
        assertThat(queryService.findEnabledByName("SeedWrongCreds").orElseThrow().hasSnapshot()).isFalse();
        assertThat(queryService.findEnabledByName("SeedOk").orElseThrow().hasSnapshot()).isTrue();

        // second run is idempotent and keeps the snapshot
        loader(seed.toString()).run(null);
        assertThat(queryService.findEnabledByName("SeedOk").orElseThrow().version()).isEqualTo(1);
        assertThat(queryService.findEnabledByName("SeedOk").orElseThrow().hasSnapshot()).isTrue();

        Path bad = Files.writeString(dir.resolve("bad.yml"), """
                queries:
                  - name: Orphan
                    datasource: nope
                    sql: SELECT 1
                """);
        assertThatThrownBy(() -> loader(bad.toString()).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nope");
    }
}
