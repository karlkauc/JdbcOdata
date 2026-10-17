package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parametrised queries as composable OData functions: {@code GetSales(p_year=2024)} runs the Sales query with
 * {@code p_year=2024}; the result accepts keys, $count and all query options.
 */
class QueryFunctionTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

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

    private static void allOfYear(JsonNode rows, int year) {
        assertThat(rows.size()).isPositive();
        for (JsonNode row : rows) assertThat(row.get("sale_year").asInt()).isEqualTo(year);
    }

    @Test
    void metadataAndServiceDocumentDeclareTheFunction() throws Exception {
        String md = call("$metadata").body();
        assertThat(md).contains("<Function Name=\"GetSales\" IsComposable=\"true\">")
                .contains("<Parameter Name=\"p_year\" Type=\"Edm.Int64\"")
                .contains("<FunctionImport Name=\"GetSales\" Function=\"JdbcOdata.GetSales\" EntitySet=\"Sales\"");
        assertThat(ok("").toString()).contains("\"name\":\"GetSales\"").contains("FunctionImport");
    }

    @Test
    void parametersSelectTheRowsAndNullOrNoneUseTheDefault() throws Exception {
        JsonNode y2024 = ok("GetSales(p_year=2024)");
        allOfYear(y2024.get("value"), 2024);
        assertThat(y2024.get("value").size()).isEqualTo(12);
        assertThat(y2024.get("@odata.context").asString()).contains("$metadata#Sales");
        allOfYear(ok("GetSales()").get("value"), 2025);
        allOfYear(ok("GetSales(p_year=null)").get("value"), 2025);
        allOfYear(ok("GetSales(p_year=@y)?@y=2024").get("value"), 2024);
        // the function argument wins over a custom query option of the same name
        allOfYear(ok("GetSales(p_year=2024)?p_year=2025").get("value"), 2024);
        allOfYear(ok("Sales?p_year=2024").get("value"), 2024);
    }

    @Test
    void theResultIsComposable() throws Exception {
        assertThat(call("GetSales(p_year=2024)/$count").body()).isEqualTo("12");
        JsonNode r = ok("GetSales(p_year=2024)?$filter=" + enc("sale_month le 3") + "&$orderby=" + enc("amount desc")
                + "&$top=2&$count=true&$select=sale_year,sale_month,amount");
        assertThat(r.get("@odata.count").asInt()).isEqualTo(6);
        assertThat(r.get("value").size()).isEqualTo(2);
        allOfYear(r.get("value"), 2024);
        assertThat(r.get("value").get(0).get("amount").decimalValue())
                .isGreaterThanOrEqualTo(r.get("value").get(1).get("amount").decimalValue());
        JsonNode single = ok("GetSales(p_year=2024)(3)");
        assertThat(single.get("sale_year").asInt()).isEqualTo(2024);
        assertThat(single.get("RowId").asInt()).isEqualTo(3);
        assertThat(call("GetSales(p_year=2024)(99)").statusCode()).isEqualTo(404);
        assertThat(ok("GetSales(p_year=2024)?$apply=" + enc("aggregate($count as N)")).get("value").get(0).get("N").asInt())
                .isEqualTo(12);
    }

    @Test
    void wrongArgumentsAreRejected() throws Exception {
        assertThat(call("GetSales(p_year='abc')").statusCode()).isEqualTo(400);
        assertThat(call("GetSales(other=1)").statusCode()).isIn(400, 404);
        assertThat(call("GetNothing(p_year=1)").statusCode()).isIn(400, 404);
    }
}
