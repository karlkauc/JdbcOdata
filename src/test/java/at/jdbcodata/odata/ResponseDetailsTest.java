package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * $compute on single entities, metadata level and IEEE754Compatible for $apply results, $deltatoken, vocabulary
 * annotations in $metadata and $expand with /$ref and /$count. Model: P5Customers (key customer) → Sales (to-many).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseDetailsTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID customersId;

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<String> call(String path) throws Exception {
        return get("/odata/" + path, "demo", "demo");
    }

    private HttpResponse<String> call(String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/" + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("demo:demo".getBytes(StandardCharsets.UTF_8)))
                .header("Accept", accept).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.uri() + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode ok(String path) throws Exception {
        return ok(call(path));
    }

    @BeforeAll
    void defineModel() throws Exception {
        String datasourceId = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) datasourceId = n.get("datasourceId").asString();
        }
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"P5Customers","description":"Kunden der Demo","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT customer, MIN(region) AS region FROM sales GROUP BY customer ORDER BY customer",
                 "relations":[{"name":"Sales","targetQuery":"Sales","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":true,"description":"Umsaetze des Kunden"}]}
                """.formatted(datasourceId), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        customersId = UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(send("POST", "/api/v1/queries/" + customersId + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}",
                ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/v1/queries/" + customersId + "/columns",
                "{\"columns\":[{\"propertyName\":\"customer\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD).statusCode())
                .isEqualTo(200);
    }

    @AfterAll
    void removeModel() throws Exception {
        send("DELETE", "/api/v1/queries/" + customersId, null, ADMIN, ADMIN_PASSWORD);
    }

    @Test
    void computeOnASingleEntity() throws Exception {
        JsonNode plain = ok("Sales(3)");
        JsonNode r = ok("Sales(3)?$compute=" + enc("amount mul 2 as Doppelt") + "&$select=RowId,Doppelt");
        assertThat(r.get("Doppelt").decimalValue()).isEqualByComparingTo(plain.get("amount").decimalValue().multiply(BigDecimal.TWO));
        assertThat(r.has("amount")).as("$select").isFalse();
        assertThat(r.get("@odata.context").asString()).endsWith("/$entity");
        assertThat(ok("Sales/3?$compute=" + enc("amount add 1 as P")).has("P")).isTrue();
        JsonNode nav = ok("P5Customers('Beta%20AG')/Sales(13)?$compute=" + enc("sale_month mul 10 as M"));
        assertThat(nav.get("M").asInt()).isEqualTo(nav.get("sale_month").asInt() * 10);
        JsonNode withExpand = ok("P5Customers('Beta%20AG')?$compute=" + enc("length(customer) as L") + "&$expand=Sales($top=1)");
        assertThat(withExpand.get("L").asInt()).isEqualTo(7);
        assertThat(withExpand.get("Sales").size()).isEqualTo(1);
        assertThat(call("Sales(3)?$compute=" + enc("nope mul 2 as X")).statusCode()).isEqualTo(400);
    }

    @Test
    void applyResultsHonourMetadataLevelAndIeee754() throws Exception {
        String agg = "Sales?$apply=" + enc("aggregate(amount with sum as S,$count as N)");
        JsonNode numbers = ok(agg);
        assertThat(numbers.get("value").get(0).get("S").isNumber()).isTrue();
        HttpResponse<String> strings = call(agg, "application/json;IEEE754Compatible=true");
        JsonNode s = ok(strings);
        assertThat(s.get("value").get(0).get("S").isString()).isTrue();
        assertThat(new BigDecimal(s.get("value").get(0).get("S").asString()))
                .isEqualByComparingTo(numbers.get("value").get(0).get("S").decimalValue());
        assertThat(strings.headers().firstValue("Content-Type").orElse("")).contains("IEEE754Compatible=true");

        String filtered = "Sales?$apply=" + enc("filter(amount gt 0)") + "&$top=1";
        JsonNode full = ok(call(filtered, "application/json;odata.metadata=full"));
        JsonNode row = full.get("value").get(0);
        assertThat(row.get("@odata.type").asString()).isEqualTo("#JdbcOdata.Sales");
        assertThat(row.get("@odata.id").asString()).startsWith("Sales(");
        assertThat(row.get("amount@odata.type").asString()).isEqualTo("#Decimal");
        assertThat(row.get("RowId@odata.type").asString()).isEqualTo("#Int64");
        assertThat(row.has("customer@odata.type")).isFalse();
        JsonNode none = ok(call(filtered, "application/json;odata.metadata=none"));
        assertThat(none.has("@odata.context")).isFalse();
        assertThat(none.get("value").get(0).has("@odata.id")).isFalse();
        // Olingo's own serializer for comparison: same rule for Int64/Decimal
        assertThat(ok(call("Sales?$top=1", "application/json;IEEE754Compatible=true")).get("value").get(0).get("amount").isString()).isTrue();
    }

    @Test
    void keyAfterACollectionNavigationSelectsThatEntity() throws Exception {
        JsonNode beta = ok("P5Customers('Beta%20AG')/Sales?$orderby=RowId%20desc&$top=1");
        int rowId = beta.get("value").get(0).get("RowId").asInt();
        assertThat(ok("P5Customers('Beta%20AG')/Sales(" + rowId + ")").get("RowId").asInt()).isEqualTo(rowId);
        int alphaRow = ok("P5Customers('Alpha%20GmbH')/Sales?$top=1").get("value").get(0).get("RowId").asInt();
        assertThat(call("P5Customers('Beta%20AG')/Sales(" + alphaRow + ")").statusCode()).isEqualTo(404);
    }

    @Test
    void unknownDeltaTokenIsGone() throws Exception {
        HttpResponse<String> r = call("Sales?$deltatoken=abc");
        assertThat(r.statusCode()).isEqualTo(410);
        assertThat(r.body()).contains("delta link");
        assertThat(call("Sales(1)?$deltatoken=abc").statusCode()).isEqualTo(400);
    }

    @Test
    void metadataCarriesDescriptionsAndCapabilities() throws Exception {
        String md = call("$metadata").body();
        assertThat(md).contains("Org.OData.Core.V1.xml").contains("Alias=\"Core\"").contains("Alias=\"Capabilities\"");
        assertThat(md).containsPattern("<EntityType Name=\"P5Customers\">.*?<Annotation Term=\"Core.Description\"><String>Kunden der Demo</String>");
        assertThat(md).contains("<String>Umsaetze des Kunden</String>");
        assertThat(md).contains("<Parameter Name=\"p_year\" Type=\"Edm.Int64\"><Annotation Term=\"Core.Description\"><String>Sales year (Default: 2025)</String>");
        assertThat(md).contains("Term=\"Capabilities.InsertRestrictions\"").contains("<PropertyValue Property=\"Insertable\"><Bool>false</Bool>")
                .contains("Term=\"Capabilities.KeyAsSegmentSupported\"").contains("Term=\"Capabilities.CountRestrictions\"");
        assertThat(call("$metadata?$format=json").body()).contains("@Core.Description").contains("@Capabilities.InsertRestrictions");
    }

    @Test
    void expandWithRefAndCount() throws Exception {
        JsonNode refs = ok("P5Customers?$expand=Sales/$ref&$filter=" + enc("customer eq 'Beta AG'"));
        JsonNode sales = refs.get("value").get(0).get("Sales");
        assertThat(sales.size()).isPositive();
        assertThat(sales.get(0).get("@odata.id").asString()).startsWith("Sales(");
        assertThat(sales.get(0).has("amount")).isFalse();
        JsonNode star = ok("P5Customers?$expand=*/$ref&$top=1");
        assertThat(star.get("value").get(0).get("Sales").get(0).has("@odata.id")).isTrue();
        assertThat(star.get("value").get(0).get("Sales").get(0).has("amount")).isFalse();

        int total = ok("P5Customers('Beta%20AG')/Sales/$count".replace("/$count", "") + "?$count=true").get("@odata.count").asInt();
        JsonNode count = ok("P5Customers?$expand=Sales/$count&$filter=" + enc("customer eq 'Beta AG'"));
        assertThat(count.get("value").get(0).get("Sales@odata.count").asInt()).isEqualTo(total);
        assertThat(count.get("value").get(0).has("Sales")).isFalse();
        assertThat(count.get("@odata.context").asString()).doesNotContain("null");
        int big = Integer.parseInt(call("P5Customers('Beta%20AG')/Sales/$count?$filter=" + enc("amount gt 5000")).body());
        JsonNode filtered = ok("P5Customers?$expand=" + enc("Sales/$count($filter=amount gt 5000)") + "&$filter=" + enc("customer eq 'Beta AG'"));
        assertThat(filtered.get("value").get(0).get("Sales@odata.count").asInt()).isEqualTo(big);
        // dynamic writer path ($apply keeps the entities)
        JsonNode dyn = ok("P5Customers?$apply=" + enc("filter(customer eq 'Beta AG')") + "&$expand=Sales/$count");
        assertThat(dyn.get("value").get(0).get("Sales@odata.count").asInt()).isEqualTo(total);
        JsonNode dynRef = ok("P5Customers?$apply=" + enc("filter(customer eq 'Beta AG')") + "&$expand=Sales/$ref");
        assertThat(dynRef.get("value").get(0).get("Sales").get(0).has("@odata.id")).isTrue();
        assertThat(ok("P5Customers('Beta%20AG')?$expand=Sales/$count").get("Sales@odata.count").asInt()).isEqualTo(total);
    }
}
