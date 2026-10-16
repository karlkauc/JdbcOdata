package at.jdbcodata.odata;

import at.jdbcodata.testutil.DemoProfileTest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end test against the demo profile: the feed must behave the way Excel expects. */
class ODataFeedTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serviceDocumentRequiresBasicAuth() throws Exception {
        HttpResponse<String> r = get("/odata/", null, null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"JdbcOdata\"");
    }

    @Test
    void serviceDocumentListsSeededQuery() throws Exception {
        HttpResponse<String> r = get("/odata/", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"name\":\"Sales\"");
    }

    @Test
    void metadataContainsEntityTypeFromSnapshot() throws Exception {
        HttpResponse<String> r = get("/odata/$metadata", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .contains("EntitySet Name=\"Sales\"")
                .contains("Property Name=\"RowId\" Type=\"Edm.Int64\"")
                .contains("Property Name=\"customer\" Type=\"Edm.String\"")
                .contains("Property Name=\"amount\" Type=\"Edm.Decimal\"")
                .contains("Property Name=\"sold_at\" Type=\"Edm.DateTimeOffset\"")
                .contains("Property Name=\"delivered\" Type=\"Edm.Boolean\"");
    }

    @Test
    void returnsRowsWithTopAndCount() throws Exception {
        HttpResponse<String> r = get("/odata/Sales?$top=2&$count=true", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Cache")).hasValue("DISABLED");
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("@odata.count").asInt()).isEqualTo(24);
        assertThat(body.get("value").size()).isEqualTo(2);
        JsonNode first = body.get("value").get(0);
        assertThat(first.get("RowId").asLong()).isEqualTo(1);
        assertThat(first.get("customer").asString()).isEqualTo("Alpha GmbH");
        assertThat(first.get("sale_year").asInt()).isEqualTo(2025);
        assertThat(first.get("amount").isNumber()).isTrue();
        assertThat(first.get("sold_at").asString()).startsWith("2025-01-13T");
    }

    @Test
    void appliesQueryParameterAndSkip() throws Exception {
        HttpResponse<String> r = get("/odata/Sales?p_year=2024&$skip=10&$count=true", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("@odata.count").asInt()).isEqualTo(12);
        assertThat(body.get("value").size()).isEqualTo(2);
        assertThat(body.get("value").get(0).get("RowId").asLong()).isEqualTo(11);
    }

    @Test
    void selectLimitsProperties() throws Exception {
        HttpResponse<String> r = get("/odata/Sales?$select=customer,amount&$top=1", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode first = json.readTree(r.body()).get("value").get(0);
        assertThat(first.has("customer")).isTrue();
        assertThat(first.has("amount")).isTrue();
        assertThat(first.has("region")).isFalse();
    }

    @Test
    void countSegmentReturnsPlainNumber() throws Exception {
        HttpResponse<String> r = get("/odata/Sales/$count", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body().trim()).isEqualTo("24");
    }

    @Test
    void wrongDatabasePasswordYields401() throws Exception {
        HttpResponse<String> r = get("/odata/Sales", "demo", "wrong");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"JdbcOdata\"");
        assertThat(r.body()).contains("rejected the supplied credentials");
    }

    @Test
    void invalidParameterYields400() throws Exception {
        HttpResponse<String> r = get("/odata/Sales?p_year=abc", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("p_year");
    }

    @Test
    void unsupportedOptionYields501() throws Exception {
        HttpResponse<String> r = get("/odata/Sales?$expand=foo", "demo", "demo");
        assertThat(r.statusCode()).isIn(400, 501);
        r = get("/odata/Sales?$search=foo", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(501);
        r = get("/odata/Sales(1)", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(501);
    }

    @Test
    void unknownEntitySetYields404() throws Exception {
        HttpResponse<String> r = get("/odata/Nope", "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void adminApiRequiresAdminAccount() throws Exception {
        assertThat(get("/api/v1/datasources", null, null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/datasources", "demo", "demo").statusCode()).isEqualTo(401);
        HttpResponse<String> r = get("/api/v1/datasources", "admin", "secret");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"name\":\"demo\"");
        HttpResponse<String> q = get("/api/v1/queries", "admin", "secret");
        assertThat(q.statusCode()).isEqualTo(200);
        assertThat(q.body()).contains("\"feedPath\":\"/odata/Sales\"").contains("\"hasSnapshot\":true");
    }

    @Test
    void healthIsOpen() throws Exception {
        HttpResponse<String> r = get("/actuator/health", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("UP");
    }
}
