package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilterValuesTest {

    @Test
    void canonicalisesJdbcValues() {
        assertThat(FilterValues.canonical(null)).isNull();
        assertThat(FilterValues.canonical(5)).isEqualTo(BigDecimal.valueOf(5));
        assertThat(FilterValues.canonical(5L)).isEqualTo(BigDecimal.valueOf(5L));
        assertThat(FilterValues.canonical((short) 5)).isEqualTo(BigDecimal.valueOf(5));
        assertThat(FilterValues.canonical((byte) 5)).isEqualTo(BigDecimal.valueOf(5));
        assertThat(FilterValues.canonical(2.5d)).isEqualTo(BigDecimal.valueOf(2.5d));
        assertThat(FilterValues.canonical(2.5f)).isEqualTo(BigDecimal.valueOf(2.5f));
        assertThat(FilterValues.canonical(new java.math.BigInteger("7"))).isEqualTo(new BigDecimal("7"));
        assertThat(FilterValues.canonical(Date.valueOf("2025-01-02"))).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(FilterValues.canonical(Time.valueOf("10:11:12"))).isEqualTo(LocalTime.of(10, 11, 12));
        Instant now = Instant.parse("2025-01-02T03:04:05Z");
        assertThat(FilterValues.canonical(Timestamp.from(now))).isEqualTo(now);
        assertThat(FilterValues.canonical(java.util.Date.from(now))).isEqualTo(now);
        assertThat(FilterValues.canonical(ZonedDateTime.ofInstant(now, ZoneOffset.UTC))).isEqualTo(now);
        UUID u = UUID.randomUUID();
        assertThat(FilterValues.canonical(u)).isEqualTo(u.toString());
        assertThat(FilterValues.canonical("x")).isEqualTo("x");
        assertThat(FilterValues.canonical(true)).isEqualTo(true);
    }

    @Test
    void comparesCompatibleTypesAndNulls() {
        assertThat(FilterValues.compare(null, null)).isZero();
        assertThat(FilterValues.compare(null, "a")).isNegative();
        assertThat(FilterValues.compare("a", null)).isPositive();
        assertThat(FilterValues.compare(BigDecimal.ONE, BigDecimal.TEN)).isNegative();
        assertThat(FilterValues.compare("b", "a")).isPositive();
        assertThat(FilterValues.compare(false, true)).isNegative();
        assertThat(FilterValues.compare(LocalTime.NOON, LocalTime.MIDNIGHT)).isPositive();
        assertThat(FilterValues.compare(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1))).isZero();
        Instant i = Instant.parse("2025-01-01T12:00:00Z");
        assertThat(FilterValues.compare(LocalDate.of(2025, 1, 1), i)).isNegative();
        assertThat(FilterValues.compare(i, LocalDate.of(2025, 1, 2))).isNegative();
        assertThat(FilterValues.compare("a", BigDecimal.ONE)).isNull();
        assertThat(FilterValues.equal("a", "a")).isTrue();
        assertThat(FilterValues.equal("a", BigDecimal.ONE)).isFalse();
        assertThat(FilterValues.zoned("x")).isNull();
        assertThat(FilterValues.zoned(LocalDate.of(2025, 3, 4)).getDayOfMonth()).isEqualTo(4);
    }
}
