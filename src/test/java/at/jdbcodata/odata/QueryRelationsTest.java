package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relations between queries surface as navigation properties in {@code $metadata}: the demo {@code Sales} feed gets a
 * second query {@code SalesCustomers} and a relation in both directions, defined through the admin API like an operator
 * would do it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryRelationsTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID customersId;
    private UUID salesId;
    private UUID datasourceId;

    private String q(String path) throws Exception {
        return get(path, ADMIN, ADMIN_PASSWORD).body();
    }

    @Test
    @Order(1)
    void relationsAreStoredAndReturnedByTheApi() throws Exception {
        JsonNode queries = json.readTree(q("/api/v1/queries"));
        for (JsonNode n : queries) {
            if ("Sales".equals(n.get("name").asString())) {
                salesId = UUID.fromString(n.get("id").asString());
                datasourceId = UUID.fromString(n.get("datasourceId").asString());
            }
        }
        assertThat(salesId).isNotNull();

        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"SalesCustomers","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT DISTINCT customer, region FROM sales ORDER BY customer",
                 "relations":[{"name":"Sales","targetQuery":"Sales","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":true,"partner":"Customer","description":"Umsaetze des Kunden"}]}
                """.formatted(datasourceId), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        JsonNode body = json.readTree(created.body());
        customersId = UUID.fromString(body.get("id").asString());
        assertThat(body.get("relations")).hasSize(1);
        assertThat(body.get("relations").get(0).get("toMany").asBoolean()).isTrue();

        // Snapshot with the demo login, mark customer as key.
        HttpResponse<String> snap = send("POST", "/api/v1/queries/" + customersId + "/snapshot",
                "{\"username\":\"demo\",\"password\":\"demo\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(snap.statusCode()).as(snap.body()).isEqualTo(200);
        HttpResponse<String> cols = send("PUT", "/api/v1/queries/" + customersId + "/columns",
                "{\"columns\":[{\"propertyName\":\"customer\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(cols.statusCode()).as(cols.body()).isEqualTo(200);

        // The inverse relation on Sales, added without touching anything else (PUT keeps SQL and parameters).
        JsonNode sales = json.readTree(q("/api/v1/queries/" + salesId));
        String update = """
                {"name":"Sales","datasourceId":"%s","sql":%s,"parameters":%s,
                 "relations":[{"name":"Customer","targetQuery":"SalesCustomers","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":false,"partner":"Sales"}]}
                """.formatted(datasourceId, sales.get("sql").toString(), sales.get("parameters").toString());
        HttpResponse<String> updated = send("PUT", "/api/v1/queries/" + salesId, update, ADMIN, ADMIN_PASSWORD);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).get("hasSnapshot").asBoolean()).as("relations do not drop the snapshot").isTrue();
    }

    @Test
    @Order(2)
    void metadataShowsNavigationPropertiesBindingsAndConstraints() throws Exception {
        HttpResponse<String> r = get("/odata/$metadata", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        String xml = r.body();
        assertThat(xml)
                .containsPattern("<NavigationProperty Name=\"Customer\" Type=\"JdbcOdata.SalesCustomers\"[^>]*Partner=\"Sales\"")
                .containsPattern("<ReferentialConstraint Property=\"customer\" ReferencedProperty=\"customer\"\\s*/?>")
                .containsPattern("<NavigationProperty Name=\"Sales\" Type=\"Collection\\(JdbcOdata.Sales\\)\"[^>]*Partner=\"Customer\"")
                .containsPattern("<NavigationPropertyBinding Path=\"Customer\" Target=\"SalesCustomers\"\\s*/?>")
                .containsPattern("<NavigationPropertyBinding Path=\"Sales\" Target=\"Sales\"\\s*/?>");
        // The navigation path resolves to the related customer.
        HttpResponse<String> nav = get("/odata/Sales(1)/Customer", "demo", "demo");
        assertThat(nav.statusCode()).isEqualTo(200);
        assertThat(nav.body()).containsPattern("\"@odata.context\":\"http[^\"]*/odata/\\$metadata#SalesCustomers/\\$entity\"");
    }

    @Test
    @Order(3)
    void relationToAnInvisibleOrMissingTargetIsOmitted() throws Exception {
        // Disable the target: its navigation property and binding must vanish, the feed itself keeps working.
        JsonNode customers = json.readTree(q("/api/v1/queries/" + customersId));
        String disable = """
                {"name":"SalesCustomers","datasourceId":"%s","sql":%s,"keyMode":"COLUMNS","enabled":false}
                """.formatted(datasourceId, customers.get("sql").toString());
        assertThat(send("PUT", "/api/v1/queries/" + customersId, disable, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        String xml = get("/odata/$metadata", "demo", "demo").body();
        assertThat(xml).doesNotContain("NavigationProperty Name=\"Customer\"").doesNotContain("SalesCustomers");
        assertThat(get("/odata/Sales?$top=1", "demo", "demo").statusCode()).isEqualTo(200);
        // Clean up so other tests see the plain demo model.
        assertThat(send("DELETE", "/api/v1/queries/" + customersId, null, ADMIN, ADMIN_PASSWORD).statusCode()).isIn(200, 204);
        JsonNode sales = json.readTree(q("/api/v1/queries/" + salesId));
        String reset = """
                {"name":"Sales","datasourceId":"%s","sql":%s,"parameters":%s,"relations":[]}
                """.formatted(datasourceId, sales.get("sql").toString(), sales.get("parameters").toString());
        assertThat(send("PUT", "/api/v1/queries/" + salesId, reset, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(json.readTree(q("/api/v1/queries/" + salesId)).get("relations")).isEmpty();
    }
}
