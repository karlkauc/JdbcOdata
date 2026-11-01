package at.jdbcodata.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UrlPathHelper;

/**
 * True for a Prometheus scrape with {@code Authorization: Bearer <token>}, which then needs no admin login. Selects
 * its own stateless security chain, because scrapes through the UI chain would each open a session. Anything else
 * stays with the normal login.
 */
public class MetricsTokenMatcher implements RequestMatcher {

    public static final String PATH = "/actuator/prometheus";
    /** A guessable token would be worse than a login: nothing limits attempts on it. */
    public static final int MIN_LENGTH = 32;

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final byte[] token;

    public MetricsTokenMatcher(String token) {
        if (!token.isEmpty() && token.length() < MIN_LENGTH) {
            throw new IllegalStateException("jdbcodata.security.metrics-token needs at least " + MIN_LENGTH + " characters");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (token.length == 0 || !PATH.equals(PATHS.getPathWithinApplication(request))) return false;
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }
}
