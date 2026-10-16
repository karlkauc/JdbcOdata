package at.jdbcodata.datasource;

import static at.jdbcodata.testutil.TestData.datasource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    @Test
    void limitsParallelExecutionsPerDatasource() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter();
        Datasource one = new Datasource(UUID.randomUUID(), "one", null, "jdbc:h2:mem:x", null, null, 10, 10, 1, true, Instant.now(), Instant.now());
        assertThat(limiter.available(one)).isEqualTo(1);
        try (ConcurrencyLimiter.Permit p = limiter.acquire(one, Duration.ofMillis(10))) {
            assertThat(limiter.available(one)).isZero();
            assertThatThrownBy(() -> limiter.acquire(one, Duration.ofMillis(50)))
                    .isInstanceOf(TooManyExecutionsException.class).hasMessageContaining("'one'").hasMessageContaining("1 parallel");
            p.close();
            p.close(); // idempotent
            assertThat(limiter.available(one)).isEqualTo(1);
        }
        Datasource other = datasource("other", "jdbc:h2:mem:y");
        try (ConcurrencyLimiter.Permit ignored = limiter.acquire(other, Duration.ofMillis(10))) {
            assertThat(limiter.available(other)).isEqualTo(9);
        }
        // a changed limit replaces the semaphore
        Datasource resized = new Datasource(one.id(), "one", null, "jdbc:h2:mem:x", null, null, 10, 10, 3, true, Instant.now(), Instant.now());
        try (ConcurrencyLimiter.Permit ignored = limiter.acquire(resized, Duration.ofMillis(10))) {
            assertThat(limiter.available(resized)).isEqualTo(2);
        }
        Datasource zero = new Datasource(UUID.randomUUID(), "z", null, "jdbc:h2:mem:z", null, null, 10, 10, 0, true, Instant.now(), Instant.now());
        assertThat(limiter.available(zero)).as("at least one permit").isEqualTo(1);
    }
}
