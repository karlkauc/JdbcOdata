package at.jdbcodata.security;

import at.jdbcodata.config.JdbcOdataProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counts failed logins per client IP and per username (FR-37); a counter expires one minute after its last
 * failure. An IP is blocked once it reaches the limit. A username is blocked only at {@value #USER_FACTOR} times
 * the limit: that still stops a brute force spread over many addresses, but a single client cannot lock out
 * another user. Blocked requests get 429 until the counter expires.
 */
@Component
public class LoginRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);
    static final int USER_FACTOR = 5;

    private final int limit;
    private final AtomicLong rejected = new AtomicLong();
    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW).maximumSize(100_000).build();

    public LoginRateLimiter(JdbcOdataProperties props) {
        this.limit = props.security().rateLimitPerMinute();
    }

    public void recordFailure(String ip, String username) {
        if (limit <= 0) return;
        if (ip != null) increment("ip:" + ip);
        if (username != null && !username.isBlank()) increment("user:" + username.toLowerCase());
    }

    public boolean isBlocked(String ip, String username) {
        if (limit <= 0) return false;
        return count("ip:" + ip) >= limit
                || (username != null && count("user:" + username.toLowerCase()) >= limit * USER_FACTOR);
    }

    public int limit() {
        return limit;
    }

    public void countRejected() {
        rejected.incrementAndGet();
    }

    /** Requests refused so far because of too many failures. */
    public long rejected() {
        return rejected.get();
    }

    /** A write, so the entry's expiry restarts with every failure. */
    private void increment(String key) {
        failures.asMap().merge(key, new AtomicInteger(1), (old, one) -> new AtomicInteger(old.get() + 1));
    }

    private int count(String key) {
        AtomicInteger c = failures.getIfPresent(key);
        return c == null ? 0 : c.get();
    }

    /** Successful logins do not reset counters on purpose: a slow brute force must not be able to "refresh". */
    public void clear() {
        failures.invalidateAll();
    }
}
