package at.jdbcodata.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Single sign-on for the admin UI. The identity provider itself is a standard
 * {@code spring.security.oauth2.client} registration; without one, OIDC stays off.
 * {@code localLogin}: password logins of the {@code admin_user} accounts in UI and API.
 * {@code usernameClaim}: claim with the name shown in UI and audit log.
 * {@code roleClaim}: claim holding the groups or app roles; {@code adminValues} and {@code viewerValues} are
 * compared with it exactly. A user matching neither list cannot sign in.
 */
@ConfigurationProperties(prefix = "jdbcodata.security.oidc")
public record OidcProperties(@DefaultValue("true") boolean localLogin,
                             @DefaultValue("preferred_username") String usernameClaim,
                             @DefaultValue("groups") String roleClaim,
                             @DefaultValue List<String> adminValues,
                             @DefaultValue List<String> viewerValues) {
}
