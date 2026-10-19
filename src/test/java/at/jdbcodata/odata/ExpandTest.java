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
        assertThat(body.get("@odata.context").asString()).endsWith("/odata/$metadata#Sales(RowId,customer,Customer())");
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

    // --- navigation in $filter / $orderby ------------------------------------------------------------------

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void filterOverToOneNavigation() throws Exception {
        JsonNode all = odata("/odata/Sales?$select=RowId,customer&$expand=Customer($select=region)");
        String region = null;
        int expected = 0;
        for (JsonNode row : all.get("value")) {
            if (!row.get("Customer").isNull()) {
                region = region == null ? row.get("Customer").get("region").asString() : region;
            }
        }
        for (JsonNode row : all.get("value")) {
            JsonNode c = row.get("Customer");
            if (!c.isNull() && region.equals(c.get("region").asString())) expected++;
        }
        JsonNode filtered = odata("/odata/Sales?$count=true&$filter=" + enc("Customer/region eq '" + region + "'"));
        assertThat(filtered.get("@odata.count").asInt()).isEqualTo(expected).isPositive();
        HttpResponse<String> count = get("/odata/Sales/$count?$filter=" + enc("Customer/region eq '" + region + "'"), "demo", "demo");
        assertThat(count.body()).isEqualTo(String.valueOf(expected));

        // A missing related row compares as null (Alpha GmbH has no customer row).
        JsonNode orphans = odata("/odata/Sales?$filter=" + enc("Customer eq null or Customer/customer eq null"));
        assertThat(orphans.get("value")).isNotEmpty();
        assertThat(orphans.get("value")).allSatisfy(r -> assertThat(r.get("customer").asString()).isEqualTo("Alpha GmbH"));
        JsonNode negated = odata("/odata/Sales?$count=true&$filter=" + enc("not (Customer/customer eq null)"));
        JsonNode alpha = odata("/odata/Sales?$count=true&$filter=" + enc("customer eq 'Alpha GmbH'"));
        assertThat(negated.get("@odata.count").asInt()).isEqualTo(24 - alpha.get("@odata.count").asInt());
    }

    @Test
    void orderByNavigationProperty() throws Exception {
        JsonNode rows = odata("/odata/Sales?$filter=" + enc("customer ne 'Alpha GmbH'") + "&$orderby=" + enc("Customer/customer desc,RowId"));
        String previous = null;
        for (JsonNode row : rows.get("value")) {
            String c = row.get("customer").asString();
            if (previous != null) assertThat(c.compareTo(previous)).isLessThanOrEqualTo(0);
            previous = c;
        }
    }

    @Test
    void anyAllAndCountOverCollectionNavigation() throws Exception {
        JsonNode customers = odata("/odata/ExpCustomers?$expand=Sales($select=amount)");
        java.math.BigDecimal threshold = new java.math.BigDecimal("1000");
        int any = 0, all = 0, many = 0;
        for (JsonNode c : customers.get("value")) {
            boolean a = false, l = true;
            for (JsonNode s : c.get("Sales")) {
                boolean gt = s.get("amount").decimalValue().compareTo(threshold) > 0;
                a |= gt;
                l &= gt;
            }
            if (a) any++;
            if (l) all++;
            if (c.get("Sales").size() > 3) many++;
        }
        assertThat(odata("/odata/ExpCustomers?$count=true&$filter=" + enc("Sales/any(s:s/amount gt 1000)")).get("@odata.count").asInt())
                .isEqualTo(any);
        assertThat(odata("/odata/ExpCustomers?$count=true&$filter=" + enc("Sales/all(s:s/amount gt 1000)")).get("@odata.count").asInt())
                .isEqualTo(all);
        assertThat(odata("/odata/ExpCustomers?$count=true&$filter=" + enc("Sales/$count gt 3")).get("@odata.count").asInt())
                .isEqualTo(many);
        // Lambda bodies may navigate further and nest: customers with a sale whose customer is themselves.
        assertThat(odata("/odata/ExpCustomers?$count=true&$filter="
                + enc("Sales/any(s:s/Customer/customer eq 'Beta AG')")).get("@odata.count").asInt()).isEqualTo(1);
        // Navigation filters also work inside $expand.
        JsonNode nested = odata("/odata/ExpCustomers?$top=1&$expand=Sales($filter=" + enc("Customer/region ne null") + ")");
        assertThat(nested.get("value").get(0).get("Sales").size()).isPositive();
        // Comparing a collection directly is rejected.
        assertThat(get("/odata/ExpCustomers?$filter=" + enc("Sales eq null"), "demo", "demo").statusCode()).isIn(400, 501);
    }

    // --- resource paths: Sales(1), Sales(1)/Customer, ExpCustomers('Beta AG')/Sales ---------------------------

    @Test
    void entityByKeyNavigationPathsAndProperties() throws Exception {
        JsonNode one = odata("/odata/Sales(1)");
        assertThat(one.get("@odata.context").asString()).endsWith("/odata/$metadata#Sales/$entity");
        assertThat(one.get("RowId").asInt()).isEqualTo(1);
        assertThat(get("/odata/Sales(9999)", "demo", "demo").statusCode()).isEqualTo(404);

        JsonNode rows = odata("/odata/Sales?$select=RowId,customer&$orderby=RowId");
        int withCustomer = -1;
        int alphaRow = -1;
        String customer = null;
        for (JsonNode r : rows.get("value")) {
            if ("Alpha GmbH".equals(r.get("customer").asString())) {
                if (alphaRow < 0) alphaRow = r.get("RowId").asInt();
            } else if (withCustomer < 0) {
                withCustomer = r.get("RowId").asInt();
                customer = r.get("customer").asString();
            }
        }
        JsonNode c = odata("/odata/Sales(" + withCustomer + ")/Customer");
        assertThat(c.get("@odata.context").asString()).endsWith("/odata/$metadata#ExpCustomers/$entity");
        assertThat(c.get("customer").asString()).isEqualTo(customer);
        assertThat(get("/odata/Sales(" + alphaRow + ")/Customer", "demo", "demo").statusCode()).as("no related row").isEqualTo(204);

        String key = "('" + enc(customer.replace("'", "''")) + "')";
        int expected = 0;
        for (JsonNode r : rows.get("value")) if (customer.equals(r.get("customer").asString())) expected++;
        JsonNode sales = odata("/odata/ExpCustomers" + key + "/Sales?$count=true&$top=1&$orderby=amount%20desc");
        assertThat(sales.get("@odata.context").asString()).contains("/odata/$metadata#Sales");
        assertThat(sales.get("@odata.count").asInt()).isEqualTo(expected);
        assertThat(sales.get("value").size()).isEqualTo(1);
        assertThat(get("/odata/ExpCustomers" + key + "/Sales/$count", "demo", "demo").body()).isEqualTo(String.valueOf(expected));

        // Chains, keys after a collection navigation, $expand on a single entity.
        JsonNode chain = odata("/odata/Sales(" + withCustomer + ")/Customer/Sales(" + withCustomer + ")/Customer");
        assertThat(chain.get("customer").asString()).isEqualTo(customer);
        JsonNode expanded = odata("/odata/Sales(" + withCustomer + ")?$select=RowId&$expand=Customer($select=region)");
        assertThat(expanded.get("Customer").has("region")).isTrue();

        // Properties and raw values.
        JsonNode region = odata("/odata/ExpCustomers" + key + "/region");
        assertThat(region.get("value").asString()).isEqualTo(c.get("region").asString());
        HttpResponse<String> raw = get("/odata/ExpCustomers" + key + "/region/$value", "demo", "demo");
        assertThat(raw.statusCode()).isEqualTo(200);
        assertThat(raw.body()).isEqualTo(c.get("region").asString());

        // Read-only.
        assertThat(send("POST", "/odata/Sales", "{}", "demo", "demo").statusCode()).isIn(405, 501);
        assertThat(send("DELETE", "/odata/Sales(1)", null, "demo", "demo").statusCode()).isIn(405, 501);
    }

    @Test
    void searchInsideExpandAndGroupByAlongNavigation() throws Exception {
        JsonNode customers = odata("/odata/ExpCustomers?$expand=Sales");
        JsonNode first = customers.get("value").get(0);
        String term = first.get("customer").asString().split(" ")[0];
        JsonNode searched = odata("/odata/ExpCustomers?$expand=Sales($search=" + enc(term) + ")");
        for (JsonNode c : searched.get("value")) {
            for (JsonNode s : c.get("Sales")) {
                assertThat(s.get("customer").asString().toLowerCase()).contains(term.toLowerCase());
            }
        }
        // groupby a property reached through a to-one navigation (emitted under its own name).
        JsonNode grouped = odata("/odata/Sales?$apply=" + enc("groupby((Customer/region),aggregate($count as N))"));
        int total = 0;
        for (JsonNode g : grouped.get("value")) {
            assertThat(g.has("region")).isTrue();
            total += g.get("N").asInt();
        }
        assertThat(total).isEqualTo(24);
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
