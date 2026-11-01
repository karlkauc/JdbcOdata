package at.jdbcodata.security;

import at.jdbcodata.config.JdbcOdataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Runs the TLS check while the context starts, so a misconfiguration fails fast with a clear message. */
@Component
public class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    public StartupChecks(Environment environment, JdbcOdataProperties props, OidcProperties oidc, OidcClients clients) {
        PlainHttpGuard.check(environment, props);
        if (!oidc.localLogin() && (!clients.configured() || oidc.adminValues().isEmpty())) {
            throw new IllegalStateException("jdbcodata.security.oidc.local-login=false would lock everybody out: it needs "
                    + "an OIDC client registration and jdbcodata.security.oidc.admin-values (see docs/OPERATIONS.md).");
        }
        if (clients.configured() && oidc.adminValues().isEmpty() && oidc.viewerValues().isEmpty()) {
            log.warn("OIDC is configured without jdbcodata.security.oidc.admin-values or viewer-values, so every "
                    + "single sign-on will be rejected.");
        }
        if ("framework".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", ""))) {
            // Spring's ForwardedHeaderFilter takes the client-supplied first X-Forwarded-For entry as remote address
            log.warn("server.forward-headers-strategy=framework trusts X-Forwarded-* from any client, so the "
                    + "login rate limit can be bypassed with a forged X-Forwarded-For. Use 'native' behind a reverse proxy.");
        }
    }
}
