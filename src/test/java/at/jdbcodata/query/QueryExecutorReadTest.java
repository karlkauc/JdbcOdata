package at.jdbcodata.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.UUID;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QueryExecutorReadTest {

    private static final String URL = "jdbc:h2:mem:readtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final UUID GUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE t (
                        s VARCHAR(10), i INTEGER, b BIGINT, n NUMERIC(10,2), d DOUBLE PRECISION, bo BOOLEAN,
                        dt DATE, tm TIME, ts TIMESTAMP, bin VARBINARY(3), u UUID, c CLOB, big NUMERIC(30,0))""");
            st.execute("INSERT INTO t VALUES ('a', 1, 2, 3.14, 2.5, TRUE, DATE '2025-01-02', TIME '10:11:12', "
                    + "TIMESTAMP '2025-01-02 10:11:12', X'010203', '" + GUID + "', 'clob', 123)");
            st.execute("INSERT INTO t VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
            st.execute("INSERT INTO t VALUES ('c', 3, 4, 5, 6, FALSE, DATE '2025-01-03', TIME '00:00:00', "
                    + "TIMESTAMP '2025-01-03 00:00:00', X'00', '" + GUID + "', 'x', 1)");
        }
    }

    @Test
    void readsAllTypesAndNulls() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM t ORDER BY s NULLS LAST")) {
            ResultTable table = QueryExecutor.read(rs, 10);
            assertThat(table.truncated()).isFalse();
            assertThat(table.rows()).hasSize(3);
            assertThat(table.columns()).extracting(ResultColumn::name)
                    .containsExactly("s", "i", "b", "n", "d", "bo", "dt", "tm", "ts", "bin", "u", "c", "big");
            assertThat(table.columns()).extracting(col -> col.mapping().kind()).containsExactly(
                    EdmPrimitiveTypeKind.String, EdmPrimitiveTypeKind.Int32, EdmPrimitiveTypeKind.Int64,
                    EdmPrimitiveTypeKind.Decimal, EdmPrimitiveTypeKind.Double, EdmPrimitiveTypeKind.Boolean,
                    EdmPrimitiveTypeKind.Date, EdmPrimitiveTypeKind.TimeOfDay, EdmPrimitiveTypeKind.DateTimeOffset,
                    EdmPrimitiveTypeKind.Binary, EdmPrimitiveTypeKind.Guid, EdmPrimitiveTypeKind.String,
                    EdmPrimitiveTypeKind.Decimal);

            Object[] first = table.rows().get(0);
            assertThat(first[0]).isEqualTo("a");
            assertThat(first[1]).isEqualTo(1);
            assertThat(first[2]).isEqualTo(2L);
            assertThat(first[3]).isEqualTo(new BigDecimal("3.14"));
            assertThat(first[4]).isEqualTo(2.5d);
            assertThat(first[5]).isEqualTo(true);
            assertThat(first[6]).isEqualTo(Date.valueOf("2025-01-02"));
            assertThat(first[7]).isEqualTo(Time.valueOf("10:11:12"));
            assertThat(first[8]).isEqualTo(Timestamp.valueOf("2025-01-02 10:11:12"));
            assertThat((byte[]) first[9]).containsExactly(1, 2, 3);
            assertThat(first[10]).isEqualTo(GUID);
            assertThat(first[11]).isEqualTo("clob");
            assertThat(first[12]).isEqualTo(new BigDecimal("123"));

            Object[] nulls = table.rows().get(2);
            assertThat(nulls).containsOnlyNulls();

            assertThat(table.indexOf("BO")).isEqualTo(5);
            assertThat(table.indexOf("missing")).isEqualTo(-1);
        }
    }

    @Test
    void marksTruncationWhenMoreRowsThanLimit() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT s FROM t")) {
            ResultTable table = QueryExecutor.read(rs, 2);
            assertThat(table.truncated()).isTrue();
            assertThat(table.rows()).hasSize(2);
        }
    }
}
