package at.jdbcodata.mysql;

import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryExecutor;
import at.jdbcodata.testutil.DemoProfileTest;
import at.jdbcodata.testutil.TestData;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole service against a real MySQL or MariaDB through the bundled MariaDB driver. As in PushDownTest, the same
 * query runs with and without push-down and every request must answer identically; the data contains what MySQL
 * collations treat differently from Java: case, umlauts, trailing blanks and LIKE wildcards. The database user is
 * called demo like the login of the demo datasource, which every pass-through request is also checked against.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MysqlFamilyIT extends DemoProfileTest {

    private static final String DATABASE = "shop";
    private static final String USER = "demo";
    private static final String PASSWORD = "demo";
    private static final String ROOT_PASSWORD = "root-pw";
    private static final String SQL = "SELECT id, customer, region, sale_month, amount, sold_at, "
            + "CAST(sold_at AS DATE) AS sold_on, delivered FROM sales WHERE sale_year = 2025";
    private static final String[] CUSTOMERS = {"Alpha GmbH", "Beta AG", "beta ag", "Gamma_%", "Österreich Süd", "Straße 5"};
    private static final String[] REGIONS = {"Nord", "Süd", "nord", "Ost ", "Ost", null};

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<UUID> created = new ArrayList<>();
    private String datasourceId;
    private String push;
    private String memory;

    abstract GenericContainer<?> database();

    /** Waits for the port, because Testcontainers' own check would need a jdbc:mysql driver. */
    static GenericContainer<?> server(String image) {
        return new GenericContainer<>(image).withExposedPorts(3306)
                .withEnv(Map.of("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD, "MYSQL_DATABASE", DATABASE, "MYSQL_USER", USER, "MYSQL_PASSWORD", PASSWORD))
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** The URL an administrator would enter: always the bundled driver's prefix, also for MySQL. */
    private String jdbcUrl() {
        return "jdbc:mariadb://" + database().getHost() + ":" + database().getMappedPort(3306) + "/" + DATABASE
                + "?allowPublicKeyRetrieval=true"; // MySQL's default login without TLS
    }

    private String label() {
        return getClass().getSimpleName().replace("IT", "");
    }

    private Connection root() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), "root", ROOT_PASSWORD);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void seed() throws Exception {
        try (Connection c = root(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sales (id INT PRIMARY KEY, customer VARCHAR(100) NOT NULL, region VARCHAR(20), "
                    + "sale_year INT NOT NULL, sale_month INT NOT NULL, amount DECIMAL(12,2) NOT NULL, "
                    + "sold_at DATETIME NOT NULL, delivered TINYINT(1) NOT NULL) DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE audit_trail (note VARCHAR(20))");
            s.execute("CREATE USER 'limited'@'%' IDENTIFIED BY 'limited-pw'");
            s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
            s.execute("CREATE FUNCTION write_note() RETURNS INT MODIFIES SQL DATA SQL SECURITY INVOKER "
                    + "BEGIN INSERT INTO audit_trail VALUES ('written'); RETURN 1; END");
            s.execute("GRANT SELECT, INSERT ON " + DATABASE + ".* TO '" + USER + "'@'%'");
            s.execute("GRANT EXECUTE ON FUNCTION " + DATABASE + ".write_note TO '" + USER + "'@'%'");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO sales VALUES (?,?,?,?,?,?,?,?)")) {
                for (int id = 1; id <= 48; id++) {
                    p.setInt(1, id);
                    p.setString(2, CUSTOMERS[id % CUSTOMERS.length]);
                    p.setString(3, REGIONS[id % 5 == 0 ? 5 : id % 5]);
                    p.setInt(4, id > 42 ? 2024 : 2025);
                    p.setInt(5, id % 12 + 1);
                    p.setBigDecimal(6, new java.math.BigDecimal(id * 137 % 9000).add(new java.math.BigDecimal(id % 4 == 0 ? "0.50" : "0.25")));
                    p.setString(7, "2025-%02d-%02d %02d:%02d:%02d".formatted(id % 12 + 1, id % 28 + 1, id % 24, id % 60, id * 7 % 60));
                    p.setBoolean(8, id % 3 == 0);
                    p.executeUpdate();
                }
            }
        }
    }

    private String createQuery(String name, boolean pushDown) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/queries", """
                {"name":"%s","datasourceId":"%s","keyMode":"COLUMNS","pushDown":%s,"sql":"%s"}
                """.formatted(name, datasourceId, pushDown, SQL), ADMIN, ADMIN_PASSWORD);
        assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
        UUID id = UUID.fromString(json.readTree(r.body()).get("id").asString());
        created.add(id);
        HttpResponse<String> snapshot = send("POST", "/api/v1/queries/" + id + "/snapshot",
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(USER, PASSWORD), ADMIN, ADMIN_PASSWORD);
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
        HttpResponse<String> columns = send("PUT", "/api/v1/queries/" + id + "/columns",
                "{\"columns\":[{\"propertyName\":\"id\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(columns.statusCode()).as(columns.body()).isEqualTo(200);
        return name;
    }

    @BeforeAll
    void setUpDatabase() throws Exception {
        seed();
        HttpResponse<String> ds = send("POST", "/api/v1/datasources", """
                {"name":"%s","jdbcUrl":"%s","sessionSettings":{"time_zone":"+02:00"}}
                """.formatted(label().toLowerCase(), jdbcUrl()), ADMIN, ADMIN_PASSWORD);
        assertThat(ds.statusCode()).as(ds.body()).isIn(200, 201);
        datasourceId = json.readTree(ds.body()).get("id").asString();
        push = createQuery("Pd" + label(), true);
        memory = createQuery("Mem" + label(), false);
    }

    @AfterAll
    void removeDefinitions() throws Exception {
        for (UUID id : created) send("DELETE", "/api/v1/queries/" + id, null, ADMIN, ADMIN_PASSWORD);
        send("DELETE", "/api/v1/datasources/" + datasourceId, null, ADMIN, ADMIN_PASSWORD);
    }

    private HttpResponse<String> feed(String set, String query) throws Exception {
        return get("/odata/" + set + query, USER, PASSWORD);
    }

    /** Without the parts that name the entity set, rows sorted, numbers by value. */
    private String comparable(HttpResponse<String> r) throws Exception {
        JsonNode n = json.readTree(r.body());
        ((ObjectNode) n).remove("@odata.context");
        ((ObjectNode) n).remove("@odata.nextLink");
        List<String> rows = new ArrayList<>();
        for (JsonNode row : n.get("value")) {
            ((ObjectNode) row).remove("@odata.id");
            rows.add(row.toString().replaceAll("(\\d+)\\.0+(?=[,}\\]])", "$1").replaceAll("(\\d+\\.\\d*?)0+(?=[,}\\]])", "$1"));
        }
        JsonNode count = n.get("@odata.count");
        return (count == null ? "" : "count=" + count + " ") + rows.stream().sorted().toList();
    }

    private void assertSameAnswer(String query) throws Exception {
        HttpResponse<String> pushed = feed(push, query);
        HttpResponse<String> inMemory = feed(memory, query);
        assertThat(inMemory.statusCode()).as(inMemory.body()).isEqualTo(200);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        assertThat(comparable(pushed)).as(query).isEqualTo(comparable(inMemory));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "region eq 'Nord'",
            "region eq 'nord'",
            "region eq 'Ost'",
            "region eq 'Ost '",
            "region ne 'Ost'",
            "region eq null",
            "region ne null and amount gt 3000",
            "not (region eq 'Nord')",
            "customer eq 'beta ag'",
            "customer eq 'Beta AG'",
            "customer eq 'Strasse 5'",
            "customer in ('Alpha GmbH', 'beta ag')",
            "region in ('Ost', 'nord')",
            "region gt 'M'",
            "region gt 'm'",
            "region lt 'Süd'",
            "region le 'Ost'",
            "region ge 'Ost '",
            "customer ge 'Beta' and region le 'Nord'",
            "tolower(region) gt 'n'",
            "tolower(customer) eq 'beta ag'",
            "toupper(region) eq 'NORD'",
            "trim(region) eq 'Ost'",
            "length(region) eq 4",
            "length(region) eq 3",
            "length(customer) eq 14",
            "concat(region, 'x') eq 'Ost x'",
            "substring(customer, 1, 3) eq 'lph'",
            "indexof(customer, 'a') eq 1",
            "contains(customer, 'ETA')",
            "contains(customer, 'eta')",
            "contains(customer, 'ö')",
            "contains(customer, 'ß')",
            "contains(customer, '_')",
            "contains(customer, '%')",
            "startswith(customer, 'Ö')",
            "startswith(tolower(customer), 'beta')",
            "endswith(customer, '_%')",
            "endswith(region, ' ')",
            "amount ge 2000 and amount lt 6000.5",
            "amount eq 137.25",
            "-amount lt -5000",
            "sale_month add 1 gt 10",
            "sale_month mul 2 eq 8",
            "sale_month div 2 eq 3",
            "sale_month mod 5 eq 2",
            "amount div 2 gt 2000",
            "amount mod 7 lt 1",
            "round(amount) gt 3000",
            "floor(amount) eq 137",
            "ceiling(amount) eq 138",
            "sale_month in (1, 3, 5) or delivered",
            "delivered",
            "not delivered",
            "delivered eq true",
            "delivered eq false and sale_month gt 6",
            "year(sold_on) eq 2025 and month(sold_on) le 6",
            "day(sold_on) eq 10",
            "sold_on ge 2025-06-01",
            "year(sold_at) eq 2025",
            "month(sold_at) eq 2",
            "hour(sold_at) eq 10",
            "minute(sold_at) eq 30",
            "second(sold_at) eq 7",
            "sold_at gt 2025-06-01T10:00:00Z",
            "true",
    })
    void filtersAnswerLikeInMemory(String filter) throws Exception {
        assertSameAnswer("?$filter=" + enc(filter) + "&$orderby=id");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?$orderby=region,id&$top=7&$skip=2&$count=true",
            "?$orderby=region%20desc,id&$top=7",
            "?$orderby=customer,id",
            "?$orderby=customer%20desc,id&$top=9",
            "?$filter=amount%20gt%203000&$orderby=sale_month%20desc,id&$skip=3&$count=true",
            "?$filter=region%20gt%20'M'&$orderby=amount,id&$top=3&$count=true",
            "?$search=Beta&$orderby=amount,id&$top=3&$count=true",
            "?$search=beta&$orderby=id",
            "?$search=%C3%96sterreich&$orderby=id",
            "?$search=strasse&$orderby=id",
            "?$top=4&$select=id,amount&$orderby=id",
            "?$skip=10&$orderby=id",
    })
    void pagingAndSearchAnswerLikeInMemory(String query) throws Exception {
        assertSameAnswer(query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "filter(amount gt 3000)/groupby((customer),aggregate(amount with sum as S,$count as N))",
            "aggregate(amount with average as A,amount with min as Mi,amount with max as Ma,region with countdistinct as R,$count as N)",
            "groupby((region))",
            "groupby((region),aggregate(amount with sum as S))",
            "groupby((customer),aggregate(amount with sum as S))",
            "groupby((customer,delivered),aggregate(amount with average as A))",
            "groupby((sold_on),aggregate(amount with sum as S))",
            "groupby((rollup(customer,region)),aggregate(amount with sum as S))",
            "groupby((region),aggregate(customer with min as C,customer with max as D))",
            "groupby((customer),aggregate(amount with sum as S))/filter(S gt 20000)",
            "filter(contains(customer,'eta'))/orderby(amount desc,id)/top(3)",
    })
    void applyAnswersLikeInMemory(String apply) throws Exception {
        assertSameAnswer("?$apply=" + enc(apply));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?$filter=region%20eq%20null", "?$filter=region%20eq%20'Ost'", "?$search=Beta"})
    void countAnswersLikeInMemory(String query) throws Exception {
        HttpResponse<String> pushed = feed(push + "/$count", query);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        assertThat(pushed.body()).as(query).isEqualTo(feed(memory + "/$count", query).body());
    }

    @Test
    void theWorkReallyRunsInTheDatabase() throws Exception {
        assertThat(feed(push, "?$filter=amount%20gt%203000&$orderby=id&$top=2&$count=true").headers().firstValue("X-Push-Down"))
                .hasValue("filter,orderby,top,count");
        assertThat(feed(push, "?$filter=region%20eq%20'Nord'").headers().firstValue("X-Push-Down")).hasValue("filter");
        assertThat(feed(push + "/$count", "?$filter=region%20eq%20null").headers().firstValue("X-Push-Down")).hasValue("filter,count");
        assertThat(feed(memory, "?$top=2").headers().firstValue("X-Push-Down")).isEmpty();
    }

    @Test
    void columnTypesAreMapped() throws Exception {
        String metadata = get("/odata/$metadata", USER, PASSWORD).body();
        String type = metadata.substring(metadata.indexOf("<EntityType Name=\"" + push));
        type = type.substring(0, type.indexOf("</EntityType>"));
        assertThat(type).containsPattern("Name=\"id\"[^>]*Type=\"Edm.Int32\"")
                .containsPattern("Name=\"customer\"[^>]*Type=\"Edm.String\"")
                .containsPattern("Name=\"amount\"[^>]*Type=\"Edm.Decimal\"")
                .containsPattern("Name=\"sold_on\"[^>]*Type=\"Edm.Date\"")
                .containsPattern("Name=\"delivered\"[^>]*Type=\"Edm.Boolean\"");
        JsonNode row = json.readTree(feed(push + "(1)", "").body());
        assertThat(row.get("customer").asString()).isEqualTo("Beta AG");
        assertThat(row.get("delivered").isBoolean()).isTrue();
        assertThat(row.get("sold_on").asString()).isEqualTo("2025-02-02");
    }

    @Test
    void permissionsStayInTheDatabase() throws Exception {
        assertThat(feed(push, "?$top=1").statusCode()).isEqualTo(200);
        assertThat(get("/odata/" + push + "?$top=1", USER, "wrong-password").statusCode()).isEqualTo(401);
        HttpResponse<String> denied = get("/odata/" + push + "?$top=1", "limited", "limited-pw");
        assertThat(denied.statusCode()).as(denied.body()).isIn(401, 403);
        assertThat(denied.body()).doesNotContain("Beta AG");
    }

    @Test
    void connectionsAreReadOnly() throws Exception {
        IdentityPoolManager pools = new IdentityPoolManager();
        try {
            QueryExecutor executor = new QueryExecutor(pools, new ConcurrencyLimiter(), TestData.props(100, 20, ""));
            Datasource ds = TestData.datasource("ro", jdbcUrl());
            ExecutionIdentity identity = new ExecutionIdentity(USER, USER, PASSWORD, ExecutionMode.CALLER);
            QueryDefinition write = query("W", "SELECT write_note() AS x", List.of(), List.of(), KeyMode.ROWID);
            catchThrowable(() -> executor.execute(write, ds, identity, Map.of(), 10, 10));
        } finally {
            pools.closeAll();
        }
        try (Connection c = root(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM audit_trail")) {
            r.next();
            assertThat(r.getInt(1)).isZero();
        }
    }

    @Test
    void sessionSettingsApply() throws Exception {
        IdentityPoolManager pools = new IdentityPoolManager();
        try {
            QueryExecutor executor = new QueryExecutor(pools, new ConcurrencyLimiter(), TestData.props(100, 20, ""));
            Datasource ds = new Datasource(UUID.randomUUID(), "tz", null, jdbcUrl(), null, null, 30, 1000, 10, true,
                    Instant.now(), Instant.now(), Map.of("time_zone", "+02:00", "lc_time_names", "de_DE"), Map.of());
            ExecutionIdentity identity = new ExecutionIdentity(USER, USER, PASSWORD, ExecutionMode.CALLER);
            String sql = "SELECT @@session.time_zone AS tz, MONTHNAME('2025-03-01') AS m";
            QueryDefinition q = query("S", sql, List.of(), List.of(), KeyMode.ROWID);
            for (int i = 0; i < 3; i++) { // same pooled connection after the rollback of the previous run
                Object[] row = executor.execute(q, ds, identity, Map.of(), 10, 10).rows().get(0);
                assertThat(row[0]).isEqualTo("+02:00");
                assertThat(row[1]).isEqualTo("März");
            }
        } finally {
            pools.closeAll();
        }
    }
}
