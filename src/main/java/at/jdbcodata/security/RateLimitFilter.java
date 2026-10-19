package at.jdbcodata.security;

import at.jdbcodata.identity.BasicCredentials;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Rejects requests from IPs or for usernames that exceeded the failed-login limit: OData and API requests, every
 * request with Basic Auth (the admin UI accepts it too), and the admin login form. Runs before Spring Security.
 * The client IP is {@code getRemoteAddr()}: behind a reverse proxy Tomcat's RemoteIpValve
 * ({@code server.forward-headers-strategy=native}) sets it from {@code X-Forwarded-For} for trusted proxies only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final LoginRateLimiter limiter;

    public RateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (BasicCredentials.fromHeader(request.getHeader("Authorization")).isPresent()) return false;
        // decoded, so /%6fdata/ cannot slip past (path traversal is rejected later by Spring's firewall)
        String path = PATHS.getPathWithinApplication(request);
        boolean loginPost = "POST".equalsIgnoreCase(request.getMethod()) && path.equals("/login");
        return !(path.startsWith("/odata/") || path.equals("/odata") || path.startsWith("/api/") || loginPost);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = BasicCredentials.fromHeader(request.getHeader("Authorization"))
                .map(BasicCredentials::username).orElse(request.getParameter("username"));
        if (limiter.isBlocked(request.getRemoteAddr(), username)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(LoginRateLimiter.WINDOW.toSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"429\",\"message\":\"Too many failed logins. Try again in "
                    + LoginRateLimiter.WINDOW.toSeconds() + " seconds.\"}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
