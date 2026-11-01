package at.jdbcodata.audit;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Sign-ins in the audit log. Only interactive logins count as success: API Basic Auth authenticates every request,
 * and feed successes are in the execution log. A problem with the audit table must not lock anybody out.
 */
@Component
public class LoginAudit {

    private static final Logger log = LoggerFactory.getLogger(LoginAudit.class);
    private static final String ADMIN = "ADMIN_LOGIN";
    private static final String FEED = "FEED_LOGIN";

    private final AuditService audit;

    public LoginAudit(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void onLogin(InteractiveAuthenticationSuccessEvent event) {
        write("LOGIN", ADMIN, event.getAuthentication());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        write("LOGIN_FAILED", ADMIN, event.getAuthentication());
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent event) {
        write("LOGOUT", ADMIN, event.getAuthentication());
    }

    /** The feed authenticates without Spring Security, so it reports rejected logins itself. */
    public void feedLoginRejected(String username, String ip) {
        write("LOGIN_FAILED", FEED, username, ip);
    }

    private void write(String action, String type, Authentication authentication) {
        String ip = authentication.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : null;
        write(action, type, authentication.getName(), ip);
    }

    private void write(String action, String type, String username, String ip) {
        try {
            audit.recordAs(username, action, type, ip == null ? null : Map.of("ip", ip));
        } catch (RuntimeException e) {
            log.warn("Could not write audit record {} {}: {}", action, type, e.getMessage());
        }
    }
}
