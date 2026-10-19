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

    @Test
    void detectsWhatArraysAndJsonColumnsCanBePublishedAs() {
        ResultTable probe = new ResultTable(List.of(
                col("tags", Types.ARRAY, "_text", 0, 0),
                col("mengen", Types.ARRAY, "_int4", 0, 0),
                col("positionen", Types.OTHER, "jsonb", 0, 0),
                col("werte", Types.VARCHAR, "VARCHAR", 0, 0),
                col("text", Types.VARCHAR, "VARCHAR", 0, 0),
                col("gemischt", Types.VARCHAR, "VARCHAR", 0, 0)), List.of(
                new Object[] {List.of("a", "b"), List.of(1, 2),
                        "[{\"Artikel\":\"Schraube\",\"Menge\":3,\"Preis\":1.5,\"Tags\":[\"x\"],\"Info\":{\"a\":1}},{\"Artikel\":\"Mutter\",\"Datum\":\"2025-01-02\"}]",
                        "[1, 2.5]", "kein JSON", "[1, {\"a\":1}]"},
                new Object[] {null, null, null, null, "[1]", null}), false);
        QueryDefinition q = query("Q", "SELECT 1", List.of(), List.of(), KeyMode.ROWID);

        List<QueryColumn> cols = SchemaSnapshotService.toColumns(q, probe);

        assertThat(cols).extracting(QueryColumn::elementType).containsExactly("Edm.String", "Edm.Int32", null, "Edm.Decimal", null, null);
        assertThat(cols).allMatch(c -> c.collection() == CollectionKind.NONE);
        assertThat(cols.get(2).structure()).extracting(ComplexField::name).containsExactly("Artikel", "Menge", "Preis", "Tags", "Info", "Datum");
        assertThat(cols.get(2).structure()).extracting(ComplexField::edmType)
                .containsExactly("Edm.String", "Edm.Int64", "Edm.Decimal", "Edm.String", "Edm.String", "Edm.Date");
        assertThat(cols.get(2).structure()).extracting(ComplexField::collection).containsExactly(false, false, false, true, false, false);
        assertThat(cols.get(4).canBeComplexCollection() || cols.get(4).canBePrimitiveCollection()).isFalse();
    }

    @Test
    void collectionValuesConvertAndPrintAsBefore() {
        assertThat(CollectionShape.asList("{a,\"b c\",NULL}")).containsExactly("a", "b c", null);
        assertThat(CollectionShape.literal(java.util.Arrays.asList("a", "b c", null, true))).isEqualTo("{a,\"b c\",NULL,t}");
        assertThat(CollectionShape.convert(new java.math.BigDecimal("3"), org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.Int32)).isEqualTo(3);
        assertThat(CollectionShape.convert("x", org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.Int32)).isNull();
        assertThat(CollectionShape.convert(new java.math.BigDecimal("1.123456789012"), org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.Decimal))
                .isEqualTo(new java.math.BigDecimal("1.1234567890"));
        assertThat(CollectionShape.convert("2025-01-02T10:00:00Z", org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.DateTimeOffset))
                .isEqualTo(java.sql.Timestamp.from(java.time.Instant.parse("2025-01-02T10:00:00Z")));
        assertThat(CollectionShape.convert(java.util.Map.of("a", 1), org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.String)).isEqualTo("{\"a\":1}");
    }

    private static ResultColumn col(String name, int type, String typeName, int precision, int scale) {
        return new ResultColumn(name, type, typeName, precision, scale, true, TypeMapper.map(type, typeName, precision, scale));
    }
}
