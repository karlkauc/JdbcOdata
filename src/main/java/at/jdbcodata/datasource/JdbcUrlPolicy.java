package at.jdbcodata.datasource;

import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.config.JdbcOdataProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which JDBC URLs an admin may configure. A JDBC URL is code: embedded H2 runs {@code INIT=RUNSCRIPT} and
 * {@code FILE_READ} inside this JVM, other drivers load classes or local files named in URL options. Only known
 * network drivers are allowed (plus {@code jdbcodata.security.allowed-jdbc-urls} for drivers in {@code lib/}),
 * options that load code, run SQL on connect or weaken read-only mode are rejected, and a driver class must be a
 * {@link Driver}.
 */
@Component
public class JdbcUrlPolicy {

    /** Not H2 over TCP: its server can hand this JVM serialized Java objects, so it needs an explicit prefix. */
    static final List<String> DEFAULT_PREFIXES = List.of("jdbc:postgresql:", "jdbc:mariadb:", "jdbc:oracle:thin:");

    /** Lower-case option names that load classes or files, run SQL on connect or switch off read-only mode. */
    static final Set<String> FORBIDDEN_OPTIONS = Set.of(
            "init", "authenticator", "trace_level_file",
            "socketfactory", "socketfactoryarg", "sslfactory", "sslfactoryarg", "sslhostnameverifier",
            "sslpasswordcallback", "authenticationpluginclassname", "loggerfile", "readonlymode",
            "allowlocalinfile", "allowloadlocalinfile", "allowurlinlocalinfile", "allowmultiqueries", "initsql",
            "autodeserialize", "queryinterceptors", "statementinterceptors", "connectionlifecycleinterceptors",
            "connectexecutor", "connectexecutorarg", "classloaderstrategy", "xmlfactoryfactory", "gsslib", "jaaslogin",
            "options", "service", "sessionvariables", "credentialtype", "tlssockettype", "localsocket", "pipe");

    /** {@code password=}/{@code pwd=} options, {@code //user:pass@host} and Oracle's {@code thin:user/pass@host}. */
    private static final Pattern CREDENTIALS_IN_URL = Pattern.compile(
            "[;?&:]\\s*(password|pwd|sslpassword|keystorepassword|truststorepassword)\\s*=|//[^/@?;]*:[^/@?;]*@|^jdbc:oracle:thin:[^@/]+/[^@]+@");

    private static final Pattern DRIVER_PROPERTY_NAME = Pattern.compile("[a-z0-9_.\\-]{1,200}");
    private static final Pattern CREDENTIAL_PROPERTY = Pattern.compile("password|passwd|pwd|secret|credential|token");

    private static final Pattern OPTION = Pattern.compile("[;?&]\\s*([A-Za-z0-9_.\\-]+)\\s*=");

    private final List<String> prefixes;
    private final boolean allowEmbeddedH2;

    @Autowired
    public JdbcUrlPolicy(JdbcOdataProperties props) {
        this(props.security().allowedJdbcUrls(), props.security().allowEmbeddedH2());
    }

    JdbcUrlPolicy(List<String> extraPrefixes, boolean allowEmbeddedH2) {
        List<String> all = new ArrayList<>(DEFAULT_PREFIXES);
        if (extraPrefixes != null) {
            extraPrefixes.stream().filter(p -> p != null && !p.isBlank()).map(p -> p.trim().toLowerCase(Locale.ROOT))
                    .forEach(all::add);
        }
        this.prefixes = List.copyOf(all);
        this.allowEmbeddedH2 = allowEmbeddedH2;
    }

