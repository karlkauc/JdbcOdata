package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** SQL arrays and JSON columns as collection-valued properties, lambdas over them and $select with options. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectionPropertyTest extends DemoProfileTest {

    private static final String DEMO_URL = "jdbc:h2:mem:demo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<UUID> created = new ArrayList<>();

    private static void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(DEMO_URL, "demo", "demo"); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private UUID query(String body, String columns) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/queries", body, ADMIN, ADMIN_PASSWORD);
        assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
        UUID id = UUID.fromString(json.readTree(r.body()).get("id").asString());
        created.add(id);
        HttpResponse<String> snap = send("POST", "/api/v1/queries/" + id + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(snap.statusCode()).as(snap.body()).isEqualTo(200);
        HttpResponse<String> cols = send("PUT", "/api/v1/queries/" + id + "/columns", "{\"columns\":[" + columns + "]}", ADMIN, ADMIN_PASSWORD);
        assertThat(cols.statusCode()).as(cols.body()).isEqualTo(200);
        return id;
    }

    @BeforeAll
    void defineModel() throws Exception {
        sql("CREATE TABLE auftraege (auftrag_id INT PRIMARY KEY, kunde VARCHAR(40), tags VARCHAR(20) ARRAY, mengen INT ARRAY, positionen JSON, info JSON)",
                """
                INSERT INTO auftraege VALUES
                (1, 'Alpha', ARRAY['neu','eilig'], ARRAY[3,1,2], JSON '[{"Artikel":"Schraube","Menge":10,"Betrag":12.50,"Seriennummern":["S1","S2"]},{"Artikel":"Monitor","Menge":1,"Betrag":249.00,"Seriennummern":["M9"]}]', JSON '{"Ort":"Wien","Land":"AT","Plz":1010}'),
                (2, 'Beta', ARRAY['alt'], ARRAY[5], JSON '[{"Artikel":"Maus","Menge":2,"Betrag":30.00,"Seriennummern":[]}]', JSON '{"Ort":"Graz","Land":"AT","Plz":8010}'),
                (3, 'Gamma', ARRAY[]::VARCHAR ARRAY, NULL, JSON '[]', JSON '{"Ort":"Berlin","Land":"DE"}'),
                (4, 'Delta', NULL, ARRAY[7,0], NULL, NULL)
                """);
        String ds = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) ds = n.get("datasourceId").asString();
        }
        String sql = "SELECT auftrag_id, kunde, tags, mengen, positionen, info FROM auftraege ORDER BY auftrag_id";
        query("""
                {"name":"Auftraege","datasourceId":"%s","keyMode":"COLUMNS","pushDown":true,"cacheEnabled":true,"sql":"%s"}
                """.formatted(ds, sql),
                """
                {"propertyName":"auftrag_id","key":true,"visible":true},
                {"propertyName":"tags","key":false,"visible":true,"collection":"PRIMITIVE","textCopy":true},
                {"propertyName":"info","key":false,"visible":true,"collection":"OBJECT"},
                {"propertyName":"mengen","key":false,"visible":true,"collection":"PRIMITIVE"},
                {"propertyName":"positionen","key":false,"visible":true,"collection":"COMPLEX"}
                """);
        // the same columns without opt-in: plain text as before
        query("""
                {"name":"AuftraegeText","datasourceId":"%s","keyMode":"COLUMNS","sql":"%s",
                 "relations":[{"name":"Details","targetQuery":"Auftraege","localColumns":["auftrag_id"],"targetColumns":["auftrag_id"],"toMany":true}]}
                """.formatted(ds, sql), "{\"propertyName\":\"auftrag_id\",\"key\":true,\"visible\":true}");
    }

    @AfterAll
    void removeModel() throws Exception {
        for (UUID id : created) send("DELETE", "/api/v1/queries/" + id, null, ADMIN, ADMIN_PASSWORD);
        sql("DROP TABLE auftraege");
    }

    private JsonNode body(String path) throws Exception {
        HttpResponse<String> r = get("/odata/" + path, "demo", "demo");
        assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private List<Integer> ids(String path) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (JsonNode n : body(path).get("value")) out.add(n.get("auftrag_id").asInt());
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.isNull() ? null : n.asString());
        return out;
    }

    @Test
    void metadataDeclaresCollectionsAndComplexTypes() throws Exception {
        String md = get("/odata/$metadata", "demo", "demo").body();
        assertThat(md).contains("<Property Name=\"tags\" Type=\"Collection(Edm.String)\"")
                .contains("<Property Name=\"mengen\" Type=\"Collection(Edm.Int32)\"")
                .contains("<Property Name=\"positionen\" Type=\"Collection(JdbcOdata.Auftraege_positionen)\"")
                .contains("<ComplexType Name=\"Auftraege_positionen\">")
                .contains("<Property Name=\"Seriennummern\" Type=\"Collection(Edm.String)\"")
                .contains("<Property Name=\"Betrag\" Type=\"Edm.Decimal\"");
        // without opt-in the snapshot only offers the collection form
        JsonNode q = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("AuftraegeText".equals(n.get("name").asString())) q = n;
        }
        for (JsonNode c : q.get("columns")) {
            assertThat(c.get("collection").asString()).isEqualTo("NONE");
            if (c.get("propertyName").asString().equals("mengen")) assertThat(c.get("elementType").asString()).isEqualTo("Edm.Int32");
            if (c.get("propertyName").asString().equals("positionen")) assertThat(c.get("structure").size()).isEqualTo(4);
        }
    }

    @Test
    void collectionsAreWrittenAsArrays() throws Exception {
        JsonNode first = body("Auftraege(1)");
        assertThat(texts(first.get("tags"))).containsExactly("neu", "eilig");
        assertThat(first.get("mengen").get(0).asInt()).isEqualTo(3);
        assertThat(first.get("positionen").get(1).get("Artikel").asString()).isEqualTo("Monitor");
        assertThat(first.get("positionen").get(0).get("Betrag").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(texts(first.get("positionen").get(0).get("Seriennummern"))).containsExactly("S1", "S2");
        JsonNode all = body("Auftraege?$orderby=auftrag_id");
        assertThat(all.get("value").get(2).get("tags").size()).isZero();
        assertThat(all.get("value").get(3).get("tags").isNull() || all.get("value").get(3).get("tags").size() == 0).isTrue();
        // served from the cache the second time: same values
        assertThat(body("Auftraege?$orderby=auftrag_id")).isEqualTo(all);
        // not opted in: text as before
        JsonNode text = body("AuftraegeText(1)");
        assertThat(text.get("tags").asString()).isEqualTo("{neu,eilig}");
        assertThat(text.get("positionen").asString()).startsWith("[{\"Artikel\"");
    }

    @Test
    void lambdasAndCountOverCollections() throws Exception {
        assertThat(ids("Auftraege?$filter=" + enc("tags/any(t:t eq 'eilig')"))).containsExactly(1);
        assertThat(ids("Auftraege?$filter=" + enc("mengen/all(m:m gt 0)"))).containsExactly(1, 2, 3);
        assertThat(ids("Auftraege?$filter=" + enc("positionen/any(p:p/Betrag gt 100)"))).containsExactly(1);
        assertThat(ids("Auftraege?$filter=" + enc("positionen/any(p:p/Seriennummern/any(s:s eq 'M9'))"))).containsExactly(1);
        assertThat(ids("Auftraege?$filter=" + enc("tags/$count ge 1") + "&$orderby=" + enc("positionen/$count desc,auftrag_id"))).containsExactly(1, 2);
        assertThat(ids("Auftraege?$filter=" + enc("not tags/any()"))).containsExactly(3, 4);
        // the plain condition goes to the database, the lambda stays in memory
        HttpResponse<String> r = get("/odata/Auftraege?$filter=" + enc("kunde ne 'Beta' and tags/any(t:t eq 'neu')"), "demo", "demo");
        assertThat(r.headers().firstValue("X-Push-Down").orElse("")).contains("filter(partial)");
        assertThat(json.readTree(r.body()).get("value").size()).isEqualTo(1);
        // $search looks into the elements
        assertThat(ids("Auftraege?$search=Monitor")).containsExactly(1);
        assertThat(ids("Auftraege?$search=alt")).containsExactly(2);
    }

    @Test
    void selectWithOptionsOnPrimitiveCollections() throws Exception {
        JsonNode r = body("Auftraege?$filter=auftrag_id%20eq%201&$select=" + enc("tags($orderby=$this;$top=1),mengen($filter=$this ge 2;$orderby=$this desc;$count=true)"));
        JsonNode row = r.get("value").get(0);
        assertThat(texts(row.get("tags"))).containsExactly("eilig");
        assertThat(row.get("mengen").get(0).asInt()).isEqualTo(3);
        assertThat(row.get("mengen").size()).isEqualTo(2);
        assertThat(row.get("mengen@odata.count").asInt()).isEqualTo(2);
        assertThat(row.has("kunde")).isFalse();
        assertThat(r.get("@odata.context").asString()).endsWith("tags,mengen)");
        JsonNode skipped = body("Auftraege(1)?$select=" + enc("tags($skip=1),mengen($search=1)"));
        assertThat(texts(skipped.get("tags"))).containsExactly("eilig");
        assertThat(skipped.get("mengen").size()).isEqualTo(1);
    }

    @Test
    void selectWithOptionsOnComplexCollections() throws Exception {
        JsonNode row = body("Auftraege?$filter=auftrag_id%20eq%201&$select=auftrag_id," + enc(
                "positionen($filter=Betrag gt 10;$orderby=Betrag desc;$select=Artikel,Betrag;$count=true)")).get("value").get(0);
        assertThat(row.get("positionen@odata.count").asInt()).isEqualTo(2);
        assertThat(row.get("positionen").get(0).get("Artikel").asString()).isEqualTo("Monitor");
        assertThat(row.get("positionen").get(0).has("Menge")).isFalse();
        assertThat(row.get("positionen").get(0).has("Seriennummern")).isFalse();
        // nested options on a collection inside the elements
        JsonNode nested = body("Auftraege(1)?$select=" + enc("positionen($top=1;$select=Artikel,Seriennummern($orderby=$this desc;$top=1;$count=true))"));
        JsonNode p = nested.get("positionen");
        assertThat(p.size()).isEqualTo(1);
        assertThat(texts(p.get(0).get("Seriennummern"))).containsExactly("S2");
        assertThat(p.get(0).get("Seriennummern@odata.count").asInt()).isEqualTo(2);
        // $search and lambdas inside the options
        JsonNode searched = body("Auftraege(1)?$select=" + enc("positionen($search=schraube OR maus)"));
        assertThat(searched.get("positionen").size()).isEqualTo(1);
        JsonNode lambda = body("Auftraege(1)?$select=" + enc("positionen($filter=Seriennummern/any(s:s eq 'M9'))"));
        assertThat(lambda.get("positionen").get(0).get("Artikel").asString()).isEqualTo("Monitor");
    }

    @Test
    void selectOptionsInsideExpandAndApply() throws Exception {
        JsonNode r = body("AuftraegeText?$filter=auftrag_id%20le%202&$orderby=auftrag_id&$select=auftrag_id&$expand="
                + enc("Details($select=tags($top=1),positionen($orderby=Betrag desc;$top=1;$select=Artikel))"));
        JsonNode first = r.get("value").get(0).get("Details").get(0);
        assertThat(texts(first.get("tags"))).containsExactly("neu");
        assertThat(first.get("positionen").size()).isEqualTo(1);
        assertThat(first.get("positionen").get(0).get("Artikel").asString()).isEqualTo("Monitor");
        assertThat(first.get("positionen").get(0).has("Betrag")).isFalse();
        // $apply keeps the entities: collections and options still apply
        JsonNode applied = body("Auftraege?$apply=" + enc("filter(tags/any(t:t eq 'alt'))") + "&$select=" + enc("auftrag_id,mengen($count=true)"));
        assertThat(applied.get("value").get(0).get("auftrag_id").asInt()).isEqualTo(2);
        assertThat(applied.get("value").get(0).get("mengen@odata.count").asInt()).isEqualTo(1);
    }

    @Test
    void deltaLinksAndXml() throws Exception {
        String path = "/odata/Auftraege?$select=" + enc("auftrag_id,tags($top=1)");
        HttpResponse<String> initial = send("GET", path, null, "demo", "demo", "Prefer", "odata.track-changes");
        assertThat(initial.statusCode()).as(initial.body()).isEqualTo(200);
        JsonNode body = json.readTree(initial.body());
        assertThat(body.get("value").get(0).get("tags").size()).isEqualTo(1);
        java.net.URI link = java.net.URI.create(body.get("@odata.deltaLink").asString());
        HttpResponse<String> delta = get(link.getRawPath() + "?" + link.getRawQuery(), "demo", "demo");
        assertThat(delta.statusCode()).as(delta.body()).isEqualTo(200);
        assertThat(json.readTree(delta.body()).get("value").size()).isZero();
        HttpResponse<String> xml = get("/odata/Auftraege(1)?$format=xml", "demo", "demo");
        if (xml.statusCode() == 200) assertThat(xml.body()).contains("eilig").contains("Monitor");
    }

    @Test
    void collectionPropertiesAsResources() throws Exception {
        JsonNode tags = body("Auftraege(1)/tags?$orderby=" + enc("$it") + "&$top=1");
        assertThat(texts(tags.get("value"))).containsExactly("eilig");
        assertThat(tags.get("@odata.context").asString()).endsWith("Auftraege(auftrag_id=1)/tags");
        assertThat(texts(body("Auftraege(1)/tags?$filter=" + enc("$it ne 'neu'")).get("value"))).containsExactly("eilig");
        JsonNode pos = body("Auftraege(1)/positionen?$filter=" + enc("Betrag gt 100") + "&$select=Artikel");
        assertThat(pos.get("value").size()).isEqualTo(1);
        assertThat(pos.get("value").get(0).get("Artikel").asString()).isEqualTo("Monitor");
        assertThat(get("/odata/Auftraege(4)/positionen", "demo", "demo").statusCode()).isEqualTo(204);
    }

    @Test
    void jsonObjectsAsComplexValues() throws Exception {
        String md = get("/odata/$metadata", "demo", "demo").body();
        assertThat(md).contains("<Property Name=\"info\" Type=\"JdbcOdata.Auftraege_info\"")
                .contains("<ComplexType Name=\"Auftraege_info\">").contains("<Property Name=\"tagsText\" Type=\"Edm.String\"");
        JsonNode first = body("Auftraege(1)");
        assertThat(first.get("info").get("Ort").asString()).isEqualTo("Wien");
        assertThat(first.get("info").get("Plz").asInt()).isEqualTo(1010);
        assertThat(first.get("tagsText").asString()).isEqualTo("{neu,eilig}");
        assertThat(ids("Auftraege?$filter=" + enc("info/Land eq 'AT'") + "&$orderby=" + enc("info/Plz desc"))).containsExactly(2, 1);
        assertThat(ids("Auftraege?$filter=" + enc("info eq null"))).containsExactly(4);
        assertThat(ids("Auftraege?$filter=" + enc("info/Plz eq null and info ne null"))).containsExactly(3);
        JsonNode selected = body("Auftraege?$select=auftrag_id,info/Ort&$filter=" + enc("auftrag_id eq 2"));
        assertThat(selected.get("value").get(0).get("info").get("Ort").asString()).isEqualTo("Graz");
        assertThat(selected.get("value").get(0).get("info").has("Plz")).isFalse();
        assertThat(ids("Auftraege?$search=Berlin")).containsExactly(3);
        // as resources
        assertThat(body("Auftraege(1)/info").get("Ort").asString()).isEqualTo("Wien");
        assertThat(body("Auftraege(2)/info/Plz").get("value").asInt()).isEqualTo(8010);
        assertThat(get("/odata/Auftraege(3)/info/Plz", "demo", "demo").statusCode()).isEqualTo(204);
        assertThat(get("/odata/Auftraege(2)/info/Ort/$value", "demo", "demo").body()).isEqualTo("Graz");
        // $select options only on collections
        assertThat(get("/odata/Auftraege?$select=" + enc("info($top=1)"), "demo", "demo").statusCode()).isEqualTo(400);
    }

    @Test
    void countOfCollectionProperties() throws Exception {
        assertThat(get("/odata/Auftraege(1)/tags/$count", "demo", "demo").body()).isEqualTo("2");
        assertThat(get("/odata/Auftraege(1)/positionen/$count?$filter=" + enc("Betrag gt 100"), "demo", "demo").body()).isEqualTo("1");
        assertThat(get("/odata/Auftraege(1)/mengen/$count?$filter=" + enc("$it ge 2"), "demo", "demo").body()).isEqualTo("2");
        assertThat(get("/odata/Auftraege(4)/positionen/$count", "demo", "demo").body()).isEqualTo("0");
    }

    @Test
    void structureCanBeEditedAndSurvivesANewSnapshot() throws Exception {
        sql("CREATE TABLE merge_items (id INT PRIMARY KEY, data JSON)",
                "INSERT INTO merge_items VALUES (1, JSON '{\"b\":2,\"a\":\"x\"}')");
        String ds = null;
        for (JsonNode n : json.readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body())) {
            if ("Sales".equals(n.get("name").asString())) ds = n.get("datasourceId").asString();
        }
        UUID id = query("""
                {"name":"MergeItems","datasourceId":"%s","keyMode":"COLUMNS","sql":"SELECT id, data FROM merge_items ORDER BY id"}
                """.formatted(ds), """
                {"propertyName":"id","key":true,"visible":true},
                {"propertyName":"data","key":false,"visible":true,"collection":"OBJECT","textCopy":true,
                 "structure":[{"key":"a","name":"Name","edmType":"Edm.String","collection":false},
                              {"key":"b","name":"Wert","edmType":"Edm.Decimal","collection":false}]}
                """);
        JsonNode row = body("MergeItems(1)");
        assertThat(row.get("data").get("Name").asString()).isEqualTo("x");
        assertThat(row.get("data").get("Wert").decimalValue()).isEqualByComparingTo("2");
        assertThat(get("/odata/$metadata", "demo", "demo").body())
                .containsPattern("<ComplexType Name=\"MergeItems_data\"><Property Name=\"Name\" Type=\"Edm.String\"></Property><Property Name=\"Wert\" Type=\"Edm.Decimal\"");

        // a new field appears; a new snapshot keeps key, form, text copy and the edited fields and appends it
        sql("INSERT INTO merge_items VALUES (2, JSON '{\"a\":\"y\",\"c\":true}')");
        assertThat(send("POST", "/api/v1/queries/" + id + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        JsonNode q = json.readTree(get("/api/v1/queries/" + id, ADMIN, ADMIN_PASSWORD).body());
        JsonNode data = q.get("columns").get(1);
        assertThat(q.get("columns").get(0).get("key").asBoolean()).isTrue();
        assertThat(data.get("collection").asString()).isEqualTo("OBJECT");
        assertThat(data.get("textCopy").asBoolean()).isTrue();
        List<String> names = new ArrayList<>();
        for (JsonNode f : data.get("structure")) names.add(f.get("name").asString());
        assertThat(names).containsExactly("Name", "Wert", "c");
        assertThat(body("MergeItems(2)").get("data").get("c").asBoolean()).isTrue();
        assertThat(body("MergeItems(2)").get("dataText").asString()).contains("\"c\":true");

        // invalid structures
        assertThat(send("PUT", "/api/v1/queries/" + id + "/columns", """
                {"columns":[{"propertyName":"data","key":false,"visible":true,"structure":[{"key":"a","name":"1x","edmType":"Edm.String","collection":false}]}]}
                """, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/api/v1/queries/" + id + "/columns", """
                {"columns":[{"propertyName":"data","key":false,"visible":true,"structure":[{"key":"a","name":"A","edmType":"Edm.Nope","collection":false}]}]}
                """, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/api/v1/queries/" + id + "/columns", """
                {"columns":[{"propertyName":"data","key":false,"visible":true,"collection":"COMPLEX"}]}
                """, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);
        sql("DROP TABLE merge_items");
    }

    @Test
    void invalidSelectOptions() throws Exception {
        assertThat(get("/odata/Auftraege?$select=" + enc("kunde($top=1)"), "demo", "demo").statusCode()).isEqualTo(400);
        assertThat(get("/odata/Auftraege?$select=" + enc("tags($expand=x)"), "demo", "demo").statusCode()).isEqualTo(400);
        assertThat(get("/odata/Auftraege?$select=" + enc("tags($select=x)"), "demo", "demo").statusCode()).isEqualTo(400);
        assertThat(get("/odata/Auftraege?$select=" + enc("positionen($filter=Nope gt 1)"), "demo", "demo").statusCode()).isEqualTo(400);
        assertThat(get("/odata/Auftraege?$select=" + enc("tags($top=-1)"), "demo", "demo").statusCode()).isEqualTo(400);
        // a collection cannot be a key
        UUID id = created.get(0);
        HttpResponse<String> bad = send("PUT", "/api/v1/queries/" + id + "/columns",
                "{\"columns\":[{\"propertyName\":\"kunde\",\"key\":false,\"visible\":true,\"collection\":\"PRIMITIVE\"}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(bad.statusCode()).isEqualTo(400);
    }
}
