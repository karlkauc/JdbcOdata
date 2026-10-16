package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-memory {@code $filter} / {@code $orderby} against the demo data: 24 rows for 2025, ids 13..36,
 * Alpha GmbH (Nord) ids 13..24 and Beta AG (Süd) ids 25..36, one row per month, delivered when id % 3 != 0.
 */
class ODataFilterTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "customer eq 'Alpha GmbH'                                  | 12",
            "customer ne 'Alpha GmbH'                                  | 12",
            "sale_month ge 10 and delivered eq true                    | 4",
            "sale_month gt 10 or sale_month lt 2                       | 6",
            "sale_month le 1                                           | 2",
            "not delivered                                             | 8",
            "contains(tolower(customer),'beta')                        | 12",
            "startswith(customer,'Alpha') or sale_month eq 12          | 13",
            "endswith(customer,'AG')                                   | 12",
            "toupper(region) eq 'NORD'                                 | 12",
            "length(region) eq 4                                       | 12",
            "indexof(customer,'GmbH') gt 0                             | 12",
            "substring(customer,0,5) eq 'Alpha'                        | 12",
            "substring(customer,6) eq 'GmbH'                           | 12",
            "trim(concat(' ', region)) eq 'Nord'                       | 12",
            "year(sold_at) eq 2025 and month(sold_at) eq 3             | 2",
            "day(sold_at) eq 13                                        | 1",
            "hour(sold_at) ge 0 and minute(sold_at) ge 0 and second(sold_at) eq 0 | 24",
            "date(sold_at) eq 2025-01-13                               | 1",
            "time(sold_at) lt 23:59:59                                 | 24",
            "sold_at ge 2025-06-01T00:00:00Z                           | 14",
            "now() gt sold_at                                          | 24",
            "sale_month in (1,2)                                       | 4",
            "region eq null                                            | 0",
            "region ne null                                            | 24",
            "sale_month add 1 eq 13                                    | 2",
            "sale_month sub 1 eq 0                                     | 2",
            "sale_month mul 2 eq 4                                     | 2",
            "sale_month div 2 eq 1                                     | 2",
            "sale_month mod 2 eq 0                                     | 12",
            "-sale_month lt 0                                          | 24",
            "round(amount) ge 1000 and floor(amount) lt ceiling(amount) | 24",
            "amount gt 5000 and amount le 10000                        | -1",
            "RowId le 3                                                | 3",
            "id eq 13                                                  | 1",
    })
    void filtersRows(String filter, int expected) throws Exception {
        HttpResponse<String> r = get("/odata/Sales?$count=true&$filter=" + enc(filter), "demo", "demo");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        if (expected >= 0) {
            assertThat(body.get("@odata.count").asInt()).as(filter).isEqualTo(expected);
            assertThat(body.get("value").size()).isEqualTo(expected);
        } else {
            assertThat(body.get("value").size()).isGreaterThan(0);
            for (JsonNode row : body.get("value")) {
                assertThat(row.get("amount").decimalValue()).isGreaterThan(java.math.BigDecimal.valueOf(5000));
            }
        }
    }

    @Test
    void ordersRows() throws Exception {
        JsonNode body = json.readTree(get("/odata/Sales?$orderby=" + enc("customer desc,sale_month asc") + "&$top=2", "demo", "demo").body());
        assertThat(body.get("value").get(0).get("id").asInt()).isEqualTo(25);
        assertThat(body.get("value").get(1).get("id").asInt()).isEqualTo(26);

        body = json.readTree(get("/odata/Sales?$orderby=amount&$skip=23&$top=1&$count=true", "demo", "demo").body());
        assertThat(body.get("@odata.count").asInt()).isEqualTo(24);
        JsonNode max = json.readTree(get("/odata/Sales?$orderby=" + enc("amount desc") + "&$top=1", "demo", "demo").body());
        assertThat(body.get("value").get(0).get("amount").decimalValue()).isEqualByComparingTo(max.get("value").get(0).get("amount").decimalValue());

        body = json.readTree(get("/odata/Sales?$filter=" + enc("contains(customer,'Beta')") + "&$orderby=" + enc("amount desc") + "&$top=1", "demo", "demo").body());
        assertThat(body.get("value").get(0).get("customer").asString()).isEqualTo("Beta AG");
    }

    @Test
    void countSegmentHonoursFilter() throws Exception {
        HttpResponse<String> r = get("/odata/Sales/$count?$filter=" + enc("sale_month eq 1"), "demo", "demo");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body().trim()).isEqualTo("2");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "customer gt 1          | 400",
            "sale_month             | 400",
            "length(sale_month) eq 1 | 400",
            "year(customer) eq 1    | 400",
            "date(customer) eq 2025-01-01 | 400",
            "time(customer) eq 10:00:00 | 400",
            "customer add 1 eq 2    | 400",
            "not customer           | 400",
            "-customer eq 1         | 400",
            "@p eq 1                | 501",
            "sale_month eq 'x'      | 400",
            "geo.length(customer) eq 1 | 400",
            "unknown eq 1           | 400",
    })
    void rejectsInvalidFilters(String filter, int status) throws Exception {
        HttpResponse<String> r = get("/odata/Sales?$filter=" + enc(filter), "demo", "demo");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
