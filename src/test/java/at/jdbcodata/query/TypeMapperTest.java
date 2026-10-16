package at.jdbcodata.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.junit.jupiter.api.Test;

class TypeMapperTest {

    @Test
    void mapsIntegerLikeNumericsToIntegers() {
        assertThat(TypeMapper.map(Types.NUMERIC, "NUMBER", 5, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.Int32);
        assertThat(TypeMapper.map(Types.NUMERIC, "NUMBER", 10, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.Int64);
        assertThat(TypeMapper.map(Types.DECIMAL, "DECIMAL", 20, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.Decimal);
    }

    @Test
    void keepsDeclaredDecimalFacets() {
        var m = TypeMapper.map(Types.NUMERIC, "NUMERIC", 12, 2);
        assertThat(m.kind()).isEqualTo(EdmPrimitiveTypeKind.Decimal);
        assertThat(m.precision()).isEqualTo(12);
        assertThat(m.scale()).isEqualTo(2);
        assertThat(m.fqn()).isEqualTo("Edm.Decimal");
    }

    @Test
    void unknownDecimalPrecisionFallsBackToWideFacets() {
        var oracleNumber = TypeMapper.map(Types.NUMERIC, "NUMBER", 0, -127);
        assertThat(oracleNumber.precision()).isEqualTo(38);
        assertThat(oracleNumber.scale()).isEqualTo(10);
        var pgNumeric = TypeMapper.map(Types.NUMERIC, "numeric", 0, 0);
        assertThat(pgNumeric.kind()).isEqualTo(EdmPrimitiveTypeKind.Decimal);
        assertThat(pgNumeric.scale()).isEqualTo(10);
    }

    @Test
    void mapsTemporalAndOtherTypes() {
        assertThat(TypeMapper.map(Types.TIMESTAMP, "TIMESTAMP", 0, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.DateTimeOffset);
        assertThat(TypeMapper.map(Types.TIMESTAMP, "TIMESTAMP", 0, 0).precision()).isEqualTo(TypeMapper.TEMPORAL_PRECISION);
        assertThat(TypeMapper.map(Types.DATE, "DATE", 0, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.Date);
        assertThat(TypeMapper.map(Types.BLOB, "BLOB", 0, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.Binary);
        assertThat(TypeMapper.map(Types.OTHER, "uuid", 0, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.Guid);
        assertThat(TypeMapper.map(Types.OTHER, "json", 0, 0).kind()).isEqualTo(EdmPrimitiveTypeKind.String);
    }

    @Test
    void sanitisesPropertyNames() {
        Set<String> used = new HashSet<>(Set.of("RowId"));
        assertThat(TypeMapper.toPropertyName("Umsatz (EUR)", 0, used)).isEqualTo("Umsatz__EUR_");
        assertThat(TypeMapper.toPropertyName("2024", 1, used)).isEqualTo("_2024");
        assertThat(TypeMapper.toPropertyName("RowId", 2, used)).isEqualTo("RowId_2");
        assertThat(TypeMapper.toPropertyName("", 3, used)).isEqualTo("Column3");
        assertThat(TypeMapper.toPropertyName("customer", 4, used)).isEqualTo("customer");
        assertThat(TypeMapper.toPropertyName("customer", 5, used)).isEqualTo("customer_2");
    }
}
