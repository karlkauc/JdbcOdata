package at.jdbcodata.security;

import at.jdbcodata.audit.LoginAudit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Only users the provider has confirmed reach the audit log: the callback URL is public, so anybody could fill the
 * table with made-up callbacks. Everything else, a wrong client secret for example, is a log entry.
 */
@Component
public class OidcFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcFailureHandler.class);

    private final LoginAudit audit;

    public OidcFailureHandler(LoginAudit audit) {
        this.audit = audit;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("Single sign-on failed: {}", exception.getMessage());
        String result = "/login?ssofailed";
        if (exception instanceof OidcLoginRejected r) {
            audit.oidcLoginRejected(r.username(), request.getRemoteAddr());
            result = "/login?denied";
        }
        response.sendRedirect(request.getContextPath() + result);
    }
}
