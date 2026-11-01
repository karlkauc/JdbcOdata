package at.jdbcodata.security;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** The identity provider has confirmed this user, but this service does not let them in. */
public class OidcLoginRejected extends OAuth2AuthenticationException {

    private final String username;

    OidcLoginRejected(String username, String reason) {
        super(new OAuth2Error("access_denied"), reason);
        this.username = username;
    }

    public String username() {
        return username;
    }
}
