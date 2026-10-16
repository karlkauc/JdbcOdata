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

/**
 * Rejects requests from IPs or for usernames that exceeded the failed-login limit: OData and API requests with
 * Basic Auth, and the admin login form. Runs before Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter limiter;

    public RateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean loginPost = "POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/login");
        return !(path.contains("/odata/") || path.contains("/api/") || loginPost);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = BasicCredentials.fromHeader(request.getHeader("Authorization"))
                .map(BasicCredentials::username).orElse(request.getParameter("username"));
        if (limiter.isBlocked(clientIp(request), username)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(LoginRateLimiter.WINDOW.toSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"429\",\"message\":\"Too many failed logins. Try again in "
                    + LoginRateLimiter.WINDOW.toSeconds() + " seconds.\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
