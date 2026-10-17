package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 2 of the query language: $compute, $levels, $apply with/inside $expand, cast/isof/$it/$root, $ref,
 * rollup/from/inline aggregates, $all and $crossjoin. Model: Sales.Customer → P2Customers (to-one),
 * P2Customers.Sales → Sales (to-many), Sales.Peers → Sales (to-many, same customer; recursive).
 * "Alpha GmbH" has no P2Customers row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvancedQueryTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID customersId;
    private UUID salesId;
    private UUID datasourceId;
    private JsonNode salesBefore;
    private final List<JsonNode> sales = new ArrayList<>();

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode odata(String path) throws Exception {
        HttpResponse<String> r = get(path, "demo", "demo");
        assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private int status(String path) throws Exception {
        return get(path, "demo", "demo").statusCode();
    }

    @BeforeAll
    void defineModel() throws Exception {
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) {
                salesId = UUID.fromString(n.get("id").asString());
                datasourceId = UUID.fromString(n.get("datasourceId").asString());
            }
        }
        salesBefore = json.readTree(get("/api/v1/queries/" + salesId, ADMIN, ADMIN_PASSWORD).body());
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"P2Customers","datasourceId":"%s","keyMode":"COLUMNS",
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
                 "relations":[{"name":"Customer","targetQuery":"P2Customers","localColumns":["customer"],"targetColumns":["customer"],
                               "toMany":false,"partner":"Sales"},
                              {"name":"Peers","targetQuery":"Sales","localColumns":["customer"],"targetColumns":["customer"],"toMany":true}]}
                """.formatted(datasourceId, salesBefore.get("sql").toString(), salesBefore.get("parameters").toString());
        HttpResponse<String> updated = send("PUT", "/api/v1/queries/" + salesId, update, ADMIN, ADMIN_PASSWORD);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        odata("/odata/Sales?$orderby=RowId").get("value").forEach(sales::add);
        assertThat(sales).hasSize(24);
    }

    @AfterAll
    void removeModel() throws Exception {
        send("DELETE", "/api/v1/queries/" + customersId, null, ADMIN, ADMIN_PASSWORD);
        String reset = """
                {"name":"Sales","datasourceId":"%s","sql":%s,"parameters":%s,"relations":[]}
                """.formatted(datasourceId, salesBefore.get("sql").toString(), salesBefore.get("parameters").toString());
        send("PUT", "/api/v1/queries/" + salesId, reset, ADMIN, ADMIN_PASSWORD);
    }

    private long salesWhere(java.util.function.Predicate<JsonNode> p) {
        return sales.stream().filter(p).count();
    }

    private static boolean alpha(JsonNode row) {
        return "Alpha GmbH".equals(row.get("customer").asString());
    }

    // --- $compute -----------------------------------------------------------------------------------------------

    @Test
    void computeAddsPropertiesAndCombinesWithExpandAndApply() throws Exception {
        JsonNode r = odata("/odata/Sales?$top=3&$orderby=RowId&$compute=" + enc("amount mul 2 as Double") + "&$select=RowId,amount,Double");
        for (JsonNode row : r.get("value")) {
            assertThat(row.get("Double").decimalValue()).isEqualByComparingTo(row.get("amount").decimalValue().multiply(BigDecimal.TWO));
            assertThat(row.has("customer")).as("$select").isFalse();
            assertThat(row.get("@odata.id").asString()).startsWith("Sales(");
        }
        JsonNode withExpand = odata("/odata/Sales?$top=2&$filter=" + enc("customer ne 'Alpha GmbH'") + "&$compute="
                + enc("year(sold_at) as Y") + "&$expand=Customer($select=region)");
        assertThat(withExpand.get("value").get(0).get("Customer").has("region")).isTrue();
        assertThat(withExpand.get("value").get(0).has("Y")).isTrue();
        JsonNode withApply = odata("/odata/Sales?$apply=" + enc("filter(amount gt 0)") + "&$compute=" + enc("amount add 1 as P"));
        assertThat(withApply.get("value").get(0).has("P")).isTrue();
        assertThat(QueryRewriter.rewrite("$top=1&$compute=a%20as%20b")).isEqualTo("$top=1&$apply=compute(a%20as%20b)");
        assertThat(QueryRewriter.rewrite("$apply=filter(x)&%24compute=a%20as%20b")).isEqualTo("$apply=filter(x)/compute(a%20as%20b)");
    }

    // --- $levels ------------------------------------------------------------------------------------------------

    @Test
    void levelsOnARecursiveNavigation() throws Exception {
        JsonNode r = odata("/odata/Sales?$top=1&$orderby=RowId&$select=RowId&$expand=Peers($levels=2;$select=RowId)");
        JsonNode level1 = r.get("value").get(0).get("Peers");
        long peers = salesWhere(s -> s.get("customer").asString().equals(sales.get(0).get("customer").asString()));
        assertThat(level1.size()).isEqualTo((int) peers);
        // All peers of a customer are peers of each other: on level 2 every one of them was already visited, so the
        // cycle ends with empty collections instead of repeating the same rows.
        JsonNode level2 = level1.get(0).get("Peers");
        assertThat(level2).isNotNull();
        assertThat(level2.size()).isZero();
        assertThat(status("/odata/Sales?$top=1&$expand=Peers($levels=max;$select=RowId)")).isEqualTo(200);
        assertThat(status("/odata/Sales?$expand=Customer($levels=2)")).isEqualTo(400);
    }

    // --- $apply and $expand -------------------------------------------------------------------------------------

    @Test
    void applyWithExpandAndApplyInsideExpand() throws Exception {
        JsonNode kept = odata("/odata/Sales?$apply=" + enc("filter(customer ne 'Alpha GmbH')/top(2)") + "&$expand=Customer");
        assertThat(kept.get("value").get(0).get("Customer").get("customer").asString())
                .isEqualTo(kept.get("value").get(0).get("customer").asString());
        assertThat(status("/odata/Sales?$apply=" + enc("groupby((region))") + "&$expand=Customer")).isEqualTo(400);

        JsonNode totals = odata("/odata/P2Customers?$expand=Sales($apply=" + enc("aggregate(amount with sum as Total)") + ")");
        Map<String, BigDecimal> expected = new HashMap<>();
        sales.forEach(s -> expected.merge(s.get("customer").asString(), s.get("amount").decimalValue(), BigDecimal::add));
        for (JsonNode c : totals.get("value")) {
            JsonNode agg = c.get("Sales");
            assertThat(agg.size()).isEqualTo(1);
            assertThat(agg.get(0).get("Total").decimalValue()).isEqualByComparingTo(expected.get(c.get("customer").asString()));
        }
        JsonNode single = odata("/odata/P2Customers('" + enc(totals.get("value").get(0).get("customer").asString()) + "')?$expand=Sales($apply="
                + enc("aggregate($count as N)") + ")");
        assertThat(single.get("Sales").get(0).get("N").asInt()).isPositive();
    }

    // --- cast, isof, $it, $root ---------------------------------------------------------------------------------

    @Test
    void castIsofItAndRoot() throws Exception {
        assertThat(odata("/odata/Sales?$count=true&$filter=" + enc("cast(RowId,Edm.String) eq '1'")).get("@odata.count").asInt()).isEqualTo(1);
        assertThat(odata("/odata/Sales?$count=true&$filter=" + enc("isof(JdbcOdata.Sales)")).get("@odata.count").asInt()).isEqualTo(24);
        assertThat(odata("/odata/Sales?$count=true&$filter=" + enc("isof(JdbcOdata.P2Customers)")).get("@odata.count").asInt()).isZero();
        assertThat(odata("/odata/Sales?$count=true&$filter=" + enc("isof(customer,Edm.String)")).get("@odata.count").asInt()).isEqualTo(24);
        assertThat(odata("/odata/Sales?$count=true&$filter=" + enc("cast(customer,Edm.Int32) eq null")).get("@odata.count").asInt()).isEqualTo(24);

        JsonNode it = odata("/odata/P2Customers?$count=true&$filter=" + enc("Sales/any(s:s/customer eq $it/customer)"));
        assertThat(it.get("@odata.count").asInt()).isEqualTo(odata("/odata/P2Customers?$count=true").get("@odata.count").asInt());

        String beta = sales.stream().filter(s -> !alpha(s)).findFirst().orElseThrow().get("customer").asString();
        JsonNode root = odata("/odata/Sales?$count=true&$filter=" + enc("customer eq $root/P2Customers('" + beta.replace("'", "''") + "')/customer"));
        assertThat(root.get("@odata.count").asLong()).isEqualTo(salesWhere(s -> beta.equals(s.get("customer").asString())));
        assertThat(status("/odata/Sales?$filter=" + enc("customer eq $root/P2Customers('Nobody')/customer"))).isEqualTo(404);
    }

    // --- $ref ---------------------------------------------------------------------------------------------------

    @Test
    void references() throws Exception {
        String beta = sales.stream().filter(s -> !alpha(s)).findFirst().orElseThrow().get("customer").asString();
        JsonNode refs = odata("/odata/P2Customers('" + enc(beta) + "')/Sales/$ref?$count=true");
        assertThat(refs.get("@odata.context").asString()).startsWith("http").endsWith("/odata/$metadata#Collection($ref)");
        assertThat(refs.get("@odata.count").asLong()).isEqualTo(salesWhere(s -> beta.equals(s.get("customer").asString())));
        refs.get("value").forEach(v -> assertThat(v.get("@odata.id").asString()).contains("Sales("));
        int row = sales.stream().filter(s -> !alpha(s)).findFirst().orElseThrow().get("RowId").asInt();
        JsonNode one = odata("/odata/Sales(" + row + ")/Customer/$ref");
        assertThat(one.get("@odata.id").asString()).contains("P2Customers(");
        assertThat(odata("/odata/Sales/$ref?$top=2").get("value")).hasSize(2);
    }

    // --- rollup, from, inline aggregates ------------------------------------------------------------------------

    @Test
    void rollupFromAndInlineAggregates() throws Exception {
        JsonNode rollup = odata("/odata/Sales?$apply=" + enc("groupby((rollup(region,customer)),aggregate($count as N))"));
        int withCustomer = 0;
        int regionOnly = 0;
        for (JsonNode g : rollup.get("value")) {
            assertThat(g.has("region")).as("leftmost rollup property stays").isTrue();
            if (g.has("customer")) withCustomer += g.get("N").asInt();
            else regionOnly += g.get("N").asInt();
        }
        assertThat(withCustomer).isEqualTo(24);
        assertThat(regionOnly).isEqualTo(24);
        JsonNode all = odata("/odata/Sales?$apply=" + enc("groupby((rollup($all,region)),aggregate($count as N))"));
        assertThat(all.get("value")).anySatisfy(g -> {
            assertThat(g.has("region")).isFalse();
            assertThat(g.get("N").asInt()).isEqualTo(24);
        });

        Map<String, BigDecimal> perCustomer = new HashMap<>();
        sales.forEach(s -> perCustomer.merge(s.get("customer").asString(), s.get("amount").decimalValue(), BigDecimal::add));
        BigDecimal maxOfSums = perCustomer.values().stream().max(BigDecimal::compareTo).orElseThrow();
        JsonNode from = odata("/odata/Sales?$apply=" + enc("aggregate(amount with sum as M from customer with max)"));
        assertThat(from.get("value").get(0).get("M").decimalValue()).isEqualByComparingTo(maxOfSums);

        BigDecimal nonAlpha = sales.stream().filter(s -> !alpha(s)).map(s -> s.get("amount").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        JsonNode inline = odata("/odata/P2Customers?$apply=" + enc("aggregate(Sales(amount with sum as Total))"));
        assertThat(inline.get("value").get(0).get("Total").decimalValue()).isEqualByComparingTo(nonAlpha);
        JsonNode navCount = odata("/odata/P2Customers?$apply=" + enc("aggregate(Sales/$count as N)"));
        assertThat(navCount.get("value").get(0).get("N").asLong()).isEqualTo(salesWhere(s -> !alpha(s)));
    }

    // --- $all, $crossjoin ---------------------------------------------------------------------------------------

    @Test
    void allAndCrossjoin() throws Exception {
        String beta = sales.stream().filter(s -> !alpha(s)).findFirst().orElseThrow().get("customer").asString();
        String word = beta.split(" ")[0].toLowerCase(Locale.ROOT);
        JsonNode all = odata("/odata/$all?$count=true&$search=" + enc(word));
        long expected = odata("/odata/Sales?$count=true&$search=" + enc(word)).get("@odata.count").asLong()
                + odata("/odata/P2Customers?$count=true&$search=" + enc(word)).get("@odata.count").asLong();
        assertThat(all.get("@odata.count").asLong()).isEqualTo(expected);
        assertThat(all.get("value")).anySatisfy(e -> assertThat(e.get("@odata.type").asString()).isEqualTo("#JdbcOdata.Sales"));
        assertThat(all.get("value")).anySatisfy(e -> assertThat(e.get("@odata.type").asString()).isEqualTo("#JdbcOdata.P2Customers"));

        JsonNode cj = odata("/odata/$crossjoin(Sales,P2Customers)?$count=true&$filter=" + enc("Sales/customer eq P2Customers/customer"));
        assertThat(cj.get("@odata.count").asLong()).isEqualTo(salesWhere(s -> !alpha(s)));
        JsonNode first = cj.get("value").get(0);
        assertThat(first.get("Sales@odata.navigationLink").asString()).startsWith("Sales(");
        JsonNode expanded = odata("/odata/$crossjoin(Sales,P2Customers)?$top=1&$filter=" + enc("Sales/customer eq P2Customers/customer")
                + "&$expand=P2Customers");
        assertThat(expanded.get("value").get(0).get("P2Customers").has("region")).isTrue();
        JsonNode ordered = odata("/odata/$crossjoin(Sales,P2Customers)?$top=1&$filter=" + enc("Sales/customer eq P2Customers/customer")
                + "&$orderby=" + enc("Sales/amount desc") + "&$expand=Sales");
        BigDecimal max = sales.stream().filter(s -> !alpha(s)).map(s -> s.get("amount").decimalValue()).max(BigDecimal::compareTo).orElseThrow();
        assertThat(ordered.get("value").get(0).get("Sales").get("amount").decimalValue()).isEqualByComparingTo(max);
    }
}
