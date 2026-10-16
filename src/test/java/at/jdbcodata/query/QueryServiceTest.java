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

    private QuerySpec spec(String name, String sql, List<QueryParameter> params) {
        Datasource demo = datasourceService.findByName("demo").orElseThrow();
        return new QuerySpec(name, "desc", demo.id(), sql, null, null, null, null, null, null, null, params);
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
        assertThatThrownBy(() -> service.create(new QuerySpec("Ok", null, null, "SELECT 1", null, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("datasourceId");
        assertThatThrownBy(() -> service.create(new QuerySpec("Ok", null, UUID.randomUUID(), "SELECT 1", null, null, null, null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(spec("Sales", "SELECT 1", List.of())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void lifecycleOfADefinition() {
        String name = "Svc_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');
        QueryDefinition created = service.create(spec(name, "SELECT 1 AS one", List.of()));
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.enabled()).isTrue();
        assertThat(created.hasSnapshot()).isFalse();
        assertThat(created.executionMode()).isEqualTo(ExecutionMode.CALLER);
        assertThat(service.findEnabledByName(name)).isPresent();
        assertThat(service.findAll()).extracting(QueryDefinition::name).contains(name);

        QueryDefinition withSnapshot = service.replaceSnapshot(created.id(), List.of(
                new QueryColumn(0, "one", "one", Types.INTEGER, "INTEGER", "Edm.Int32", null, null, true, false, true)));
        assertThat(withSnapshot.hasSnapshot()).isTrue();
        assertThat(edmRegistry.serviceMetadata().getEdm().getEntityContainer().getEntitySet(name)).isNotNull();

        QueryDefinition sameSql = service.update(created.id(), spec(name, "SELECT  1   AS one", List.of()));
        assertThat(sameSql.version()).isEqualTo(1);
        assertThat(sameSql.hasSnapshot()).isTrue();

        QueryDefinition newSql = service.update(created.id(), spec(name, "SELECT 2 AS two", List.of()));
        assertThat(newSql.version()).isEqualTo(2);
        assertThat(newSql.hasSnapshot()).isFalse();
        assertThat(edmRegistry.serviceMetadata().getEdm().getEntityContainer().getEntitySet(name)).isNull();

        assertThatThrownBy(() -> service.update(created.id(), spec("Sales", "SELECT 1", List.of())))
                .isInstanceOf(ConflictException.class);

        QueryDefinition upserted = service.upsertByName(spec(name, "SELECT 3 AS three", List.of()));
        assertThat(upserted.id()).isEqualTo(created.id());
        assertThat(upserted.version()).isEqualTo(3);

        QueryDefinition disabled = service.update(created.id(), new QuerySpec(name, null, created.datasourceId(),
                "SELECT 3 AS three", ExecutionMode.CALLER, true, 60, 5, 50, KeyMode.COLUMNS, false, List.of()));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.cacheEnabled()).isTrue();
        assertThat(disabled.cacheTtlSec()).isEqualTo(60);
        assertThat(disabled.timeoutSec()).isEqualTo(5);
        assertThat(disabled.maxRows()).isEqualTo(50);
        assertThat(disabled.keyMode()).isEqualTo(KeyMode.COLUMNS);
        assertThat(service.findEnabledByName(name)).isEmpty();
        assertThat(service.findAllEnabled()).extracting(QueryDefinition::name).doesNotContain(name);

        service.delete(created.id());
        assertThat(service.findById(created.id())).isEmpty();
        assertThatThrownBy(() -> service.require(created.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update(created.id(), spec(name, "SELECT 1", List.of()))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(created.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.replaceSnapshot(created.id(), List.of())).isInstanceOf(NotFoundException.class);
    }
}
