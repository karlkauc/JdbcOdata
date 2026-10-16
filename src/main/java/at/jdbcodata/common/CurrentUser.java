package at.jdbcodata.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Name of the authenticated admin, or {@code system} for startup jobs such as the seed loader. */
public final class CurrentUser {

    public static final String SYSTEM = "system";

    private CurrentUser() {
    }

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return SYSTEM;
        }
        return auth.getName();
    }
}
