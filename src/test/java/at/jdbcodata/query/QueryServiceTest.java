package at.jdbcodata.query;

import static at.jdbcodata.testutil.TestData.param;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.odata.EdmRegistry;
import at.jdbcodata.testutil.DemoProfileTest;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class QueryServiceTest extends DemoProfileTest {

    @Autowired
    QueryService service;
    @Autowired
    DatasourceService datasourceService;
    @Autowired
    EdmRegistry edmRegistry;
    @Autowired
    QueryVersionRepository versionRepository;

    private QuerySpec spec(String name, String sql, List<QueryParameter> params) {
        Datasource demo = datasourceService.findByName("demo").orElseThrow();
        return new QuerySpec(name, "desc", demo.id(), sql, null, null, null, null, null, null, null, params);
    }

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');
    }

    @Test
    void validatesDefinitions() {
        assertThatThrownBy(() -> service.create(spec("1bad", "SELECT 1", List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> service.create(spec("Ok", " ", List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("SQL");
        assertThatThrownBy(() -> service.create(spec("Ok", "SELECT :x", List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining(":x");
        assertThatThrownBy(() -> service.create(spec("Ok", "SELECT 1", List.of(param("bad name", ParameterType.INT, false, null)))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("bad name");
        assertThatThrownBy(() -> service.create(spec("Ok", "SELECT 1", List.of(new QueryParameter("p", null, false, null, null, false)))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("no type");
        assertThatThrownBy(() -> service.create(spec("Ok", "SELECT :p", List.of(param("p", ParameterType.INT, false, "abc")))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("INT");
        assertThatThrownBy(() -> service.create(spec("Ok", "SELECT :p", List.of(param("p", ParameterType.INT, false, null), param("p", ParameterType.INT, false, null)))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> service.create(spec("Ok", "DELETE FROM sales", List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Only SELECT");
        assertThatThrownBy(() -> service.create(spec("Ok", "SELECT FROM WHERE", List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("parsed");
        assertThatThrownBy(() -> service.create(new QuerySpec("Ok", null, null, "SELECT 1", null, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("datasourceId");
        assertThatThrownBy(() -> service.create(new QuerySpec("Ok", null, UUID.randomUUID(), "SELECT 1", null, null, null, null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(spec("Sales", "SELECT 1", List.of())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.transientDefinition(new QuerySpec("Ok", null, UUID.randomUUID(), "SELECT 1", null, null, null, null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void lifecycleWithVersions() {
        String name = uniqueName("Svc_");
        QueryDefinition created = service.create(spec(name, "SELECT 1 AS one", List.of()));
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.enabled()).isTrue();
        assertThat(created.hasSnapshot()).isFalse();
        assertThat(created.executionMode()).isEqualTo(ExecutionMode.CALLER);
        assertThat(service.findEnabledByName(name)).isPresent();
        assertThat(service.findAll()).extracting(QueryDefinition::name).contains(name);
        List<QueryVersion> versions = service.versions(created.id());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).comment()).isEqualTo("created");
        assertThat(versions.get(0).changedBy()).isEqualTo("system");
        assertThat(versions.get(0).sqlText()).isEqualTo("SELECT 1 AS one");

        QueryDefinition withSnapshot = service.replaceSnapshot(created.id(), List.of(
                new QueryColumn(0, "one", "one", Types.INTEGER, "INTEGER", "Edm.Int32", null, null, true, false, true),
                new QueryColumn(1, "two", "two", Types.INTEGER, "INTEGER", "Edm.Int32", null, null, true, false, true)));
        assertThat(withSnapshot.hasSnapshot()).isTrue();
        assertThat(edmRegistry.serviceMetadata().getEdm().getEntityContainer().getEntitySet(name)).isNotNull();

        QueryDefinition settings = service.updateColumnSettings(created.id(), List.of(
                new ColumnSetting("one", true, false), new ColumnSetting("two", false, false), new ColumnSetting("nope", true, true)));
        assertThat(settings.columns().get(0).key()).isTrue();
        assertThat(settings.columns().get(0).visible()).as("key implies visible").isTrue();
        assertThat(settings.columns().get(1).visible()).isFalse();

        QueryDefinition sameSql = service.update(created.id(), spec(name, "SELECT  1   AS one", List.of()));
        assertThat(sameSql.version()).isEqualTo(1);
        assertThat(sameSql.hasSnapshot()).isTrue();
        assertThat(service.versions(created.id())).hasSize(1);

        QueryDefinition newSql = service.update(created.id(), spec(name, "SELECT 2 AS two", List.of()));
        assertThat(newSql.version()).isEqualTo(2);
        assertThat(newSql.hasSnapshot()).isFalse();
        assertThat(service.versions(created.id())).extracting(QueryVersion::version).containsExactly(2, 1);
        assertThat(edmRegistry.serviceMetadata().getEdm().getEntityContainer().getEntitySet(name)).isNull();

        QueryDefinition withParam = service.update(created.id(), spec(name, "SELECT 2 AS two",
                List.of(param("p", ParameterType.INT, false, "1"))));
        assertThat(withParam.version()).as("parameter change creates a version").isEqualTo(3);
        assertThat(service.versions(created.id()).get(0).parameters()).extracting(QueryParameter::name).containsExactly("p");

        assertThatThrownBy(() -> service.update(created.id(), spec("Sales", "SELECT 1", List.of())))
                .isInstanceOf(ConflictException.class);

        QueryDefinition upserted = service.upsertByName(spec(name, "SELECT 3 AS three", List.of()));
        assertThat(upserted.id()).isEqualTo(created.id());
        assertThat(upserted.version()).isEqualTo(4);

        QueryDefinition disabled = service.update(created.id(), new QuerySpec(name, null, created.datasourceId(),
                "SELECT 3 AS three", ExecutionMode.CALLER, true, 60, 5, 50, KeyMode.COLUMNS, false, List.of()));
        assertThat(disabled.version()).as("settings only, no new version").isEqualTo(4);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.cacheEnabled()).isTrue();
        assertThat(disabled.cacheTtlSec()).isEqualTo(60);
        assertThat(disabled.timeoutSec()).isEqualTo(5);
        assertThat(disabled.maxRows()).isEqualTo(50);
        assertThat(disabled.keyMode()).isEqualTo(KeyMode.COLUMNS);
        assertThat(service.findEnabledByName(name)).isEmpty();
        assertThat(service.findAllEnabled()).extracting(QueryDefinition::name).doesNotContain(name);

        QueryDefinition restored = service.restoreVersion(created.id(), 1);
        assertThat(restored.version()).isEqualTo(5);
        assertThat(restored.sqlText()).isEqualTo("SELECT 1 AS one");
        assertThat(restored.enabled()).as("settings are kept").isFalse();
        assertThat(service.versions(created.id()).get(0).comment()).isEqualTo("restored from version 1");
        assertThatThrownBy(() -> service.restoreVersion(created.id(), 99)).isInstanceOf(NotFoundException.class);

        service.delete(created.id());
        assertThat(service.findById(created.id())).isEmpty();
        assertThatThrownBy(() -> service.require(created.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.versions(created.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update(created.id(), spec(name, "SELECT 1", List.of()))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(created.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.replaceSnapshot(created.id(), List.of())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateColumnSettings(created.id(), List.of())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.restoreVersion(created.id(), 1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void backfillsHistoryForDefinitionsWithoutVersions() {
        String name = uniqueName("Old_");
        QueryDefinition created = service.create(spec(name, "SELECT 1 AS a", List.of()));
        versionRepository.deleteAll(versionRepository.findAllByQueryIdOrderByVersionDesc(created.id()));
        assertThat(service.versions(created.id())).isEmpty();

        QueryDefinition updated = service.update(created.id(), spec(name, "SELECT 2 AS b", List.of()));
        assertThat(updated.version()).isEqualTo(2);
        List<QueryVersion> versions = service.versions(created.id());
        assertThat(versions).extracting(QueryVersion::version).containsExactly(2, 1);
        assertThat(versions.get(1).sqlText()).isEqualTo("SELECT 1 AS a");
        assertThat(versions.get(1).comment()).contains("before change");
        service.delete(created.id());
    }

    @Test
    void buildsTransientDefinitions() {
        QueryDefinition t = service.transientDefinition(spec("Tmp", "SELECT 1 AS x", List.of()));
        assertThat(t.id()).isNull();
        assertThat(t.datasourceName()).isEqualTo("demo");
        assertThat(t.version()).isZero();
        assertThat(t.executionMode()).isEqualTo(ExecutionMode.CALLER);
        assertThat(t.keyMode()).isEqualTo(KeyMode.ROWID);
        assertThat(t.cacheTtlSec()).isEqualTo(3600);
        assertThat(t.enabled()).isTrue();
        assertThat(t.hasSnapshot()).isFalse();
    }
}
