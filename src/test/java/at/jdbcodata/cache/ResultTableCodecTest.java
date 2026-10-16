package at.jdbcodata.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.query.ResultColumn;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.TypeMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResultTableCodecTest {

    @Test
    void roundTripsAllValueTypes() {
        Timestamp ts = Timestamp.valueOf("2025-01-02 03:04:05.123456789");
        UUID uuid = UUID.randomUUID();
        ResultTable table = new ResultTable(List.of(
                col("s", Types.VARCHAR, "VARCHAR"), col("n", Types.NUMERIC, "NUMERIC"), col("ts", Types.TIMESTAMP, null)),
                List.of(new Object[] {"x".repeat(70_000), new BigDecimal("1.50"), ts},
                        new Object[] {null, 7, 7L},
                        new Object[] {2.5d, true, Date.valueOf("2025-01-02")},
                        new Object[] {Time.valueOf("10:11:12"), new byte[] {1, 2}, uuid},
                        new Object[] {new StringBuilder("other"), null, null}), true);
        byte[] payload = ResultTableCodec.encode(table);
        ResultTable back = ResultTableCodec.decode(payload);
        assertThat(back.columns()).isEqualTo(table.columns());
        assertThat(back.truncated()).isTrue();
        assertThat(back.rows()).hasSize(5);
        assertThat(back.rows().get(0)[0]).isEqualTo("x".repeat(70_000));
        assertThat(back.rows().get(0)[1]).isEqualTo(new BigDecimal("1.50"));
        assertThat(back.rows().get(0)[2]).isEqualTo(ts);
        assertThat(back.rows().get(1)).containsExactly(null, 7, 7L);
        assertThat(back.rows().get(2)).containsExactly(2.5d, true, Date.valueOf("2025-01-02"));
        assertThat(back.rows().get(3)[0]).isEqualTo(Time.valueOf("10:11:12"));
        assertThat((byte[]) back.rows().get(3)[1]).containsExactly(1, 2);
        assertThat(back.rows().get(3)[2]).isEqualTo(uuid);
        assertThat(back.rows().get(4)[0]).isEqualTo("other");
        assertThat(payload.length).isLessThan(5000);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> ResultTableCodec.decode(new byte[] {1, 2, 3})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cacheKeyIsStableAndUserScoped() {
        UUID q = UUID.randomUUID();
        CacheKey a = CacheKey.of(q, 1, "Maria", Map.of("b", 2L, "a", "x"));
        CacheKey b = CacheKey.of(q, 1, "maria", Map.of("a", "x", "b", 2L));
        assertThat(a).isEqualTo(b);
        assertThat(CacheKey.canonical(Map.of("b", 2L, "a", "x"))).isEqualTo("{a=x,b=2}");
        assertThat(CacheKey.canonical(Map.of())).isEqualTo("{}");
        assertThat(CacheKey.canonical(null)).isEqualTo("{}");
        assertThat(CacheKey.of(q, 1, "other", Map.of())).isNotEqualTo(CacheKey.of(q, 1, "maria", Map.of()));
        assertThat(CacheKey.of(q, 2, "maria", Map.of())).isNotEqualTo(CacheKey.of(q, 1, "maria", Map.of()));
        assertThat(CacheKey.of(q, 1, null, Map.of()).dbUser()).isEmpty();
    }

    private static ResultColumn col(String name, int type, String typeName) {
        return new ResultColumn(name, type, typeName, 10, 2, true, TypeMapper.map(type, typeName, 10, 2));
    }
}
