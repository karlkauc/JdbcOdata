package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Push-down of $filter/$orderby/$top/$skip/$count: the same query once with push-down ("PdSales") and once without
 * ("MemSales") must answer every request identically. The region column contains nulls to check null semantics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushDownTest extends DemoProfileTest {

    private static final String SQL = "SELECT id, customer, CASE WHEN MOD(id, 4) = 0 THEN NULL ELSE region END AS region, "
            + "sale_month, amount, sold_at, CAST(sold_at AS DATE) AS sold_on, delivered FROM sales WHERE sale_year = 2025";

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID pushId;
    private UUID memId;

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private UUID create(String name, boolean pushDown, String datasourceId) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"%s","datasourceId":"%s","keyMode":"COLUMNS","pushDown":%s,"sql":"%s"}
                """.formatted(name, datasourceId, pushDown, SQL), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        UUID id = UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(json.readTree(created.body()).get("pushDown").asBoolean()).isEqualTo(pushDown);
        assertThat(send("POST", "/api/v1/queries/" + id + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}",
                ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        HttpResponse<String> cols = send("PUT", "/api/v1/queries/" + id + "/columns",
                "{\"columns\":[{\"propertyName\":\"id\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(cols.statusCode()).as(cols.body()).isEqualTo(200);
        return id;
    }

    @BeforeAll
    void defineQueries() throws Exception {
        String datasourceId = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) datasourceId = n.get("datasourceId").asString();
        }
        pushId = create("PdSales", true, datasourceId);
        memId = create("MemSales", false, datasourceId);
    }

    @AfterAll
    void removeQueries() throws Exception {
        send("DELETE", "/api/v1/queries/" + pushId, null, ADMIN, ADMIN_PASSWORD);
        send("DELETE", "/api/v1/queries/" + memId, null, ADMIN, ADMIN_PASSWORD);
    }

    private HttpResponse<String> both(String set, String query) throws Exception {
        return get("/odata/" + set + query, "demo", "demo");
    }

    /** Body without the context URL (which names the entity set). */
    private JsonNode body(HttpResponse<String> r) throws Exception {
        JsonNode n = json.readTree(r.body());
        if (n.isObject()) ((tools.jackson.databind.node.ObjectNode) n).remove("@odata.context");
        if (n.has("@odata.nextLink")) ((tools.jackson.databind.node.ObjectNode) n).remove("@odata.nextLink");
        if (n.has("value")) {
            for (JsonNode row : n.get("value")) ((tools.jackson.databind.node.ObjectNode) row).remove("@odata.id");
        }
        return n;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "region eq 'Nord'",
            "region eq null",
            "region ne 'Nord'",
            "region ne null and amount gt 3000",
            "not (region eq 'Nord')",
            "amount ge 2000 and amount lt 6000.5",
            "sale_month in (1, 3, 5) or delivered",
            "contains(customer, 'eta') and not delivered",
            "startswith(tolower(customer), 'alpha')",
            "endswith(customer, '_%')",
            "length(customer) eq 7",
            "sale_month add 1 gt 10",
            "-amount lt -5000",
            "year(sold_on) eq 2025 and month(sold_on) le 6",
            "sold_on ge 2025-06-01",
            "region gt 'M'",
            "year(sold_at) eq 2025",
            "amount div 2 gt 2000",
            "region lt 'Z' and amount gt 1000",
            "true",
            "region eq @r",
    })
    void filtersAnswerLikeInMemory(String filter) throws Exception {
        String q = "?$filter=" + enc(filter) + "&$orderby=id&@r='Süd'";
        HttpResponse<String> pushed = both("PdSales", q);
        HttpResponse<String> memory = both("MemSales", q);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        assertThat(body(pushed)).as(filter).isEqualTo(body(memory));
        assertThat(memory.headers().firstValue("X-Push-Down")).isEmpty();
    }

    @Test
    void translatableFiltersGoToTheDatabaseOthersStayInMemory() throws Exception {
        assertThat(both("PdSales", "?$filter=" + enc("region eq 'Nord'")).headers().firstValue("X-Push-Down")).hasValue("filter");
        assertThat(both("PdSales", "?$filter=" + enc("region eq 'Nord' and region gt 'M'")).headers().firstValue("X-Push-Down"))
                .hasValue("filter(partial)");
        assertThat(both("PdSales", "?$filter=" + enc("region gt 'M'")).headers().firstValue("X-Push-Down")).isEmpty();
        assertThat(both("PdSales", "?$top=3").headers().firstValue("X-Push-Down")).hasValue("top");
        assertThat(both("PdSales", "?$apply=" + enc("filter(amount gt 0)")).headers().firstValue("X-Push-Down")).hasValue("apply:filter");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?$orderby=region,amount%20desc&$top=5&$skip=2&$count=true",
            "?$orderby=region%20desc&$top=7",
            "?$filter=" + "amount%20gt%203000" + "&$orderby=sale_month%20desc&$skip=3&$count=true",
            "?$filter=region%20gt%20'M'&$orderby=amount&$top=3&$count=true",
            "?$search=Beta&$orderby=amount&$top=3&$count=true",
            "?$top=4&$select=id,amount",
            "?$skip=10",
            "?$orderby=amount&$top=2&$expand=*",
    })
    void pagingAnswersLikeInMemory(String q) throws Exception {
        HttpResponse<String> pushed = both("PdSales", q);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        assertThat(body(pushed)).as(q).isEqualTo(body(both("MemSales", q)));
    }

    @Test
    void pagingIsPushedOnlyWhenTheFilterIsComplete() throws Exception {
        assertThat(both("PdSales", "?$filter=amount%20gt%203000&$orderby=id&$top=2&$count=true").headers().firstValue("X-Push-Down"))
                .hasValue("filter,orderby,top,count");
        assertThat(both("PdSales", "?$filter=region%20gt%20'M'&$top=2").headers().firstValue("X-Push-Down")).isEmpty();
        assertThat(both("PdSales", "?$search=e&$top=2").headers().firstValue("X-Push-Down")).hasValue("search(partial)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?$filter=region%20eq%20null", "?$filter=region%20gt%20'M'%20and%20amount%20gt%202000",
            "?$search=Alpha", "?$filter=delivered&$search=Beta"})
    void countAnswersLikeInMemory(String q) throws Exception {
        HttpResponse<String> pushed = both("PdSales/$count", q);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        assertThat(pushed.body()).as(q).isEqualTo(both("MemSales/$count", q).body());
    }

    @Test
    void countOnlyQueryWhenTheFilterIsComplete() throws Exception {
        HttpResponse<String> r = both("PdSales/$count", "?$filter=region%20eq%20null");
        assertThat(r.headers().firstValue("X-Push-Down")).hasValue("filter,count");
        assertThat(r.body()).isEqualTo("6");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?$search=Beta&$orderby=id",
            "?$search=AG%20OR%20Nord&$orderby=id",
            "?$search=NOT%20gmbh&$orderby=id",
            "?$search=%22Beta%20AG%22%20AND%20S%C3%BCd&$orderby=id",
            "?$search=e&$orderby=id",
            "?$search=2025&$orderby=id",
            "?$search=NOT%202025&$orderby=id",
            "?$search=true&$orderby=id",
            "?$search=fals%20OR%20Beta&$orderby=id",
            "?$search=e%20AND%20NOT%20true&$orderby=id",
            "?$search=NOT%20e%20OR%20Alpha&$orderby=id",
            "?$search=06-1&$orderby=id",
            "?$search=1.5&$orderby=id",
            "?$search=t1&$orderby=id",
            "?$search=Beta&$filter=amount%20gt%205000&$orderby=amount&$top=3&$count=true",
    })
    void searchAnswersLikeInMemory(String q) throws Exception {
        HttpResponse<String> pushed = both("PdSales", q);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        assertThat(body(pushed)).as(q).isEqualTo(body(both("MemSales", q)));
    }

    @Test
    void searchIsPushedOnlyWhenNoNumberOrDateCanMatch() throws Exception {
        assertThat(both("PdSales", "?$search=Beta").headers().firstValue("X-Push-Down")).hasValue("search");
        assertThat(both("PdSales", "?$search=Beta&$top=2").headers().firstValue("X-Push-Down")).hasValue("search,top");
        // terms that may match numbers, dates or booleans: the database pre-filters, memory decides
        assertThat(both("PdSales", "?$search=e").headers().firstValue("X-Push-Down")).hasValue("search(partial)");
        assertThat(both("PdSales", "?$search=2025").headers().firstValue("X-Push-Down")).hasValue("search(partial)");
        assertThat(both("PdSales/$count", "?$search=Beta").headers().firstValue("X-Push-Down")).hasValue("search,count");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "filter(amount gt 3000)/groupby((customer),aggregate(amount with sum as S,$count as N))|$orderby=customer",
            "aggregate(amount with average as A,amount with min as Mi,amount with max as Ma,region with countdistinct as R,$count as N)|",
            "groupby((region))|$orderby=region",
            "groupby((customer,delivered),aggregate(amount with average as A))|$orderby=customer,delivered",
            "groupby((sold_on),aggregate(amount with sum as S))|$orderby=sold_on",
            "filter(contains(customer,'eta'))/orderby(amount desc)/top(3)|",
            "search(Beta)/aggregate($count as N)|",
            "filter(region eq null)/groupby((customer),aggregate(sale_month with max as M))|$orderby=customer",
            "groupby((rollup(customer,region)),aggregate(amount with sum as S))|$orderby=customer,region",
            "groupby((sold_at),aggregate(amount with sum as S))|$orderby=sold_at",
            "filter(amount gt 3000)/groupby((customer),aggregate(amount with sum as S))|$filter=S gt 20000&$count=true",
            "groupby((customer),aggregate(amount with JdbcOdata.median as M))|$orderby=customer",
            "groupby((customer),aggregate(amount with sum as S))/filter(S gt 20000)|",
            "groupby((customer,region),aggregate($count as N,amount with max as X))/orderby(X desc)/top(3)|",
            "groupby((customer,region),aggregate(amount with sum as S))/orderby(S)/skip(2)/top(3)|",
            "groupby((region),aggregate(amount with min as Mi))/filter(Mi lt 1000 or region eq null)/orderby(Mi desc)|",
            "groupby((customer),aggregate(amount with sum as S))|$filter=S gt 20000 and customer ne 'x'",
            "groupby((customer),aggregate(amount with average as A))/filter(A gt 3000)|",
            "aggregate($count as N)/filter(N gt 3)|",
            "search(\"2025\")/aggregate($count as N)|",
            "search(\"2025\")/groupby((customer),aggregate($count as N))|$orderby=customer",
            "filter(amount gt 1000)/orderby(amount desc,id)/skip(1)/top(4)|",
    })
    void applyAnswersLikeInMemory(String spec) throws Exception {
        String[] p = spec.split("\\|", -1);
        String q = "?$apply=" + enc(p[0]) + (p[1].isEmpty() ? "" : "&" + p[1].replace(" ", "%20"));
        HttpResponse<String> pushed = both("PdSales", q);
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(200);
        JsonNode a = body(pushed);
        JsonNode b = body(both("MemSales", q));
        if (p[1].contains("$orderby") || !a.get("value").isArray() || a.get("value").size() <= 1) {
            assertThat(normalized(a)).as(spec).isEqualTo(normalized(b));
        } else {
            assertThat(java.util.Set.copyOf(rowsOf(normalized(a)))).as(spec).isEqualTo(java.util.Set.copyOf(rowsOf(normalized(b))));
        }
        assertThat(both("PdSales/$count", q).body()).isEqualTo(both("MemSales/$count", q).body());
    }

    /** Numbers compared by value (1.0 = 1.00). */
    private JsonNode normalized(JsonNode n) throws Exception {
        return json.readTree(n.toString().replaceAll("(\\d+)\\.0+(?=[,}\\]])", "$1").replaceAll("(\\d+\\.\\d*?)0+(?=[,}\\]])", "$1"));
    }

    private static List<String> rowsOf(JsonNode n) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode r : n.get("value")) out.add(r.toString());
        return out;
    }

    @Test
    void applyPushDownHeader() throws Exception {
        assertThat(both("PdSales", "?$apply=" + enc("filter(amount gt 3000)/groupby((customer),aggregate(amount with sum as S))"))
                .headers().firstValue("X-Push-Down")).hasValue("apply:filter,groupby");
        assertThat(both("PdSales", "?$apply=" + enc("aggregate(amount with average as A)")).headers().firstValue("X-Push-Down"))
                .hasValue("apply:aggregate");
        assertThat(both("PdSales", "?$apply=" + enc("filter(amount gt 3000)/top(2)")).headers().firstValue("X-Push-Down"))
                .hasValue("apply:filter,top");
        assertThat(both("PdSales", "?$apply=" + enc("groupby((customer),aggregate(amount with sum as S))/filter(S gt 20000)/orderby(S desc)/top(1)"))
                .headers().firstValue("X-Push-Down")).hasValue("apply:groupby,filter,orderby,top");
        assertThat(both("PdSales", "?$apply=" + enc("groupby((customer),aggregate(amount with sum as S))") + "&$filter=" + enc("S gt 20000"))
                .headers().firstValue("X-Push-Down")).hasValue("apply:groupby,$filter");
        // averages are divided in Java, a filter on them stays in memory; so does paging without orderby
        assertThat(both("PdSales", "?$apply=" + enc("groupby((customer),aggregate(amount with average as A))/filter(A gt 1)"))
                .headers().firstValue("X-Push-Down")).hasValue("apply:groupby");
        assertThat(both("PdSales", "?$apply=" + enc("groupby((customer),aggregate(amount with sum as S))/top(1)"))
                .headers().firstValue("X-Push-Down")).hasValue("apply:groupby");
        assertThat(both("PdSales", "?$apply=" + enc("search(\"2025\")/aggregate($count as N)")).headers().firstValue("X-Push-Down"))
                .hasValue("apply:search(partial)");
        assertThat(both("PdSales", "?$apply=" + enc("groupby((sold_at))")).headers().firstValue("X-Push-Down")).isEmpty();
        assertThat(both("PdSales", "?$apply=" + enc("groupby((rollup(customer,region)))")).headers().firstValue("X-Push-Down")).isEmpty();
    }

    @Test
    void singleEntitiesAndNavigationKeepTheNormalPath() throws Exception {
        HttpResponse<String> r = both("PdSales(13)", "");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Push-Down")).isEmpty();
        assertThat(json.readTree(r.body()).get("id").asInt()).isEqualTo(13);
    }
}
