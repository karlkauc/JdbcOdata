package at.jdbcodata.query;

import static at.jdbcodata.testutil.TestData.param;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.InvalidRequestException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterBinderTest {

    private final QueryDefinition q = query("Q", "SELECT 1 WHERE a = :p_int AND b = :p_str AND c = :p_int",
            List.of(param("p_int", ParameterType.INT, true, null),
                    param("p_str", ParameterType.STRING, false, "dflt"),
                    param("p_opt", ParameterType.DATE, false, null)),
            List.of(), KeyMode.ROWID);

    @Test
    void convertsEveryType() {
        assertThat(ParameterBinder.convert(param("p", ParameterType.STRING, false, null), " x ")).isEqualTo("x");
        assertThat(ParameterBinder.convert(param("p", ParameterType.INT, false, null), "42")).isEqualTo(42L);
        assertThat(ParameterBinder.convert(param("p", ParameterType.DECIMAL, false, null), "1.50")).isEqualTo(new BigDecimal("1.50"));
        assertThat(ParameterBinder.convert(param("p", ParameterType.BOOLEAN, false, null), "ja")).isEqualTo(true);
        assertThat(ParameterBinder.convert(param("p", ParameterType.BOOLEAN, false, null), "0")).isEqualTo(false);
        assertThat(ParameterBinder.convert(param("p", ParameterType.DATE, false, null), "2025-03-04"))
                .isEqualTo(Date.valueOf(LocalDate.of(2025, 3, 4)));
        assertThat(ParameterBinder.convert(param("p", ParameterType.TIMESTAMP, false, null), "2025-03-04T10:20:30"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2025, 3, 4, 10, 20, 30)));
        assertThat(ParameterBinder.convert(param("p", ParameterType.TIMESTAMP, false, null), "2025-03-04 10:20:30"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2025, 3, 4, 10, 20, 30)));
        assertThat(ParameterBinder.convert(param("p", ParameterType.TIMESTAMP, false, null), "2025-03-04T10:20:30Z"))
                .isEqualTo(Timestamp.from(java.time.Instant.parse("2025-03-04T10:20:30Z")));
    }

    @Test
    void rejectsUnparseableValues() {
        assertThatThrownBy(() -> ParameterBinder.convert(param("p", ParameterType.INT, false, null), "abc"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("p").hasMessageContaining("INT");
        assertThatThrownBy(() -> ParameterBinder.convert(param("p", ParameterType.DECIMAL, false, null), "1,5"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ParameterBinder.convert(param("p", ParameterType.DATE, false, null), "04.03.2025"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ParameterBinder.convert(param("p", ParameterType.BOOLEAN, false, null), "maybe"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("BOOLEAN");
        assertThatThrownBy(() -> ParameterBinder.convert(param("p", ParameterType.TIMESTAMP, false, null), "x"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void resolvesDefaultsAndRequired() {
        Map<String, Object> values = ParameterBinder.resolve(q, Map.of("p_int", "7"));
        assertThat(values).containsEntry("p_int", 7L).containsEntry("p_str", "dflt").containsEntry("p_opt", null);

        values = ParameterBinder.resolve(q, Map.of("p_int", "7", "p_str", "", "p_opt", "2025-01-01"));
        assertThat(values.get("p_str")).isEqualTo("dflt");
        assertThat(values.get("p_opt")).isEqualTo(Date.valueOf("2025-01-01"));

        assertThatThrownBy(() -> ParameterBinder.resolve(q, Map.of()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("p_int");
    }

    @Test
    void ordersByOccurrenceAndRejectsUndeclared() {
        NamedParameterSql parsed = NamedParameterSql.parse(q.sqlText());
        List<ParameterBinder.BoundValue> ordered = ParameterBinder.order(parsed, q, Map.of("p_int", 1L, "p_str", "s"));
        assertThat(ordered).extracting(ParameterBinder.BoundValue::value).containsExactly(1L, "s", 1L);

        NamedParameterSql other = NamedParameterSql.parse("SELECT :nope");
        assertThatThrownBy(() -> ParameterBinder.order(other, q, Map.of()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("nope");
    }

    @Test
    void bindsValuesAndNullsOnRealStatement() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:bind;DB_CLOSE_DELAY=-1")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ? AS s, ? AS i, ? AS d, ? AS b, ? AS dt, ? AS ts, CAST(? AS VARCHAR) AS n")) {
                ParameterBinder.bind(ps, List.of(
                        new ParameterBinder.BoundValue(ParameterType.STRING, "x"),
                        new ParameterBinder.BoundValue(ParameterType.INT, 5L),
                        new ParameterBinder.BoundValue(ParameterType.DECIMAL, new BigDecimal("2.5")),
                        new ParameterBinder.BoundValue(ParameterType.BOOLEAN, true),
                        new ParameterBinder.BoundValue(ParameterType.DATE, Date.valueOf("2025-01-02")),
                        new ParameterBinder.BoundValue(ParameterType.TIMESTAMP, Timestamp.valueOf("2025-01-02 03:04:05")),
                        new ParameterBinder.BoundValue(ParameterType.STRING, null)));
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("s")).isEqualTo("x");
                    assertThat(rs.getLong("i")).isEqualTo(5L);
                    assertThat(rs.getBigDecimal("d")).isEqualByComparingTo("2.5");
                    assertThat(rs.getBoolean("b")).isTrue();
                    assertThat(rs.getDate("dt")).isEqualTo(Date.valueOf("2025-01-02"));
                    assertThat(rs.getTimestamp("ts")).isEqualTo(Timestamp.valueOf("2025-01-02 03:04:05"));
                    assertThat(rs.getString("n")).isNull();
                }
            }
            for (ParameterType t : ParameterType.values()) {
                try (PreparedStatement ps = c.prepareStatement("SELECT ? IS NULL")) {
                    ParameterBinder.bind(ps, List.of(new ParameterBinder.BoundValue(t, null)));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getBoolean(1)).as("null binding for " + t).isTrue();
                    }
                }
            }
        }
    }
}
