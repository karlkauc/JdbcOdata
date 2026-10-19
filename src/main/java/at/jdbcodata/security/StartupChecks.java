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

    public StartupChecks(Environment environment, JdbcOdataProperties props) {
        PlainHttpGuard.check(environment, props);
        if ("framework".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", ""))) {
            // Spring's ForwardedHeaderFilter takes the client-supplied first X-Forwarded-For entry as remote address
            log.warn("server.forward-headers-strategy=framework trusts X-Forwarded-* from any client, so the "
                    + "login rate limit can be bypassed with a forged X-Forwarded-For. Use 'native' behind a reverse proxy.");
        }
    }
}
