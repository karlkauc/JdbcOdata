package at.jdbcodata.query;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.ExecutionIdentity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a query once for a few sample rows and persists the column metadata (and what SQL array and JSON columns
 * could be published as). {@code $metadata} is served from
 * this snapshot without touching the database.
 */
@Service
public class SchemaSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotService.class);

    private final QueryExecutor executor;
    private final QueryService queryService;
    private final DatasourceService datasourceService;

    public SchemaSnapshotService(QueryExecutor executor, QueryService queryService, DatasourceService datasourceService) {
        this.executor = executor;
        this.queryService = queryService;
        this.datasourceService = datasourceService;
    }

    public QueryDefinition snapshot(QueryDefinition query, ExecutionIdentity identity, Map<String, String> rawParams) {
        Datasource ds = datasourceService.require(query.datasourceId());
        Map<String, Object> params = ParameterBinder.resolve(query, rawParams);
        ResultTable probe = executor.probe(query, ds, identity, params, executor.effectiveTimeoutSec(query, ds));
        List<QueryColumn> columns = toColumns(query, probe);
        log.info("Schema snapshot for query '{}': {} columns", query.name(), columns.size());
        return queryService.replaceSnapshot(query.id(), columns);
    }

    static List<QueryColumn> toColumns(QueryDefinition query, ResultTable probe) {
        Set<String> used = new HashSet<>();
        if (query.keyMode() == KeyMode.ROWID) used.add("RowId");
        List<QueryColumn> columns = new ArrayList<>();
        int ordinal = 0;
        for (ResultColumn rc : probe.columns()) {
            TypeMapper.EdmMapping m = rc.mapping();
            String propertyName = TypeMapper.toPropertyName(rc.name(), ordinal, used);
            boolean visible = m.kind() != EdmPrimitiveTypeKind.Binary;
            List<Object> samples = new ArrayList<>();
            int index = ordinal;
            for (Object[] row : probe.rows()) samples.add(row[index]);
            CollectionShape.Detected detected = CollectionShape.detect(rc, samples);
            columns.add(new QueryColumn(ordinal++, rc.name(), propertyName, rc.jdbcType(), rc.jdbcTypeName(),
                    m.fqn(), m.precision(), m.scale(), true, false, visible, detected.elementType(), detected.structure(),
                    CollectionKind.NONE));
        }
        return columns;
    }
}
