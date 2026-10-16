package at.jdbcodata.security;

import at.jdbcodata.config.JdbcOdataProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Runs the TLS check while the context starts, so a misconfiguration fails fast with a clear message. */
@Component
public class StartupChecks {

    public StartupChecks(Environment environment, JdbcOdataProperties props) {
        PlainHttpGuard.check(environment, props);
    }
}
