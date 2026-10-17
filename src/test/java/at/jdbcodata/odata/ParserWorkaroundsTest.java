package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Syntax the Olingo 5 parser rejects and JdbcOdata rewrites before parsing: matchesPattern, $count with $filter,
 * a blank after the lambda colon and key-as-segment. Model: P4Customers (key customer, string) → Sales (to-many).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParserWorkaroundsTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID customersId;
    private final List<JsonNode> sales = new ArrayList<>();

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<String> call(String path) throws Exception {
        return get("/odata/" + path, "demo", "demo");
    }

    private JsonNode ok(String path) throws Exception {
        HttpResponse<String> r = call(path);
        assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    @BeforeAll
    void defineModel() throws Exception {
        String datasourceId = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) datasourceId = n.get("datasourceId").asString();
        }
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"P4Customers","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT customer, MIN(region) AS region FROM sales GROUP BY customer ORDER BY customer",
                 "relations":[{"name":"Sales","targetQuery":"Sales","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":true}]}
                """.formatted(datasourceId), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        customersId = UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(send("POST", "/api/v1/queries/" + customersId + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}",
                ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/v1/queries/" + customersId + "/columns",
                "{\"columns\":[{\"propertyName\":\"customer\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD).statusCode())
                .isEqualTo(200);
        ok("Sales?$orderby=RowId").get("value").forEach(sales::add);
        assertThat(sales).isNotEmpty();
    }

    @AfterAll
    void removeModel() throws Exception {
        send("DELETE", "/api/v1/queries/" + customersId, null, ADMIN, ADMIN_PASSWORD);
    }

    private long salesOf(String customer, java.util.function.Predicate<JsonNode> p) {
        return sales.stream().filter(s -> s.get("customer").asString().equals(customer)).filter(p).count();
    }

    @Test
    void matchesPatternUsesARegularExpression() throws Exception {
        Pattern p = Pattern.compile("^B.*AG$");
        long expected = sales.stream().filter(s -> p.matcher(s.get("customer").asString()).find()).count();
        assertThat(ok("Sales?$filter=" + enc("matchesPattern(customer,'^B.*AG$')")).get("value").size()).isEqualTo((int) expected);
        assertThat(ok("Sales?$filter=" + enc("matchesPattern(customer,'mbh')")).get("value").size()).isZero();
        assertThat(ok("Sales?$filter=" + enc("not matchesPattern(customer,'(?i)mbh')")).get("value").size())
                .isEqualTo((int) (sales.size() - sales.stream().filter(s -> s.get("customer").asString().contains("GmbH")).count()));
        assertThat(ok("P4Customers?$expand=" + enc("Sales($filter=matchesPattern(region,'^S'))")).get("value").size()).isEqualTo(2);
        assertThat(call("Sales?$filter=" + enc("matchesPattern(customer,'(')")).statusCode()).isEqualTo(400);
    }

    @Test
    void countWithFilterInFilterAndOrderBy() throws Exception {
        long alpha = salesOf("Alpha GmbH", s -> s.get("amount").decimalValue().doubleValue() > 5000);
        long beta = salesOf("Beta AG", s -> s.get("amount").decimalValue().doubleValue() > 5000);
        assertThat(alpha).isNotEqualTo(beta);
        long threshold = Math.min(alpha, beta);
        JsonNode r = ok("P4Customers?$filter=" + enc("Sales/$count($filter=amount gt 5000) gt " + threshold));
        assertThat(r.get("value").size()).isEqualTo(1);
        assertThat(r.get("value").get(0).get("customer").asString()).isEqualTo(alpha > beta ? "Alpha GmbH" : "Beta AG");
        JsonNode ordered = ok("P4Customers?$orderby=" + enc("Sales/$count($filter=amount gt 5000) desc"));
        assertThat(ordered.get("value").get(0).get("customer").asString()).isEqualTo(alpha > beta ? "Alpha GmbH" : "Beta AG");
        JsonNode delivered = ok("P4Customers?$filter=" + enc("Sales/$count($filter=delivered and amount gt 5000) eq "
                + salesOf("Beta AG", s -> s.get("delivered").asBoolean() && s.get("amount").decimalValue().doubleValue() > 5000)
                + " and customer eq 'Beta AG'"));
        assertThat(delivered.get("value").size()).isEqualTo(1);
    }

    @Test
    void blankAfterTheLambdaColon() throws Exception {
        assertThat(ok("P4Customers?$filter=" + enc("Sales/any(s: s/amount gt 9000)")).get("value").size())
                .isEqualTo(ok("P4Customers?$filter=" + enc("Sales/any(s:s/amount gt 9000)")).get("value").size());
    }

    @Test
    void keyAsSegment() throws Exception {
        assertThat(ok("Sales/3").toString()).isEqualTo(ok("Sales(3)").toString());
        assertThat(call("Sales/3/amount").body()).isEqualTo(call("Sales(3)/amount").body());
        assertThat(call("P4Customers/Beta%20AG/Sales/$count").body()).isEqualTo(call("P4Customers('Beta%20AG')/Sales/$count").body());
        int betaRow = sales.stream().filter(x -> x.get("customer").asString().equals("Beta AG")).findFirst().orElseThrow()
                .get("RowId").asInt();
        assertThat(ok("P4Customers/Beta%20AG/Sales/" + betaRow + "?$select=customer").get("customer").asString()).isEqualTo("Beta AG");
        assertThat(call("Sales/999").statusCode()).isEqualTo(404);
        assertThat(ok("Sales/$count?$filter=amount%20gt%200").isNumber()).isTrue();
    }
}
