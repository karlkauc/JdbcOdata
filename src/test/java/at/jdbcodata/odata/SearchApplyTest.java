package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** $search and $apply on the demo {@code Sales} feed; expectations are computed from the plain rows. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchApplyTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> rows = new ArrayList<>();

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode odata(String path) throws Exception {
        HttpResponse<String> r = get(path, "demo", "demo");
        assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    @BeforeAll
    void loadRows() throws Exception {
        odata("/odata/Sales").get("value").forEach(rows::add);
        assertThat(rows).hasSize(24);
    }

    private long rowsWhere(java.util.function.Predicate<JsonNode> p) {
        return rows.stream().filter(p).count();
    }

    private static boolean anyField(JsonNode row, String needle) {
        for (Map.Entry<String, JsonNode> f : row.properties()) {
            if (!f.getValue().isNull() && f.getValue().asString().toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    // --- $search ------------------------------------------------------------------------------------------------

    @Test
    void searchTermsPhrasesAndOperators() throws Exception {
        String customer = rows.get(0).get("customer").asString();
        String word = customer.split(" ")[0].toLowerCase(Locale.ROOT);

        JsonNode one = odata("/odata/Sales?$count=true&$search=" + enc(word));
        assertThat(one.get("@odata.count").asLong()).isEqualTo(rowsWhere(r -> anyField(r, word))).isPositive();

        JsonNode phrase = odata("/odata/Sales?$count=true&$search=" + enc("\"" + customer + "\""));
        String lower = customer.toLowerCase(Locale.ROOT);
        assertThat(phrase.get("@odata.count").asLong()).isEqualTo(rowsWhere(r -> anyField(r, lower)));

        JsonNode not = odata("/odata/Sales?$count=true&$search=" + enc("NOT " + word));
        assertThat(not.get("@odata.count").asLong()).isEqualTo(24 - one.get("@odata.count").asLong());

        String other = rows.stream().map(r -> r.get("customer").asString()).filter(c -> !c.equals(customer)).findFirst()
                .orElseThrow().split(" ")[0].toLowerCase(Locale.ROOT);
        JsonNode or = odata("/odata/Sales?$count=true&$search=" + enc(word + " OR " + other));
        assertThat(or.get("@odata.count").asLong()).isEqualTo(rowsWhere(r -> anyField(r, word) || anyField(r, other)));

        // $search combines with $filter
        JsonNode both = odata("/odata/Sales?$count=true&$search=" + enc(word) + "&$filter=" + enc("amount gt 0"));
        assertThat(both.get("@odata.count").asLong())
                .isEqualTo(rowsWhere(r -> anyField(r, word) && r.get("amount").decimalValue().signum() > 0));
        assertThat(get("/odata/Sales/$count?$search=" + enc(word), "demo", "demo").body())
                .isEqualTo(String.valueOf(one.get("@odata.count").asLong()));
    }

    // --- $apply -------------------------------------------------------------------------------------------------

    @Test
    void aggregateWholeSet() throws Exception {
        JsonNode r = odata("/odata/Sales?$apply=" + enc("aggregate(amount with sum as Total,amount with max as Max,"
                + "amount with average as Avg,customer with countdistinct as Customers,$count as N)"));
        assertThat(r.get("@odata.context").asString()).isEqualTo("$metadata#Sales(Total,Max,Avg,Customers,N)");
        JsonNode agg = r.get("value").get(0);
        BigDecimal sum = rows.stream().map(x -> x.get("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal max = rows.stream().map(x -> x.get("amount").decimalValue()).max(Comparator.naturalOrder()).orElseThrow();
        assertThat(agg.get("Total").decimalValue()).isEqualByComparingTo(sum);
        assertThat(agg.get("Max").decimalValue()).isEqualByComparingTo(max);
        assertThat(agg.get("Avg").decimalValue().doubleValue()).isCloseTo(sum.doubleValue() / 24, org.assertj.core.data.Offset.offset(1e-6));
        Set<String> customers = new HashSet<>();
        rows.forEach(x -> customers.add(x.get("customer").asString()));
        assertThat(agg.get("Customers").asInt()).isEqualTo(customers.size());
        assertThat(agg.get("N").asInt()).isEqualTo(24);
    }

    @Test
    void groupByWithAggregateThenOrderByAlias() throws Exception {
        JsonNode r = odata("/odata/Sales?$count=true&$apply=" + enc("groupby((region),aggregate(amount with sum as Total,$count as N))")
                + "&$orderby=" + enc("Total desc"));
        Map<String, BigDecimal> expected = new TreeMap<>();
        rows.forEach(x -> expected.merge(x.get("region").asString(), x.get("amount").decimalValue(), BigDecimal::add));
        assertThat(r.get("@odata.count").asInt()).isEqualTo(expected.size());
        BigDecimal previous = null;
        for (JsonNode g : r.get("value")) {
            assertThat(g.get("Total").decimalValue()).isEqualByComparingTo(expected.get(g.get("region").asString()));
            if (previous != null) assertThat(g.get("Total").decimalValue()).isLessThanOrEqualTo(previous);
            previous = g.get("Total").decimalValue();
        }
        // groupby without aggregate = distinct values; filter before grouping; $filter on an alias afterwards.
        JsonNode distinct = odata("/odata/Sales?$apply=" + enc("groupby((customer,region))"));
        Set<String> pairs = new HashSet<>();
        rows.forEach(x -> pairs.add(x.get("customer").asString() + "|" + x.get("region").asString()));
        assertThat(distinct.get("value").size()).isEqualTo(pairs.size());
        String region = expected.keySet().iterator().next();
        JsonNode filtered = odata("/odata/Sales?$apply=" + enc("filter(region eq '" + region + "')/aggregate($count as N)"));
        assertThat(filtered.get("value").get(0).get("N").asLong()).isEqualTo(rowsWhere(x -> region.equals(x.get("region").asString())));
        JsonNode big = odata("/odata/Sales?$apply=" + enc("groupby((region),aggregate(amount with sum as Total))")
                + "&$filter=" + enc("Total gt 0"));
        assertThat(big.get("value").size()).isEqualTo(expected.size());
    }

    @Test
    void topCountComputeSearchAndConcat() throws Exception {
        JsonNode top = odata("/odata/Sales?$apply=" + enc("topcount(3,amount)"));
        List<BigDecimal> amounts = rows.stream().map(x -> x.get("amount").decimalValue()).sorted(Comparator.reverseOrder()).toList();
        assertThat(top.get("value")).hasSize(3);
        assertThat(top.get("value").get(0).get("amount").decimalValue()).isEqualByComparingTo(amounts.get(0));
        assertThat(top.get("value").get(2).get("amount").decimalValue()).isEqualByComparingTo(amounts.get(2));

        JsonNode bottom = odata("/odata/Sales?$apply=" + enc("bottomcount(1,amount)"));
        assertThat(bottom.get("value").get(0).get("amount").decimalValue()).isEqualByComparingTo(amounts.get(amounts.size() - 1));

        JsonNode computed = odata("/odata/Sales?$top=1&$orderby=RowId&$apply=" + enc("compute(amount mul 2 as Double)"));
        JsonNode first = computed.get("value").get(0);
        assertThat(first.get("Double").decimalValue()).isEqualByComparingTo(first.get("amount").decimalValue().multiply(BigDecimal.TWO));

        String word = rows.get(0).get("customer").asString().split(" ")[0].toLowerCase(Locale.ROOT);
        JsonNode searched = odata("/odata/Sales?$apply=" + enc("search(" + word + ")/aggregate($count as N)"));
        assertThat(searched.get("value").get(0).get("N").asLong()).isEqualTo(rowsWhere(r -> anyField(r, word)));

        JsonNode concat = odata("/odata/Sales?$apply=" + enc("concat(aggregate($count as N),topcount(1,amount))"));
        assertThat(concat.get("value")).hasSize(2);

        assertThat(get("/odata/Sales/$count?$apply=" + enc("groupby((region))"), "demo", "demo").body())
                .isEqualTo(String.valueOf(rows.stream().map(x -> x.get("region").asString()).distinct().count()));
        assertThat(get("/odata/Sales?$apply=" + enc("groupby(rollup(region))"), "demo", "demo").statusCode()).isIn(400, 501);
    }
}
