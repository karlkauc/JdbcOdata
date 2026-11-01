package at.jdbcodata.apikey;

import at.jdbcodata.audit.LoginAudit;
import at.jdbcodata.security.LoginRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Signs in REST API requests with a key that has an admin role. Part of the API chain, not a servlet filter. */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService keys;
    private final LoginRateLimiter limiter;
    private final LoginAudit audit;

    public ApiKeyAuthenticationFilter(ApiKeyService keys, LoginRateLimiter limiter, LoginAudit audit) {
        this.keys = keys;
        this.limiter = limiter;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> secret = ApiKeyService.keyIn(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (secret.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiKey> key = keys.authenticate(secret.get()).filter(k -> k.adminRole() != null);
        if (key.isEmpty()) {
            limiter.recordFailure(request.getRemoteAddr(), null); // all keys share one user name: nobody may block it
            audit.adminKeyRejected(request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(key.get().caller(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + key.get().adminRole().name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
