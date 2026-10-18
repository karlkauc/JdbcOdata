package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 4.01 expressions and Data Aggregation transformations beyond Olingo 5: case(), the $filter path segment, the
 * expand transformation, join/outerjoin, nest, custom aggregation methods and recursive hierarchies. Model:
 * P6Customers → Sales (to-many), Org with Parent (to-one, itself) and Children.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryLanguage401Test extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<UUID> created = new ArrayList<>();
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

    private List<JsonNode> rows(String path) throws Exception {
        return StreamSupport.stream(ok(path).get("value").spliterator(), false).toList();
    }

    private UUID query(String body, String key) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/queries", body, ADMIN, ADMIN_PASSWORD);
        assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
        UUID id = UUID.fromString(json.readTree(r.body()).get("id").asString());
        created.add(id);
        assertThat(send("POST", "/api/v1/queries/" + id + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}",
                ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        HttpResponse<String> cols = send("PUT", "/api/v1/queries/" + id + "/columns",
                "{\"columns\":[{\"propertyName\":\"" + key + "\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(cols.statusCode()).as(cols.body()).isEqualTo(200);
        return id;
    }

    @BeforeAll
    void defineModel() throws Exception {
        String ds = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) ds = n.get("datasourceId").asString();
        }
        query("""
                {"name":"P6Customers","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT customer, MIN(region) AS region FROM sales GROUP BY customer ORDER BY customer",
                 "relations":[{"name":"Sales","targetQuery":"Sales","localColumns":["customer"],"targetColumns":["customer"],"toMany":true}]}
                """.formatted(ds), "customer");
        query("""
                {"name":"Org","datasourceId":"%s","keyMode":"COLUMNS",
                 "sql":"SELECT * FROM (VALUES (1, CAST(NULL AS INT), 'Vorstand'), (2, 1, 'Vertrieb'), (3, 1, 'Technik'), (4, 2, 'Nord'), (5, 2, 'Sued'), (6, 3, 'Entwicklung'), (7, 6, 'Labor')) AS t(org_id, parent_id, name) ORDER BY org_id",
                 "relations":[{"name":"Parent","targetQuery":"Org","localColumns":["parent_id"],"targetColumns":["org_id"],"toMany":false,"partner":"Children"},
                              {"name":"Children","targetQuery":"Org","localColumns":["org_id"],"targetColumns":["parent_id"],"toMany":true,"partner":"Parent"}]}
                """.formatted(ds), "org_id");
        sales.addAll(rows("Sales?$orderby=RowId"));
    }

    @AfterAll
    void removeModel() throws Exception {
        for (UUID id : created) send("DELETE", "/api/v1/queries/" + id, null, ADMIN, ADMIN_PASSWORD);
    }

    private long salesCount(java.util.function.Predicate<JsonNode> p) {
        return sales.stream().filter(p).count();
    }

    private static double amount(JsonNode s) {
        return s.get("amount").decimalValue().doubleValue();
    }

    // --- case ---------------------------------------------------------------------------------------------------

    @Test
    void caseInFilterComputeAndOrderBy() throws Exception {
        assertThat(rows("Sales?$filter=" + enc("case(amount gt 5000:'hoch',true:'niedrig') eq 'hoch'")).size())
                .isEqualTo((int) salesCount(s -> amount(s) > 5000));
        List<JsonNode> computed = rows("Sales?$compute=" + enc("case(delivered:1,amount gt 9000:2,true:0) as K") + "&$select=RowId,K,delivered,amount");
        for (JsonNode r : computed) {
            int expected = r.get("delivered").asBoolean() ? 1 : r.get("amount").decimalValue().doubleValue() > 9000 ? 2 : 0;
            assertThat(r.get("K").asInt()).isEqualTo(expected);
        }
        List<JsonNode> ordered = rows("Sales?$orderby=" + enc("case(region eq 'Süd':0,true:1),RowId") + "&$select=region");
        assertThat(ordered.get(0).get("region").asString()).isEqualTo("Süd");
        assertThat(ordered.get(ordered.size() - 1).get("region").asString()).isEqualTo("Nord");
        // nested case, null when no branch applies
        JsonNode nested = ok("Sales(1)?$compute=" + enc("case(amount lt 0:'neg',amount gt 0:case(delivered:'ja',true:'nein')) as T,case(false:1) as N"));
        assertThat(nested.get("T").asString()).isEqualTo(nested.get("delivered").asBoolean() ? "ja" : "nein");
        assertThat(nested.get("N").isNull()).isTrue();
        JsonNode agg = ok("Sales?$apply=" + enc("compute(case(delivered:amount,true:0) as D)/aggregate(D with sum as S)"));
        BigDecimal expected = sales.stream().filter(s -> s.get("delivered").asBoolean()).map(s -> s.get("amount").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(agg.get("value").get(0).get("S").decimalValue()).isEqualByComparingTo(expected);
    }

    @Test
    void caseInsideLambdasAndCountFilters() throws Exception {
        List<JsonNode> any = rows("P6Customers?$filter=" + enc("Sales/any(s: case(s/amount gt 5000:true,true:false))"));
        assertThat(any).hasSize(1);
        assertThat(any.get(0).get("customer").asString()).isEqualTo("Beta AG");
        // branches mixing the lambda variable and the outer entity
        List<JsonNode> mixed = rows("P6Customers?$filter=" + enc("Sales/any(s:case(s/region eq region:s/amount,true:0) gt 5000)"));
        for (JsonNode c : mixed) {
            assertThat(sales.stream().anyMatch(s -> s.get("customer").asString().equals(c.get("customer").asString())
                    && s.get("region").asString().equals(c.get("region").asString()) && amount(s) > 5000)).isTrue();
        }
        assertThat(rows("P6Customers?$filter=" + enc("Sales/all(s:case(s/amount gt 0:true,true:false))"))).hasSize(2);
        List<JsonNode> counted = rows("P6Customers?$compute=" + enc("Sales/$count($filter=case(amount gt 5000:1,true:0) eq 1) as N")
                + "&$select=customer,N");
        assertThat(counted).hasSize(2);
        for (JsonNode c : counted) {
            long expected = salesCount(s -> s.get("customer").asString().equals(c.get("customer").asString()) && amount(s) > 5000);
            assertThat(c.get("N").asInt()).isEqualTo((int) expected);
        }
        // a case containing a count filter
        List<JsonNode> label = rows("P6Customers?$compute=" + enc("case(Sales/$count($filter=amount gt 5000) gt 0:'gross',true:'klein') as G")
                + "&$select=customer,G&$orderby=customer");
        assertThat(label.stream().filter(c -> c.get("G").asString().equals("gross")).count()).isEqualTo(1);
    }

    // --- $filter path segment -----------------------------------------------------------------------------------

    @Test
    void filterAsPathSegment() throws Exception {
        String n = String.valueOf(salesCount(s -> amount(s) > 5000));
        assertThat(call("Sales/$filter(" + enc("amount gt 5000") + ")/$count").body()).isEqualTo(n);
        assertThat(call("Sales/$filter(" + enc("amount gt 5000") + ")/$filter(delivered)/$count").body())
                .isEqualTo(String.valueOf(salesCount(s -> amount(s) > 5000 && s.get("delivered").asBoolean())));
        assertThat(rows("Sales/$filter(" + enc("amount gt 5000") + ")?$filter=" + enc("region eq 'Nord'")).size())
                .isEqualTo((int) salesCount(s -> amount(s) > 5000 && s.get("region").asString().equals("Nord")));
        List<JsonNode> beta = rows("P6Customers('Beta%20AG')/Sales/$filter(" + enc("delivered and sale_month le 6") + ")");
        assertThat(beta).isNotEmpty().allSatisfy(r -> assertThat(r.get("customer").asString()).isEqualTo("Beta AG"));
        assertThat(beta.size()).isEqualTo((int) salesCount(s -> s.get("customer").asString().equals("Beta AG")
                && s.get("delivered").asBoolean() && s.get("sale_month").asInt() <= 6));
    }

    // --- expand transformation, join, outerjoin, nest -------------------------------------------------------------

    @Test
    void expandTransformation() throws Exception {
        List<JsonNode> r = rows("P6Customers?$apply=" + enc("expand(Sales,filter(amount gt 5000))"));
        assertThat(r).hasSize(2);
        for (JsonNode c : r) {
            long expected = salesCount(s -> s.get("customer").asString().equals(c.get("customer").asString()) && amount(s) > 5000);
            assertThat(c.get("Sales").size()).isEqualTo((int) expected);
        }
        List<JsonNode> withFilter = rows("P6Customers?$apply=" + enc("expand(Sales,filter(amount gt 5000))/filter(Sales/$count gt 0)"));
        assertThat(withFilter).hasSize(1);
        assertThat(withFilter.get(0).get("customer").asString()).isEqualTo("Beta AG");
    }

    @Test
    void joinAndOuterJoin() throws Exception {
        List<JsonNode> joined = rows("P6Customers?$apply=" + enc("join(Sales as S)") + "&$filter=" + enc("S/amount gt 5000"));
        assertThat(joined.size()).isEqualTo((int) salesCount(s -> amount(s) > 5000));
        assertThat(joined.get(0).get("S").get("amount").decimalValue().doubleValue()).isGreaterThan(5000);
        List<JsonNode> totals = rows("P6Customers?$apply=" + enc("join(Sales as S)/groupby((customer),aggregate(S/amount with sum as Total))")
                + "&$orderby=customer");
        for (JsonNode t : totals) {
            BigDecimal expected = sales.stream().filter(s -> s.get("customer").asString().equals(t.get("customer").asString()))
                    .map(s -> s.get("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(t.get("Total").decimalValue()).isEqualByComparingTo(expected);
        }
        long big = salesCount(s -> amount(s) > 5500);
        long customersWithBig = sales.stream().filter(s -> amount(s) > 5500).map(s -> s.get("customer").asString()).distinct().count();
        List<JsonNode> outer = rows("P6Customers?$apply=" + enc("outerjoin(Sales as S,filter(amount gt 5500))"));
        assertThat(outer.size()).isEqualTo((int) (big + (2 - customersWithBig)));
        assertThat(rows("P6Customers?$apply=" + enc("join(Sales as S,filter(amount gt 5500))")).size()).isEqualTo((int) big);
        assertThat(call("P6Customers/$count?$apply=" + enc("join(Sales as S)")).body()).isEqualTo(String.valueOf(sales.size()));
        assertThat(rows("P6Customers?$apply=" + enc("join(Sales as S)") + "&$select=customer,S&$top=1").get(0).has("region")).isFalse();
    }

    @Test
    void nestCollectsSubResults() throws Exception {
        List<JsonNode> perCustomer = rows("Sales?$apply=" + enc("groupby((customer),nest(filter(amount gt 5000)/aggregate($count as N) as Big))")
                + "&$orderby=customer");
        assertThat(perCustomer).hasSize(2);
        for (JsonNode c : perCustomer) {
            long expected = salesCount(s -> s.get("customer").asString().equals(c.get("customer").asString()) && amount(s) > 5000);
            assertThat(c.get("Big").get(0).get("N").asInt()).isEqualTo((int) expected);
        }
        JsonNode both = rows("Sales?$apply=" + enc("nest(topcount(2,amount) as Top2,bottomcount(1,amount) as Low)")).get(0);
        assertThat(both.get("Top2").size()).isEqualTo(2);
        assertThat(both.get("Low").size()).isEqualTo(1);
        double max = sales.stream().mapToDouble(QueryLanguage401Test::amount).max().orElseThrow();
        assertThat(both.get("Top2").get(0).get("amount").decimalValue().doubleValue()).isEqualTo(max);
    }

    @Test
    void joinAlongNavigationPathsAndRollupWithNest() throws Exception {
        List<JsonNode> grandchildren = rows("Org?$apply=" + enc("join(Children/Children as G)") + "&$filter=" + enc("name eq 'Vorstand'"));
        assertThat(grandchildren.stream().map(r -> r.get("G").get("name").asString()).sorted().toList())
                .containsExactly("Entwicklung", "Nord", "Sued");
        assertThat(rows("Org?$apply=" + enc("outerjoin(Children/Children as G)") + "&$filter=" + enc("G eq null")).size())
                .isEqualTo(5); // Vertrieb, Nord, Sued, Entwicklung, Labor have no grandchildren
        assertThat(call("Org?$apply=" + enc("join(Children/name as G)")).statusCode()).isEqualTo(400);

        List<JsonNode> rolled = rows("Sales?$apply=" + enc("groupby((rollup($all,region)),nest(aggregate($count as N) as C))"));
        long regions = sales.stream().map(s -> s.get("region").asString()).distinct().count();
        assertThat(rolled).hasSize((int) regions + 1);
        JsonNode total = rolled.stream().filter(r -> !r.has("region") || r.get("region").isNull()).findFirst().orElseThrow();
        assertThat(total.get("C").get(0).get("N").asInt()).isEqualTo(sales.size());
        for (JsonNode r : rolled) {
            if (r == total) continue;
            assertThat(r.get("C").get(0).get("N").asInt())
                    .isEqualTo((int) salesCount(s -> s.get("region").asString().equals(r.get("region").asString())));
        }
        List<JsonNode> joinedRollup = rows("P6Customers?$apply=" + enc("groupby((rollup($all,region)),join(Sales as S)/aggregate(S/amount with sum as T))"));
        BigDecimal all = sales.stream().map(s -> s.get("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(joinedRollup.stream().filter(r -> !r.has("region") || r.get("region").isNull()).findFirst().orElseThrow()
                .get("T").decimalValue()).isEqualByComparingTo(all);
    }

    // --- custom aggregation methods -----------------------------------------------------------------------------

    @Test
    void customAggregationMethods() throws Exception {
        JsonNode r = rows("Sales?$apply=" + enc("aggregate(amount with JdbcOdata.median as M,amount with JdbcOdata.stddev as SD,"
                + "amount with JdbcOdata.variancep as VP)")).get(0);
        List<BigDecimal> values = sales.stream().map(s -> s.get("amount").decimalValue()).sorted().toList();
        int n = values.size();
        BigDecimal median = n % 2 == 1 ? values.get(n / 2) : values.get(n / 2 - 1).add(values.get(n / 2)).divide(BigDecimal.valueOf(2));
        assertThat(r.get("M").decimalValue()).isEqualByComparingTo(median);
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        double ss = values.stream().mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2)).sum();
        assertThat(r.get("SD").decimalValue().doubleValue()).isCloseTo(Math.sqrt(ss / (n - 1)), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(r.get("VP").decimalValue().doubleValue()).isCloseTo(ss / n, org.assertj.core.data.Offset.offset(1e-4));
        List<JsonNode> grouped = rows("Sales?$apply=" + enc("groupby((customer),aggregate(amount with JdbcOdata.median as M))"));
        assertThat(grouped).hasSize(2);
        assertThat(call("Sales?$apply=" + enc("aggregate(amount with JdbcOdata.nope as X)")).statusCode()).isIn(400, 501);
        assertThat(call("$metadata").body()).contains("Aggregation.CustomAggregationMethods").contains("JdbcOdata.median");
        new MathContext(1);
    }

    // --- recursive hierarchies ------------------------------------------------------------------------------------

    private static List<String> names(List<JsonNode> rows) {
        return rows.stream().map(r -> r.get("name").asString()).toList();
    }

    @Test
    void hierarchyTransformations() throws Exception {
        assertThat(call("$metadata").body()).contains("Term=\"Aggregation.RecursiveHierarchy\" Qualifier=\"Parent\"");
        assertThat(names(rows("Org?$apply=" + enc("descendants($root/Org,Parent,org_id,filter(name eq 'Vertrieb'),keep start)"))))
                .containsExactly("Vertrieb", "Nord", "Sued");
        assertThat(names(rows("Org?$apply=" + enc("descendants($root/Org,Parent,org_id,filter(name eq 'Vorstand'),1)"))))
                .containsExactly("Vertrieb", "Technik");
        assertThat(names(rows("Org?$apply=" + enc("ancestors($root/Org,Parent,org_id,filter(name eq 'Labor'))"))))
                .containsExactly("Vorstand", "Technik", "Entwicklung");
        assertThat(names(rows("Org?$apply=" + enc("ancestors($root/Org,Parent,org_id,filter(name eq 'Labor'),1)"))))
                .containsExactly("Entwicklung");
        assertThat(names(rows("Org?$apply=" + enc("traverse($root/Org,Parent,org_id,preorder)"))))
                .containsExactly("Vorstand", "Vertrieb", "Nord", "Sued", "Technik", "Entwicklung", "Labor");
        assertThat(names(rows("Org?$apply=" + enc("traverse($root/Org,Parent,org_id,postorder)"))))
                .containsExactly("Nord", "Sued", "Vertrieb", "Labor", "Entwicklung", "Technik", "Vorstand");
        assertThat(names(rows("Org?$apply=" + enc("traverse($root/Org,Parent,org_id,preorder,filter(name eq 'Technik'))"))))
                .containsExactly("Technik", "Entwicklung", "Labor");
        assertThat(call("Org/$count?$apply=" + enc("descendants($root/Org,Parent,org_id,filter(name eq 'Technik'))")).body()).isEqualTo("2");
        assertThat(rows("Org?$apply=" + enc("descendants($root/Org,Parent,org_id,filter(name eq 'Vorstand'))/aggregate($count as N)"))
                .get(0).get("N").asInt()).isEqualTo(6);
        assertThat(call("Org?$apply=" + enc("descendants($root/Org,Children,org_id,filter(true))")).statusCode()).isEqualTo(400);
    }
}