    public void check(String jdbcUrl, String driverClass) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) throw new InvalidRequestException("JDBC URL is required");
        String url = decode(jdbcUrl.strip()).toLowerCase(Locale.ROOT);
        if (!(isEmbeddedH2(jdbcUrl.strip(), url) ? allowEmbeddedH2 : prefixes.stream().anyMatch(url::startsWith))) {
            throw new InvalidRequestException("JDBC URL not allowed. Allowed: " + String.join(", ", prefixes)
                    + " (more via jdbcodata.security.allowed-jdbc-urls; embedded H2 only with allow-embedded-h2)");
        }
        // credentials belong into pass-through logins, stored credentials or technical accounts: the URL is shown
        // to VIEWER admins and written to the audit log
        if (CREDENTIALS_IN_URL.matcher(url).find()) {
            throw new InvalidRequestException("The JDBC URL must not contain a password; use the login fields instead");
        }
        Matcher m = OPTION.matcher(url);
        while (m.find()) {
            if (FORBIDDEN_OPTIONS.contains(m.group(1))) {
                throw new InvalidRequestException("JDBC URL option '" + m.group(1) + "' is not allowed");
            }
        }
        if (url.contains("runscript") || url.contains("create alias")) {
            throw new InvalidRequestException("JDBC URL must not contain SQL");
        }
        if (driverClass != null && !driverClass.isBlank()) checkDriverClass(driverClass.strip());
    }

    /**
     * Decided on the raw URL, because H2 matches {@code tcp:} and {@code ssl:} case-sensitively and without decoding:
     * {@code jdbc:h2:TCP:/x} and {@code jdbc:h2:%74cp:/x} open an embedded file database.
     */
    private static boolean isEmbeddedH2(String rawUrl, String normalizedUrl) {
        return normalizedUrl.startsWith("jdbc:h2:")
                && !rawUrl.startsWith("jdbc:h2:tcp:") && !rawUrl.startsWith("jdbc:h2:ssl:");
    }

    /**
     * Driver properties are passed to {@link Driver#connect} like URL options, so the same options are forbidden;
     * credentials belong into the login fields, as in the URL.
     */
    public void checkDriverProperties(Map<String, String> properties) {
        if (properties == null) return;
        properties.forEach((key, value) -> {
            String k = key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
            if (!DRIVER_PROPERTY_NAME.matcher(k).matches()) {
                throw new InvalidRequestException("Driver property name '" + key + "' is invalid");
            }
            String last = k.substring(k.lastIndexOf('.') + 1);
            if (FORBIDDEN_OPTIONS.contains(k) || FORBIDDEN_OPTIONS.contains(last)) {
                throw new InvalidRequestException("Driver property '" + key + "' is not allowed");
            }
            if (k.equals("user") || CREDENTIAL_PROPERTY.matcher(k).find()) {
                throw new InvalidRequestException("Driver property '" + key + "' looks like a credential; use the login fields instead");
            }
            if (value == null || value.length() > 500 || value.chars().anyMatch(Character::isISOControl)) {
                throw new InvalidRequestException("Driver property '" + key + "': value must be at most 500 characters on one line");
            }
            String v = decode(value).toLowerCase(Locale.ROOT);
            if (v.contains("runscript") || v.contains("create alias")) {
                throw new InvalidRequestException("Driver property '" + key + "' must not contain SQL");
            }
        });
    }

    /** Loads the class without initialising it, so no static initialiser of an arbitrary class runs. */
    private static void checkDriverClass(String name) {
        try {
            Class<?> c = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            if (!Driver.class.isAssignableFrom(c)) {
                throw new InvalidRequestException("Driver class '" + name + "' is not a java.sql.Driver");
            }
        } catch (ClassNotFoundException | LinkageError e) {
            throw new InvalidRequestException("JDBC driver class not found: " + name);
        }
    }

    /** Percent-decodes repeatedly, so double-encoded option names are caught too; '+' stays as it is. */
    private static String decode(String url) {
        String current = url;
        for (int i = 0; i < 3 && current.indexOf('%') >= 0; i++) {
            try {
                String next = URLDecoder.decode(current.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (next.equals(current)) break;
                current = next;
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        return current;
    }
}
