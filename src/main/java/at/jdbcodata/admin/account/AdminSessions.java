package at.jdbcodata.admin.account;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Ends admin UI sessions when an account loses access (disabled, deleted, role or password changed). The session
 * of the admin making the change is kept, so changing one's own password does not log out the current tab.
 */
@Component
public class AdminSessions {

    private final SessionRegistry registry;

    public AdminSessions(SessionRegistry registry) {
        this.registry = registry;
    }

    public void expire(String username) {
        String current = currentSessionId();
        for (Object principal : registry.getAllPrincipals()) {
            if (principal instanceof UserDetails u && u.getUsername().equalsIgnoreCase(username)) {
                for (SessionInformation s : registry.getAllSessions(principal, false)) {
                    if (!s.getSessionId().equals(current)) s.expireNow();
                }
            }
        }
    }

    private static String currentSessionId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpSession session = attrs.getRequest().getSession(false);
            return session == null ? null : session.getId();
        }
        return null;
    }
}
