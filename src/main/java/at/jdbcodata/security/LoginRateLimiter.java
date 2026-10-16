package at.jdbcodata.security;

import at.jdbcodata.config.JdbcOdataProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Counts failed logins per client IP and per username in a sliding one-minute window (FR-37). Once the limit is
 * reached, further requests from that IP or for that username are rejected with 429 until the window expires.
 */
@Component
public class LoginRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW).maximumSize(100_000).build();

    public LoginRateLimiter(JdbcOdataProperties props) {
        this.limit = props.security().rateLimitPerMinute();
    }

    public void recordFailure(String ip, String username) {
        if (limit <= 0) return;
        if (ip != null) failures.get("ip:" + ip, k -> new AtomicInteger()).incrementAndGet();
        if (username != null && !username.isBlank()) {
            failures.get("user:" + username.toLowerCase(), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    public boolean isBlocked(String ip, String username) {
        if (limit <= 0) return false;
        return count("ip:" + ip) >= limit || (username != null && count("user:" + username.toLowerCase()) >= limit);
    }

    public int limit() {
        return limit;
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
