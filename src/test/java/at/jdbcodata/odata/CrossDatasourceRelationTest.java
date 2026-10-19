package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A relation whose target lives in another database: {@code XdsSales} runs on the demo datasource,
 * {@code XdsCustomers} on a second H2 database with its own master data. "Alpha GmbH" is missing there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossDatasourceRelationTest extends DemoProfileTest {

    private static final String MASTER_URL = "jdbc:h2:mem:xds-master;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID masterDsId;
    private UUID customersId;
    private UUID salesId;

    @BeforeAll
    void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(MASTER_URL, "demo", "demo"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE customer_master (customer VARCHAR(100) PRIMARY KEY, city VARCHAR(50))");
            st.execute("INSERT INTO customer_master VALUES ('Beta AG', 'Graz')");
        }
        String demoDsId = null;
        for (JsonNode n : json.readTree(get("/api/v1/datasources", ADMIN, ADMIN_PASSWORD).body())) {
            if ("demo".equals(n.get("name").asString())) {
                demoDsId = n.get("id").asString();
            }
        }
        HttpResponse<String> ds = send("POST", "/api/v1/datasources", """
                {"name":"xds_master","jdbcUrl":"%s","driverClass":"org.h2.Driver","defaultTimeoutSec":5,"maxRows":1000}
                """.formatted(MASTER_URL), ADMIN, ADMIN_PASSWORD);
        assertThat(ds.statusCode()).as(ds.body()).isEqualTo(201);
        masterDsId = UUID.fromString(json.readTree(ds.body()).get("id").asString());

        customersId = createQuery("""
                {"name":"XdsCustomers","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT customer, city FROM customer_master ORDER BY customer",
                 "relations":[{"name":"Sales","targetQuery":"XdsSales","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":true,"partner":"Customer"}]}
                """.formatted(masterDsId), "customer");
        salesId = createQuery("""
                {"name":"XdsSales","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT id, customer, amount FROM sales WHERE sale_year = 2025 ORDER BY id",
                 "relations":[{"name":"Customer","targetQuery":"XdsCustomers","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":false,"partner":"Sales"}]}
                """.formatted(demoDsId), "id");
    }

    private UUID createQuery(String body, String keyColumn) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/queries", body, ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        UUID id = UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(send("POST", "/api/v1/queries/" + id + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}",
                ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/v1/queries/" + id + "/columns",
                "{\"columns\":[{\"propertyName\":\"" + keyColumn + "\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD)
                .statusCode()).isEqualTo(200);
        return id;
    }

    @AfterAll
    void tearDown() throws Exception {
        send("DELETE", "/api/v1/queries/" + salesId, null, ADMIN, ADMIN_PASSWORD);
        send("DELETE", "/api/v1/queries/" + customersId, null, ADMIN, ADMIN_PASSWORD);
        send("DELETE", "/api/v1/datasources/" + masterDsId, null, ADMIN, ADMIN_PASSWORD);
    }

    private JsonNode odata(String path) throws Exception {
        HttpResponse<String> r = get(path, "demo", "demo");
        assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    @Test
    void metadataPublishesTheNavigationAcrossDatasources() throws Exception {
        HttpResponse<String> r = get("/odata/$metadata", "demo", "demo");
        assertThat(r.body()).contains("NavigationProperty Name=\"Customer\" Type=\"JdbcOdata.XdsCustomers\"");
    }

    @Test
    void expandJoinsRowsFromTheOtherDatabase() throws Exception {
        JsonNode rows = odata("/odata/XdsSales?$expand=Customer&$orderby=id").get("value");
        assertThat(rows.size()).isEqualTo(24);
        for (JsonNode row : rows) {
            JsonNode customer = row.get("Customer");
            if ("Beta AG".equals(row.get("customer").asString())) {
                assertThat(customer.get("city").asString()).isEqualTo("Graz");
            } else {
                assertThat(customer.isNull()).as("not in the master database → null").isTrue();
            }
        }
        JsonNode beta = odata("/odata/XdsCustomers?$expand=Sales($select=id)").get("value").get(0);
        assertThat(beta.get("Sales").size()).isEqualTo(12);
    }

    @Test
    void navigationPathAndFilterAcrossDatasources() throws Exception {
        JsonNode betaSale = odata("/odata/XdsSales?$filter=Customer/city%20eq%20'Graz'&$top=1").get("value").get(0);
        assertThat(betaSale.get("customer").asString()).isEqualTo("Beta AG");
        JsonNode customer = odata("/odata/XdsSales(" + betaSale.get("id").asInt() + ")/Customer");
        assertThat(customer.get("city").asString()).isEqualTo("Graz");
        assertThat(odata("/odata/XdsSales?$filter=Customer/city%20eq%20'Graz'&$count=true&$top=0")
                .get("@odata.count").asInt()).isEqualTo(12);
    }
}
