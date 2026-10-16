package at.jdbcodata.odata;

import static at.jdbcodata.testutil.TestData.column;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.queryoption.SkipOption;
import org.apache.olingo.server.api.uri.queryoption.TopOption;
import org.junit.jupiter.api.Test;

class EntityMapperTest {

    private static final ResultTable TABLE = new ResultTable(List.of(
            rc("name", Types.VARCHAR), rc("amount", Types.NUMERIC), rc("extra", Types.INTEGER)),
            List.of(new Object[] {"a", new BigDecimal("1.005"), 1},
                    new Object[] {"b", new BigDecimal("2"), 2},
                    new Object[] {null, null, 3}), false);

    private final QueryDefinition rowIdQuery = query("Q", "SELECT 1", List.of(), List.of(
            column(0, "name", "Edm.String", null, null, false, true),
            column(1, "amount", "Edm.Decimal", 10, 2, false, true),
            column(2, "missing", "Edm.String", null, null, false, true),
            column(3, "hidden", "Edm.String", null, null, false, false)), KeyMode.ROWID);

    @Test
    void mapsRowsWithRowIdAndRoundsDecimals() {
        List<Entity> all = EntityMapper.toEntities(rowIdQuery, TABLE);
        assertThat(all).hasSize(3);
        Entity first = all.get(0);
        assertThat(first.getProperty("RowId").getValue()).isEqualTo(1L);
        assertThat(first.getProperty("name").getValue()).isEqualTo("a");
        assertThat(first.getProperty("amount").getValue()).isEqualTo(new BigDecimal("1.01"));
        assertThat(first.getProperty("missing").getValue()).isNull();
        assertThat(first.getProperty("hidden")).isNull();
        assertThat(first.getId().toString()).isEqualTo("Q(1)");
        assertThat(first.getType()).isEqualTo("JdbcOdata.Q");
        assertThat(all.get(1).getProperty("amount").getValue()).isEqualTo(new BigDecimal("2"));
        assertThat(all.get(2).getProperty("name").getValue()).isNull();
    }

    @Test
    void appliesSkipAndTop() throws Exception {
        List<Entity> all = EntityMapper.toEntities(rowIdQuery, TABLE);
        UriInfo uriInfo = mock(UriInfo.class);
        SkipOption skip = mock(SkipOption.class);
        TopOption top = mock(TopOption.class);
        when(uriInfo.getSkipOption()).thenReturn(skip);
        when(uriInfo.getTopOption()).thenReturn(top);
        when(skip.getValue()).thenReturn(1);
        when(top.getValue()).thenReturn(1);

        EntityCollection page = QueryOptionsEvaluator.apply(all, uriInfo);
        assertThat(page.getCount()).isEqualTo(3);
        assertThat(page.getEntities()).hasSize(1);
        assertThat(page.getEntities().get(0).getProperty("RowId").getValue()).isEqualTo(2L);

        when(skip.getValue()).thenReturn(10);
        assertThat(QueryOptionsEvaluator.apply(all, uriInfo).getEntities()).isEmpty();
        when(skip.getValue()).thenReturn(-1);
        when(top.getValue()).thenReturn(-1);
        assertThat(QueryOptionsEvaluator.apply(all, uriInfo).getEntities()).isEmpty();

        UriInfo plain = mock(UriInfo.class);
        EntityCollection everything = QueryOptionsEvaluator.apply(all, plain);
        assertThat(everything.getEntities()).hasSize(3);
        assertThat(everything.getCount()).isEqualTo(3);
    }

    @Test
    void usesColumnKeysWhenConfigured() {
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(
                column(0, "name", "Edm.String", null, null, true, true),
                column(1, "extra", "Edm.Int32", null, null, true, true)), KeyMode.COLUMNS);
        List<Entity> all = EntityMapper.toEntities(q, TABLE);
        assertThat(all.get(0).getProperty("RowId")).isNull();
        assertThat(all.get(0).getId().toString()).isEqualTo("Q(name='a',extra=1)");
        assertThat(all.get(2).getId().toString()).isEqualTo("Q(name=null,extra=3)");
    }

    private static ResultColumn rc(String name, int type) {
        return new ResultColumn(name, type, "t", 10, 2, true, TypeMapper.map(type, "t", 10, 2));
    }
}
