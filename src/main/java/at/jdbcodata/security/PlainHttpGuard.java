package at.jdbcodata.security;

import at.jdbcodata.config.JdbcOdataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * NFR-01: Basic Auth sends clear-text credentials, so the service only starts without TLS when
 * {@code jdbcodata.security.allow-plain-http} is set explicitly (reverse proxy terminates TLS, or local demo).
 */
public final class PlainHttpGuard {

    private static final Logger log = LoggerFactory.getLogger(PlainHttpGuard.class);

    private PlainHttpGuard() {
    }

    public static void check(Environment env, JdbcOdataProperties props) {
        boolean ssl = env.getProperty("server.ssl.enabled", Boolean.class, true)
                && (env.containsProperty("server.ssl.key-store") || env.containsProperty("server.ssl.bundle"));
        if (ssl) {
            log.info("TLS is enabled (server.ssl)");
            return;
        }
        if (!props.security().allowPlainHttp()) {
            throw new IllegalStateException("Refusing to start without TLS: Basic Auth would send passwords in clear text. "
                    + "Configure server.ssl.* (see docs/OPERATIONS.md) or set jdbcodata.security.allow-plain-http=true "
                    + "when a reverse proxy terminates TLS or for local demos.");
        }
        log.warn("Running without TLS (jdbcodata.security.allow-plain-http=true). "
                + "Make sure a reverse proxy terminates TLS; otherwise passwords travel in clear text.");
    }
}
