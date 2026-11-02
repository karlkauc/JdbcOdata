package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatasourceCircuitBreakerTest {

    private static final SQLException UNREACHABLE = new SQLException("Connection refused", "08001");

    private final AtomicLong nanos = new AtomicLong();
    private final DatasourceCircuitBreaker breaker = new DatasourceCircuitBreaker(3, 120, 30, nanos::get);
    private final Datasource ds = datasource(UUID.randomUUID(), Instant.EPOCH);

    @Test
    void opensAfterConnectionFailuresInARow() {
        fail(2);
        assertThatCode(() -> breaker.check(ds)).doesNotThrowAnyException();

        fail(1);

        assertThat(breaker.isOpen(ds)).isTrue();
        assertThat(breaker.openCount()).isEqualTo(1);
        assertThatThrownBy(() -> breaker.check(ds)).isInstanceOf(DatasourceUnavailableException.class)
                .isInstanceOf(DbExecutionException.class);
        assertThat(breaker.rejected()).isEqualTo(1);
    }

    @Test
    void successStartsTheCountAgain() {
        fail(2);
        breaker.record(ds, null);
        fail(2);
        assertThat(breaker.isOpen(ds)).isFalse();
    }

    @Test
    void failuresOutsideTheWindowDoNotCount() {
        fail(2);
        seconds(121);
        fail(2);
        assertThat(breaker.isOpen(ds)).isFalse();
        fail(1);
        assertThat(breaker.isOpen(ds)).isTrue();
    }

    @Test
    void onlyOneRequestTriesAfterTheWaitAndASuccessCloses() {
        fail(3);
        seconds(29);
        assertThatThrownBy(() -> breaker.check(ds)).isInstanceOf(DatasourceUnavailableException.class);
        seconds(1);

        assertThatCode(() -> breaker.check(ds)).doesNotThrowAnyException();
        assertThatThrownBy(() -> breaker.check(ds)).isInstanceOf(DatasourceUnavailableException.class);

        breaker.record(ds, null);
        assertThat(breaker.isOpen(ds)).isFalse();
        assertThatCode(() -> breaker.check(ds)).doesNotThrowAnyException();
    }

    @Test
    void aFailedAttemptWaitsAgain() {
        fail(3);
        seconds(30);
        breaker.check(ds);
        breaker.record(ds, UNREACHABLE);

        seconds(29);
        assertThatThrownBy(() -> breaker.check(ds)).isInstanceOf(DatasourceUnavailableException.class);
        seconds(1);
        assertThatCode(() -> breaker.check(ds)).doesNotThrowAnyException();
    }

    @Test
    void aRejectedLoginProvesTheDatabaseAnswers() {
        fail(3);
        seconds(30);
        breaker.check(ds);
        breaker.record(ds, new DbAuthenticationException(ds.name(), new SQLException("password", "28P01")));
        assertThat(breaker.isOpen(ds)).isFalse();
    }

    @Test
    void oneUserWithAWrongPasswordCannotOpenIt() {
        for (int i = 0; i < 10; i++) {
            breaker.record(ds, new DbAuthenticationException(ds.name(), new SQLException("password", "28P01")));
        }
        assertThat(breaker.isOpen(ds)).isFalse();
    }

    @Test
    void anInconclusiveAttemptLetsTheNextOneTry() {
        fail(3);
        seconds(30);
        breaker.check(ds);
        breaker.record(ds, new IllegalStateException("did not finish"));

        assertThat(breaker.isOpen(ds)).isTrue();
        assertThatCode(() -> breaker.check(ds)).doesNotThrowAnyException();
    }

    @Test
    void savingTheDatasourceStartsOver() {
        fail(3);
        Datasource saved = datasource(ds.id(), Instant.EPOCH.plusSeconds(1));
        assertThat(breaker.isOpen(saved)).isFalse();
        assertThatCode(() -> breaker.check(saved)).doesNotThrowAnyException();
    }

    @Test
    void anUnusedDatasourceStopsCountingAfterTheWindow() {
        fail(3);
        seconds(30 + 119);
        assertThat(breaker.openCount()).isEqualTo(1);
        seconds(1);
        assertThat(breaker.openCount()).isZero();
    }

    @Test
    void zeroTurnsItOff() {
        DatasourceCircuitBreaker off = new DatasourceCircuitBreaker(0, 0, 0, nanos::get);
        for (int i = 0; i < 10; i++) off.record(ds, UNREACHABLE);
        assertThatCode(() -> off.check(ds)).doesNotThrowAnyException();
        assertThat(off.isOpen(ds)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"08001", "08006", "08S01"})
    void connectionStatesCount(String state) {
        assertThat(DbErrorClassifier.isConnectionFailure(new SQLException("x", state))).isTrue();
    }

    @Test
    void classifiesFailures() {
        assertThat(DbErrorClassifier.isConnectionFailure(new SQLException("io", null, new ConnectException("refused")))).isTrue();
        assertThat(DbErrorClassifier.isConnectionFailure(new SQLException("io", null, new IOException("reset")))).isTrue();
        assertThat(DbErrorClassifier.isConnectionFailure(
                new DbExecutionException("wrapped", false, new SQLException("x", "08001")))).isTrue();
        // Hikari: timed out because the database cannot be reached, or because all connections are busy
        assertThat(DbErrorClassifier.isConnectionFailure(
                new SQLTransientConnectionException("timeout", "08001", new SQLException("x", "08001")))).isTrue();
        assertThat(DbErrorClassifier.isConnectionFailure(new SQLTransientConnectionException("timeout"))).isFalse();

        assertThat(DbErrorClassifier.isConnectionFailure(new SQLException("syntax", "42601"))).isFalse();
        assertThat(DbErrorClassifier.isConnectionFailure(new SQLException("canceled", "57014"))).isFalse();
        assertThat(DbErrorClassifier.isConnectionFailure(new SQLException("password", "28P01"))).isFalse();
        assertThat(DbErrorClassifier.isConnectionFailure(new IllegalStateException("x"))).isFalse();
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) breaker.record(ds, UNREACHABLE);
    }

    private void seconds(long s) {
        nanos.addAndGet(s * 1_000_000_000L);
    }

    private static Datasource datasource(UUID id, Instant updatedAt) {
        return new Datasource(id, "db", null, "jdbc:postgresql://db/x", null, null, 30, 10, 10, true, Instant.EPOCH, updatedAt,
                Map.of(), Map.of(), 2);
    }
}
