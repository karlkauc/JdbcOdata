package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URI;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Change tracking: Prefer odata.track-changes, delta links, changed/new/deleted entities. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeltaLinkTest extends DemoProfileTest {

    private static final String DEMO_URL = "jdbc:h2:mem:demo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID queryId;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(DEMO_URL, "demo", "demo"); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    @BeforeAll
    void defineQuery() throws Exception {
        sql("CREATE TABLE delta_items (item_id INT PRIMARY KEY, name VARCHAR(40), qty INT)",
                "INSERT INTO delta_items VALUES (1, 'Schraube', 10), (2, 'Mutter', 20), (3, 'Scheibe', 30)");
        String ds = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) ds = n.get("datasourceId").asString();
        }
        HttpResponse<String> r = send("POST", "/api/v1/queries", """
                {"name":"DeltaItems","datasourceId":"%s","keyMode":"COLUMNS","cacheEnabled":false,
                 "sql":"SELECT item_id, name, qty FROM delta_items ORDER BY item_id"}
                """.formatted(ds), ADMIN, ADMIN_PASSWORD);
        assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
        queryId = UUID.fromString(json.readTree(r.body()).get("id").asString());
        assertThat(send("POST", "/api/v1/queries/" + queryId + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        HttpResponse<String> cols = send("PUT", "/api/v1/queries/" + queryId + "/columns",
                "{\"columns\":[{\"propertyName\":\"item_id\",\"key\":true,\"visible\":true}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(cols.statusCode()).as(cols.body()).isEqualTo(200);
    }

    @AfterAll
    void removeQuery() throws Exception {
        send("DELETE", "/api/v1/queries/" + queryId, null, ADMIN, ADMIN_PASSWORD);
        sql("DROP TABLE delta_items");
    }

    private HttpResponse<String> getUrl(String urlOrPath, String user, String password, String... headers) throws Exception {
        String path = urlOrPath.startsWith("http")
                ? URI.create(urlOrPath).getRawPath() + (URI.create(urlOrPath).getRawQuery() == null ? "" : "?" + URI.create(urlOrPath).getRawQuery())
                : urlOrPath;
        return send("GET", path, null, user, password, headers);
    }

    private static List<Integer> ids(JsonNode body) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode r : body.get("value")) if (r.has("item_id")) out.add(r.get("item_id").asInt());
        return out;
    }

    private static List<String> removed(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : body.get("value")) {
            if (r.has("@removed") || r.has("@odata.removed") || (r.has("@odata.context") && r.get("@odata.context").asString().contains("deletedEntity"))) {
                out.add(r.has("@id") ? r.get("@id").asString() : r.has("id") ? r.get("id").asString() : r.get("@odata.id").asString());
            }
        }
        return out;
    }

    @Test
    void deltaLinkReportsChangedNewAndDeletedEntities() throws Exception {
        HttpResponse<String> initial = getUrl("/odata/DeltaItems?$filter=qty%20lt%20100&$select=item_id,name", "demo", "demo",
                "Prefer", "odata.track-changes");
        assertThat(initial.statusCode()).as(initial.body()).isEqualTo(200);
        assertThat(initial.headers().firstValue("Preference-Applied").orElse("")).contains("odata.track-changes");
        JsonNode body = json.readTree(initial.body());
        assertThat(ids(body)).containsExactly(1, 2, 3);
        String deltaLink = body.get("@odata.deltaLink").asString();
        assertThat(deltaLink).contains("$deltatoken=").contains("$filter=").startsWith("http");

        // nothing changed
        JsonNode same = json.readTree(getUrl(deltaLink, "demo", "demo").body());
        assertThat(same.get("value").size()).isZero();
        assertThat(same.get("@odata.context").asString()).endsWith("/$delta").doesNotContain("$delta/$delta");

        sql("UPDATE delta_items SET name = 'Schraube M6' WHERE item_id = 1",
                "DELETE FROM delta_items WHERE item_id = 2",
                "INSERT INTO delta_items VALUES (4, 'Dübel', 40)",
                "UPDATE delta_items SET qty = 300 WHERE item_id = 3"); // leaves the filter: counts as removed
        HttpResponse<String> changes = getUrl(deltaLink, "demo", "demo");
        assertThat(changes.statusCode()).as(changes.body()).isEqualTo(200);
        JsonNode delta = json.readTree(changes.body());
        for (JsonNode r : delta.get("value")) {
            if (r.has("@odata.context")) {
                assertThat(r.get("@odata.context").asString()).isEqualTo("#DeltaItems/$deletedEntity");
                assertThat(r.get("reason").asString()).isEqualTo("changed"); // the request has a $filter
            }
        }
        assertThat(ids(delta)).containsExactlyInAnyOrder(1, 4);
        assertThat(removed(delta)).hasSize(2).anySatisfy(id -> assertThat(id).contains("item_id=2"))
                .anySatisfy(id -> assertThat(id).contains("item_id=3"));
        for (JsonNode r : delta.get("value")) assertThat(r.has("qty")).isFalse(); // $select applies

        // the next link starts from the new state
        JsonNode after = json.readTree(getUrl(delta.get("@odata.deltaLink").asString(), "demo", "demo").body());
        assertThat(after.get("value").size()).isZero();
        // the old link still compares with its own snapshot
        assertThat(ids(json.readTree(getUrl(deltaLink, "demo", "demo").body()))).containsExactlyInAnyOrder(1, 4);
        // 4.01 clients get @removed
        JsonNode v401 = json.readTree(getUrl(deltaLink, "demo", "demo", "OData-MaxVersion", "4.01").body());
        assertThat(removed(v401)).hasSize(2);
        // only for the caller who created it
        assertThat(getUrl(deltaLink, ADMIN, ADMIN_PASSWORD).statusCode()).isIn(401, 410);
        sql("UPDATE delta_items SET name = 'Schraube', qty = 30 WHERE item_id IN (1, 3)",
                "UPDATE delta_items SET qty = 10 WHERE item_id = 1",
                "INSERT INTO delta_items VALUES (2, 'Mutter', 20)", "DELETE FROM delta_items WHERE item_id = 4");
    }

    @Test
    void trackingNeedsAStableWindow() throws Exception {
        // $top: the snapshot is the window
        HttpResponse<String> top = getUrl("/odata/DeltaItems?$top=2&$orderby=item_id", "demo", "demo", "Prefer", "odata.track-changes");
        assertThat(top.statusCode()).isEqualTo(200);
        String topLink = json.readTree(top.body()).get("@odata.deltaLink").asString();
        sql("INSERT INTO delta_items VALUES (0, 'Nagel', 5)");
        JsonNode moved = json.readTree(getUrl(topLink, "demo", "demo").body());
        sql("DELETE FROM delta_items WHERE item_id = 0");
        assertThat(ids(moved)).containsExactly(0);
        assertThat(removed(moved)).singleElement().satisfies(id -> assertThat(id).contains("item_id=2"));
        // snapshots are in the metadata database: a new tracker (e.g. after a restart) still knows the token
        String token = topLink.substring(topLink.indexOf("$deltatoken=") + "$deltatoken=".length());
        assertThat(new DeltaTracker(jdbc).find(token, "demo", "/DeltaItems?$top=2&$orderby=item_id")).isNotNull();
        // with server-driven paging the link is on the last page
        String url = "/odata/DeltaItems";
        String deltaLink = null;
        int pages = 0;
        while (url != null) {
            JsonNode page = json.readTree(getUrl(url, "demo", "demo", "Prefer", "odata.track-changes,odata.maxpagesize=2").body());
            pages++;
            url = page.has("@odata.nextLink") ? page.get("@odata.nextLink").asString() : null;
            if (url != null) assertThat(page.has("@odata.deltaLink")).isFalse();
            else deltaLink = page.get("@odata.deltaLink").asString();
        }
        assertThat(pages).isGreaterThan(1);
        assertThat(json.readTree(getUrl(deltaLink, "demo", "demo").body()).get("value").size()).isZero();
        assertThat(getUrl("/odata/DeltaItems?$apply=aggregate($count%20as%20N)&$deltatoken=0123456789abcdef0123456789abcdef", "demo", "demo").statusCode())
                .isEqualTo(400);
        assertThat(getUrl("/odata/$metadata", "demo", "demo").body()).contains("Capabilities.ChangeTracking");
    }
}
