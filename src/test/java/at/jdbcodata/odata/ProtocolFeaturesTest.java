package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-driven paging, ETags/304, parameter aliases and $batch on the demo {@code Sales} feed (24 rows). */
class ProtocolFeaturesTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<String> getWith(String pathOrUrl, String... headers) throws Exception {
        String path = pathOrUrl.startsWith("http") ? URI.create(pathOrUrl).getRawPath()
                + (URI.create(pathOrUrl).getRawQuery() == null ? "" : "?" + URI.create(pathOrUrl).getRawQuery()) : pathOrUrl;
        return send("GET", path, null, "demo", "demo", headers);
    }

    // --- paging -------------------------------------------------------------------------------------------------

    @Test
    void preferMaxPageSizeYieldsNextLinksThatCoverTheWholeResult() throws Exception {
        List<Long> ids = new ArrayList<>();
        String url = "/odata/Sales?$count=true&$orderby=RowId";
        int pages = 0;
        while (url != null) {
            HttpResponse<String> r = getWith(url, "Prefer", "odata.maxpagesize=10");
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(r.headers().firstValue("Preference-Applied")).hasValue("odata.maxpagesize=10");
            JsonNode body = json.readTree(r.body());
            assertThat(body.get("@odata.count").asInt()).isEqualTo(24);
            body.get("value").forEach(row -> ids.add(row.get("RowId").asLong()));
            assertThat(body.get("value").size()).isLessThanOrEqualTo(10);
            url = body.has("@odata.nextLink") ? body.get("@odata.nextLink").asString() : null;
            if (url != null) assertThat(url).contains("$skiptoken=").startsWith("http");
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(ids).hasSize(24).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void pagingRespectsTopAndWorksForApply() throws Exception {
        HttpResponse<String> first = getWith("/odata/Sales?$top=15&$orderby=RowId", "Prefer", "odata.maxpagesize=10");
        JsonNode body = json.readTree(first.body());
        assertThat(body.get("value").size()).isEqualTo(10);
        JsonNode second = json.readTree(getWith(body.get("@odata.nextLink").asString(), "Prefer", "odata.maxpagesize=10").body());
        assertThat(second.get("value").size()).isEqualTo(5);
        assertThat(second.has("@odata.nextLink")).isFalse();

        JsonNode grouped = json.readTree(getWith("/odata/Sales?$apply=" + enc("groupby((customer))"), "Prefer", "odata.maxpagesize=1").body());
        assertThat(grouped.get("value").size()).isEqualTo(1);
        assertThat(grouped.get("@odata.nextLink").asString()).contains("$skiptoken=1");

        assertThat(getWith("/odata/Sales?$skiptoken=abc").statusCode()).isEqualTo(400);
        // Without a page size the whole result comes in one response, as before.
        assertThat(json.readTree(getWith("/odata/Sales").body()).get("value").size()).isEqualTo(24);
    }

    @Test
    void nextLinkReplacesAnExistingSkipToken() {
        assertThat(QueryEntityCollectionProcessor.nextLink("https://h/odata/Sales?$top=5&$skiptoken=10&p=1", 20))
                .isEqualTo("https://h/odata/Sales?$top=5&p=1&$skiptoken=20");
        assertThat(QueryEntityCollectionProcessor.nextLink("https://h/odata/Sales", 3)).isEqualTo("https://h/odata/Sales?$skiptoken=3");
    }

    // --- ETag ---------------------------------------------------------------------------------------------------

    @Test
    void etagAndNotModified() throws Exception {
        HttpResponse<String> first = getWith("/odata/Sales?$top=3");
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("W/\"");
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("private, no-cache");

        HttpResponse<String> again = getWith("/odata/Sales?$top=3", "If-None-Match", etag);
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(again.headers().firstValue("ETag")).hasValue(etag);

        HttpResponse<String> other = getWith("/odata/Sales?$top=4", "If-None-Match", etag);
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);

        assertThat(getWith("/odata/Sales(1)", "If-None-Match", getWith("/odata/Sales(1)").headers().firstValue("ETag").orElseThrow())
                .statusCode()).isEqualTo(304);
        assertThat(getWith("/odata/Sales/$count", "If-None-Match", "*").statusCode()).isEqualTo(304);

        HttpResponse<String> metadata = getWith("/odata/$metadata");
        String metaTag = metadata.headers().firstValue("ETag").orElseThrow();
        assertThat(getWith("/odata/$metadata", "If-None-Match", metaTag).statusCode()).isEqualTo(304);
    }

    // --- parameter aliases --------------------------------------------------------------------------------------

    @Test
    void parameterAliases() throws Exception {
        JsonNode direct = json.readTree(getWith("/odata/Sales?$count=true&$filter=" + enc("customer eq 'Alpha GmbH'")).body());
        JsonNode aliased = json.readTree(getWith("/odata/Sales?$count=true&$filter=" + enc("customer eq @c") + "&@c="
                + enc("'Alpha GmbH'")).body());
        assertThat(aliased.get("@odata.count").asInt()).isEqualTo(direct.get("@odata.count").asInt()).isPositive();

        JsonNode numeric = json.readTree(getWith("/odata/Sales?$count=true&$filter=" + enc("amount gt @min") + "&@min=1000").body());
        JsonNode numericDirect = json.readTree(getWith("/odata/Sales?$count=true&$filter=" + enc("amount gt 1000")).body());
        assertThat(numeric.get("@odata.count").asInt()).isEqualTo(numericDirect.get("@odata.count").asInt());

        HttpResponse<String> missing = getWith("/odata/Sales?$filter=" + enc("amount gt @nope"));
        assertThat(missing.statusCode()).isIn(400, 501);
    }

    // --- $batch -------------------------------------------------------------------------------------------------

    @Test
    void batchWithReadsAndARejectedChangeSet() throws Exception {
        String body = String.join("\r\n",
                "--batch_1",
                "Content-Type: application/http",
                "Content-Transfer-Encoding: binary",
                "",
                "GET Sales(1) HTTP/1.1",
                "Accept: application/json",
                "",
                "",
                "--batch_1",
                "Content-Type: application/http",
                "Content-Transfer-Encoding: binary",
                "",
                "GET Sales/$count?p_year=2024 HTTP/1.1",
                "",
                "",
                "--batch_1",
                "Content-Type: multipart/mixed;boundary=changeset_1",
                "",
                "--changeset_1",
                "Content-Type: application/http",
                "Content-Transfer-Encoding: binary",
                "Content-ID: 1",
                "",
                "DELETE Sales(1) HTTP/1.1",
                "",
                "",
                "--changeset_1--",
                "--batch_1--",
                "");
        HttpResponse<String> r = sendRaw(body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("multipart/mixed;boundary=");
        assertThat(r.body()).contains("HTTP/1.1 200").contains("\"RowId\":1");
        String sales2024 = getWith("/odata/Sales/$count?p_year=2024").body();
        assertThat(r.body()).contains("\r\n" + sales2024);
        assertThat(r.body()).contains("405");
    }

    private HttpResponse<String> sendRaw(String body) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/$batch"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "multipart/mixed;boundary=batch_1")
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("demo:demo".getBytes()))
                .build();
        return java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
