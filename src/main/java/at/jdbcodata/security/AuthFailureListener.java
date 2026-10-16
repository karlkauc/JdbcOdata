package at.jdbcodata.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Feeds failed admin logins (form and API Basic Auth) into the rate limiter. */
@Component
public class AuthFailureListener {

    private final LoginRateLimiter limiter;

    public AuthFailureListener(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String ip = event.getAuthentication().getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : null;
        limiter.recordFailure(ip, event.getAuthentication().getName());
    }
}
