package at.jdbcodata.query;

import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaSnapshotServiceTest {

    @Test
    void buildsColumnsWithUniqueNamesAndHidesBinary() {
        ResultTable probe = new ResultTable(List.of(
                col("RowId", Types.INTEGER, "INTEGER", 10, 0),
                col("Umsatz (EUR)", Types.NUMERIC, "NUMERIC", 12, 2),
                col("blob", Types.BLOB, "BLOB", 0, 0),
                col("umsatz__eur_", Types.VARCHAR, "VARCHAR", 0, 0)), List.of(), true);
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(), KeyMode.ROWID);

        List<QueryColumn> cols = SchemaSnapshotService.toColumns(q, probe);

        assertThat(cols).extracting(QueryColumn::propertyName).containsExactly("RowId_2", "Umsatz__EUR_", "blob", "umsatz__eur_");
        assertThat(cols).extracting(QueryColumn::name).containsExactly("RowId", "Umsatz (EUR)", "blob", "umsatz__eur_");
        assertThat(cols).extracting(QueryColumn::edmType).containsExactly("Edm.Int32", "Edm.Decimal", "Edm.Binary", "Edm.String");
        assertThat(cols).extracting(QueryColumn::visible).containsExactly(true, true, false, true);
        assertThat(cols.get(1).precision()).isEqualTo(12);
        assertThat(cols.get(1).scale()).isEqualTo(2);
        assertThat(cols).extracting(QueryColumn::ordinal).containsExactly(0, 1, 2, 3);
        assertThat(cols).allMatch(QueryColumn::nullable).noneMatch(QueryColumn::key);
    }

    @Test
    void rowIdIsNotReservedInColumnKeyMode() {
        ResultTable probe = new ResultTable(List.of(col("RowId", Types.INTEGER, "INTEGER", 10, 0)), List.of(), false);
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(), KeyMode.COLUMNS);
        assertThat(SchemaSnapshotService.toColumns(q, probe)).extracting(QueryColumn::propertyName).containsExactly("RowId");
    }

    private static ResultColumn col(String name, int type, String typeName, int precision, int scale) {
        return new ResultColumn(name, type, typeName, precision, scale, true, TypeMapper.map(type, typeName, precision, scale));
    }
}
