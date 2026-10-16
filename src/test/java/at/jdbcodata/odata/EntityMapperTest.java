package at.jdbcodata.odata;

import static at.jdbcodata.testutil.TestData.column;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.ResultColumn;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.TypeMapper;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.junit.jupiter.api.Test;

class EntityMapperTest {

    private static final ResultTable TABLE = new ResultTable(List.of(
            rc("name", Types.VARCHAR), rc("amount", Types.NUMERIC), rc("extra", Types.INTEGER)),
            List.of(new Object[] {"a", new BigDecimal("1.005"), 1},
                    new Object[] {"b", new BigDecimal("2"), 2},
                    new Object[] {null, null, 3}), false);

    @Test
    void mapsRowsWithRowIdSkipAndTop() {
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(
                column(0, "name", "Edm.String", null, null, false, true),
                column(1, "amount", "Edm.Decimal", 10, 2, false, true),
                column(2, "missing", "Edm.String", null, null, false, true),
                column(3, "hidden", "Edm.String", null, null, false, false)), KeyMode.ROWID);

        EntityCollection all = EntityMapper.toCollection(q, TABLE, 0, null);
        assertThat(all.getCount()).isEqualTo(3);
        assertThat(all.getEntities()).hasSize(3);
        Entity first = all.getEntities().get(0);
        assertThat(first.getProperty("RowId").getValue()).isEqualTo(1L);
        assertThat(first.getProperty("name").getValue()).isEqualTo("a");
        assertThat(first.getProperty("amount").getValue()).isEqualTo(new BigDecimal("1.01"));
        assertThat(first.getProperty("missing").getValue()).isNull();
        assertThat(first.getProperty("hidden")).isNull();
        assertThat(first.getId().toString()).isEqualTo("Q(1)");
        assertThat(first.getType()).isEqualTo("JdbcOdata.Q");
        assertThat(all.getEntities().get(1).getProperty("amount").getValue()).isEqualTo(new BigDecimal("2"));
        assertThat(all.getEntities().get(2).getProperty("name").getValue()).isNull();

        EntityCollection page = EntityMapper.toCollection(q, TABLE, 1, 1);
        assertThat(page.getCount()).isEqualTo(3);
        assertThat(page.getEntities()).hasSize(1);
        assertThat(page.getEntities().get(0).getProperty("RowId").getValue()).isEqualTo(2L);

        assertThat(EntityMapper.toCollection(q, TABLE, 10, 5).getEntities()).isEmpty();
        assertThat(EntityMapper.toCollection(q, TABLE, -1, -1).getEntities()).isEmpty();
    }

    @Test
    void usesColumnKeysWhenConfigured() {
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(
                column(0, "name", "Edm.String", null, null, true, true),
                column(1, "extra", "Edm.Int32", null, null, true, true)), KeyMode.COLUMNS);
        EntityCollection c = EntityMapper.toCollection(q, TABLE, 0, null);
        Entity first = c.getEntities().get(0);
        assertThat(first.getProperty("RowId")).isNull();
        assertThat(first.getId().toString()).isEqualTo("Q(name='a',extra=1)");
        assertThat(c.getEntities().get(2).getId().toString()).isEqualTo("Q(name=null,extra=3)");
    }

    private static ResultColumn rc(String name, int type) {
        return new ResultColumn(name, type, "t", 10, 2, true, TypeMapper.map(type, "t", 10, 2));
    }
}
