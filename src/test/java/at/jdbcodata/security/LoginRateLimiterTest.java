package at.jdbcodata.security;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.testutil.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LoginRateLimiterTest {

    private static JdbcOdataProperties props(boolean plain, int limit) {
        JdbcOdataProperties p = TestData.props(10, 10, "");
        return new JdbcOdataProperties(p.admin(), p.odata(), p.seed(), p.cache(), p.stats(),
                new JdbcOdataProperties.Security(plain, limit), "", "./data");
    }

    @Test
    void blocksByIpAndByUsername() {
        LoginRateLimiter limiter = new LoginRateLimiter(props(true, 3));
        assertThat(limiter.limit()).isEqualTo(3);
        for (int i = 0; i < 2; i++) limiter.recordFailure("10.0.0.1", "maria");
        assertThat(limiter.isBlocked("10.0.0.1", "maria")).isFalse();
        limiter.recordFailure("10.0.0.1", "Maria");
        assertThat(limiter.isBlocked("10.0.0.1", "other")).as("ip blocked").isTrue();
        assertThat(limiter.isBlocked("10.0.0.2", "MARIA")).as("username blocked, case-insensitive").isTrue();
        assertThat(limiter.isBlocked("10.0.0.2", "other")).isFalse();
        assertThat(limiter.isBlocked("10.0.0.2", null)).isFalse();
        limiter.recordFailure(null, null);
        limiter.recordFailure(null, " ");
        limiter.clear();
        assertThat(limiter.isBlocked("10.0.0.1", "maria")).isFalse();
    }

    @Test
    void zeroDisablesTheLimit() {
        LoginRateLimiter limiter = new LoginRateLimiter(props(true, 0));
        for (int i = 0; i < 50; i++) limiter.recordFailure("1.1.1.1", "x");
        assertThat(limiter.isBlocked("1.1.1.1", "x")).isFalse();
    }

    @Test
    void plainHttpGuard() {
        MockEnvironment env = new MockEnvironment();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PlainHttpGuard.check(env, props(false, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TLS");
        PlainHttpGuard.check(env, props(true, 1));
        env.setProperty("server.ssl.key-store", "certs/x.p12");
        PlainHttpGuard.check(env, props(false, 1));
        env.setProperty("server.ssl.enabled", "false");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PlainHttpGuard.check(env, props(false, 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
