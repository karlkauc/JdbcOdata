package at.jdbcodata.seed;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DatasourceSpec;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.ColumnSetting;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QuerySpec;
import at.jdbcodata.query.SchemaSnapshotService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads datasources and queries from a YAML file at startup ({@code jdbcodata.seed.file}). Entries are upserted by
 * name. If a query has {@code snapshotCredentials} and no snapshot yet, the schema snapshot is taken immediately.
 * Placeholders like {@code ${jdbcodata.demo.jdbc-url}} are resolved against the Spring environment.
 */
@Component
@Order(10)
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final JdbcOdataProperties props;
    private final ResourceLoader resourceLoader;
    private final Environment environment;
    private final DatasourceService datasourceService;
    private final QueryService queryService;
    private final SchemaSnapshotService snapshotService;

    public SeedLoader(JdbcOdataProperties props, ResourceLoader resourceLoader, Environment environment,
                      DatasourceService datasourceService, QueryService queryService,
                      SchemaSnapshotService snapshotService) {
        this.props = props;
        this.resourceLoader = resourceLoader;
        this.environment = environment;
        this.datasourceService = datasourceService;
        this.queryService = queryService;
        this.snapshotService = snapshotService;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String file = props.seed().file();
        if (file == null || file.isBlank()) {
            return;
        }
        String location = file.contains(":") ? file : "file:" + file;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Seed file {} not found, skipping", file);
            return;
        }
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            root = new Yaml().load(in);
        }
        if (root == null) {
            return;
        }
        int datasources = 0;
        for (Map<String, Object> ds : list(root, "datasources")) {
            datasourceService.upsertByName(toDatasourceSpec(ds));
            datasources++;
        }
        int queries = 0;
        for (Map<String, Object> q : list(root, "queries")) {
            loadQuery(q);
            queries++;
        }
        log.info("Seed {} applied: {} datasource(s), {} query(ies)", file, datasources, queries);
    }

    private void loadQuery(Map<String, Object> q) {
        String dsName = str(q, "datasource");
        Datasource ds = datasourceService.findByName(dsName)
                .orElseThrow(() -> new IllegalStateException("Seed query '" + str(q, "name")
                        + "' references unknown datasource '" + dsName + "'"));
        List<QueryParameter> params = new ArrayList<>();
        for (Map<String, Object> p : list(q, "parameters")) {
            params.add(new QueryParameter(str(p, "name"), ParameterType.valueOf(str(p, "type", "STRING").toUpperCase()),
                    bool(p, "required", false), str(p, "default"), str(p, "description"), bool(p, "sensitive", false)));
        }
        // relations: [{name, target, columns: {local: target, …}, toMany, partner, description}]
        List<QueryRelation> relations = new ArrayList<>();
        for (Map<String, Object> r : list(q, "relations")) {
            Map<String, Object> columns = map(r, "columns");
            List<String> local = new ArrayList<>();
            List<String> target = new ArrayList<>();
            if (columns != null) {
                columns.forEach((k, v) -> {
                    local.add(k);
                    target.add(String.valueOf(v));
                });
            }
            relations.add(new QueryRelation(str(r, "name"), str(r, "target"), local, target, bool(r, "toMany", false),
                    str(r, "partner"), str(r, "description")));
        }
        QuerySpec spec = new QuerySpec(str(q, "name"), str(q, "description"), ds.id(), str(q, "sql"),
                ExecutionMode.valueOf(str(q, "executionMode", "CALLER").toUpperCase()), null,
                bool(q, "cacheEnabled", false), integer(q, "cacheTtlSec"), integer(q, "timeoutSec"),
                integer(q, "maxRows"), KeyMode.valueOf(str(q, "keyMode", "ROWID").toUpperCase()),
                bool(q, "enabled", true), params, q.containsKey("relations") ? relations : null,
                q.containsKey("pushDown") ? bool(q, "pushDown", false) : null);
        QueryDefinition saved = queryService.upsertByName(spec);

        Map<String, Object> creds = map(q, "snapshotCredentials");
        if (!saved.hasSnapshot() && creds != null) {
            try {
                String user = str(creds, "username");
                ExecutionIdentity identity = new ExecutionIdentity(user, user, str(creds, "password", ""),
                        ExecutionMode.CALLER);
                saved = snapshotService.snapshot(saved, identity, Map.of());
            } catch (RuntimeException e) {
                log.error("Schema snapshot for seed query '{}' failed: {}", saved.name(), e.getMessage());
            }
        } else if (!saved.hasSnapshot()) {
            log.warn("Seed query '{}' has no schema snapshot and no snapshotCredentials; run POST /api/v1/queries/{}/snapshot",
                    saved.name(), saved.id());
        }
        // keyColumns: [KundeId] marks snapshot columns as key (requires keyMode COLUMNS and a snapshot);
        // collectionColumns: [Tags, Positionen] publishes SQL arrays / JSON columns as collections (primitive when
        // the snapshot found an element type, else complex, or one complex value for JSON objects);
        // textCopyColumns: [Kategorien] additionally as text property KategorienText.
        List<String> keyColumns = strings(q, "keyColumns");
        List<String> collectionColumns = strings(q, "collectionColumns");
        List<String> textCopyColumns = strings(q, "textCopyColumns");
        if ((!keyColumns.isEmpty() || !collectionColumns.isEmpty() || !textCopyColumns.isEmpty()) && saved.hasSnapshot()) {
            List<ColumnSetting> settings = new ArrayList<>();
            for (QueryColumn c : saved.columns()) {
                boolean key = keyColumns.contains(c.propertyName());
                at.jdbcodata.query.CollectionKind collection = !collectionColumns.contains(c.propertyName()) ? null
                        : c.canBePrimitiveCollection() ? at.jdbcodata.query.CollectionKind.PRIMITIVE
                        : c.canBeComplexCollection() ? at.jdbcodata.query.CollectionKind.COMPLEX
                        : c.canBeComplexObject() ? at.jdbcodata.query.CollectionKind.OBJECT : null;
                if (collectionColumns.contains(c.propertyName()) && collection == null) {
                    log.warn("Seed query '{}': column '{}' is no SQL or JSON array, stays a plain property", saved.name(), c.propertyName());
                }
                settings.add(new ColumnSetting(c.propertyName(), key, c.visible() || key, collection,
                        textCopyColumns.contains(c.propertyName()) ? Boolean.TRUE : null, null));
            }
            queryService.updateColumnSettings(saved.id(), settings);
        }
    }

    private DatasourceSpec toDatasourceSpec(Map<String, Object> ds) {
        return new DatasourceSpec(str(ds, "name"), str(ds, "description"), str(ds, "jdbcUrl"), str(ds, "driverClass"),
                str(ds, "timezone"), integer(ds, "defaultTimeoutSec"), integer(ds, "maxRows"),
                integer(ds, "maxConcurrent"), bool(ds, "enabled", true));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (!(v instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }

    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
    }

    private String str(Map<String, Object> m, String key) {
        return str(m, key, null);
    }

    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) return def;
        return environment.resolvePlaceholders(String.valueOf(v));
    }

    private static Integer integer(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        return v instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(v));
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) return def;
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}
