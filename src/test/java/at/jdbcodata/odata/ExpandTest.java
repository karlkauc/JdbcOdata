package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code $expand} along declared relations on the demo data: {@code Sales.Customer → ExpCustomers} (to-one) and
 * {@code ExpCustomers.Sales → Sales} (to-many). The customer query leaves out "Alpha GmbH", so Alpha's sales have no
 * customer to expand.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpandTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID customersId;
    private UUID salesId;
    private UUID datasourceId;
    private JsonNode salesBefore;

    @BeforeAll
    void defineRelations() throws Exception {
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) {
                salesId = UUID.fromString(n.get("id").asString());
                datasourceId = UUID.fromString(n.get("datasourceId").asString());
            }
        }
        salesBefore = json.readTree(get("/api/v1/queries/" + salesId, ADMIN, ADMIN_PASSWORD).body());

        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"ExpCustomers","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT customer, MIN(region) AS region FROM sales WHERE customer <> 'Alpha GmbH' GROUP BY customer ORDER BY customer",
                 "relations":[{"name":"Sales","targetQuery":"Sales","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":true,"partner":"Customer"}]}
                """.formatted(datasourceId), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        customersId = UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(send("POST", "/api/v1/queries/" + customersId + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}",
                ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/v1/queries/" + customersId + "/columns",
                "{\"columns\":[{\"propertyName\":\"customer\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD).statusCode())
                .isEqualTo(200);

        String update = """
                {"name":"Sales","datasourceId":"%s","sql":%s,"parameters":%s,
                 "relations":[{"name":"Customer","targetQuery":"ExpCustomers","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":false,"partner":"Sales"}]}
                """.formatted(datasourceId, salesBefore.get("sql").toString(), salesBefore.get("parameters").toString());
        HttpResponse<String> updated = send("PUT", "/api/v1/queries/" + salesId, update, ADMIN, ADMIN_PASSWORD);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
    }

    @AfterAll
    void removeRelations() throws Exception {
        send("DELETE", "/api/v1/queries/" + customersId, null, ADMIN, ADMIN_PASSWORD);
        String reset = """
                {"name":"Sales","datasourceId":"%s","sql":%s,"parameters":%s,"relations":[]}
                """.formatted(datasourceId, salesBefore.get("sql").toString(), salesBefore.get("parameters").toString());
        send("PUT", "/api/v1/queries/" + salesId, reset, ADMIN, ADMIN_PASSWORD);
    }

    private JsonNode odata(String path) throws Exception {
        HttpResponse<String> r = get(path, "demo", "demo");
        assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    @Test
    void toOneExpandInlinesTheMatchingEntityOrNull() throws Exception {
        JsonNode body = odata("/odata/Sales?$expand=Customer&$select=RowId,customer&$orderby=RowId");
        assertThat(body.get("@odata.context").asString()).isEqualTo("$metadata#Sales(RowId,customer,Customer())");
        JsonNode rows = body.get("value");
        assertThat(rows.size()).isEqualTo(24);
        for (JsonNode row : rows) {
            JsonNode customer = row.get("Customer");
            if ("Alpha GmbH".equals(row.get("customer").asString())) {
                assertThat(customer.isNull()).as("no matching customer → null").isTrue();
            } else {
                assertThat(customer.get("customer").asString()).isEqualTo(row.get("customer").asString());
                assertThat(customer.has("region")).isTrue();
            }
        }
    }

    @Test
    void toManyExpandWithNestedOptions() throws Exception {
        JsonNode all = odata("/odata/ExpCustomers?$expand=Sales($select=amount,customer)");
        int expandedRows = 0;
        for (JsonNode c : all.get("value")) {
            for (JsonNode s : c.get("Sales")) {
                assertThat(s.get("customer").asString()).isEqualTo(c.get("customer").asString());
                assertThat(s.has("amount")).isTrue();
                assertThat(s.has("sold_at")).as("nested $select").isFalse();
                expandedRows++;
            }
        }
        JsonNode alpha = odata("/odata/Sales?$filter=customer%20eq%20'Alpha%20GmbH'&$count=true");
        assertThat(expandedRows).isEqualTo(24 - alpha.get("@odata.count").asInt());

        JsonNode nested = odata("/odata/ExpCustomers?$top=1&$expand=Sales($filter=amount%20gt%200;$orderby=amount%20desc;$top=2;$count=true)");
        JsonNode first = nested.get("value").get(0);
        JsonNode sales = first.get("Sales");
        assertThat(sales.size()).isLessThanOrEqualTo(2);
        assertThat(first.get("Sales@odata.count").asInt()).isGreaterThanOrEqualTo(sales.size());
        if (sales.size() == 2) {
            assertThat(sales.get(0).get("amount").decimalValue()).isGreaterThanOrEqualTo(sales.get(1).get("amount").decimalValue());
        }
    }

    @Test
    void sharedParametersApplyToTheExpandedQuery() throws Exception {
        // Sales has p_year (default 2025); the expanded Sales follow the same option.
        JsonNode y2024 = odata("/odata/ExpCustomers?p_year=2024&$expand=Sales($select=sale_year)");
        for (JsonNode c : y2024.get("value")) {
            for (JsonNode s : c.get("Sales")) {
                assertThat(s.get("sale_year").asInt()).isEqualTo(2024);
            }
        }
    }

    @Test
    void nestedExpandStarAndLimits() throws Exception {
        JsonNode nested = odata("/odata/Sales?$top=3&$filter=customer%20ne%20'Alpha%20GmbH'&$expand=Customer($expand=Sales($top=1))");
        for (JsonNode row : nested.get("value")) {
            JsonNode back = row.get("Customer").get("Sales");
            assertThat(back.size()).isEqualTo(1);
            assertThat(back.get(0).get("customer").asString()).isEqualTo(row.get("customer").asString());
        }
        JsonNode star = odata("/odata/Sales?$top=1&$filter=customer%20ne%20'Alpha%20GmbH'&$expand=*");
        assertThat(star.get("value").get(0).get("Customer").isObject()).isTrue();

        assertThat(get("/odata/Sales?$expand=Customer($levels=2)", "demo", "demo").statusCode()).isIn(400, 501);
        assertThat(get("/odata/Sales?$expand=Customer($expand=Sales($expand=Customer($expand=Sales)))", "demo", "demo").statusCode())
                .isEqualTo(400);
        assertThat(get("/odata/Sales?$expand=Nope", "demo", "demo").statusCode()).isEqualTo(400);
        // $expand is not a valid option on /$count; Olingo rejects it while parsing the URI.
        assertThat(get("/odata/Sales/$count?$expand=Customer", "demo", "demo").statusCode()).isEqualTo(400);
    }
}
