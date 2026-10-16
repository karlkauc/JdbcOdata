package at.jdbcodata.query;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.ExecutionIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Runs a (possibly unsaved) query with the admin's credentials and returns a preview (PRD FR-13). */
@Service
public class QueryTestService {

    public static final int PREVIEW_ROWS = 100;

    public record TestRunResult(List<QueryColumn> columns, List<Map<String, Object>> rows, boolean truncated,
                                long durationMs) {
    }

    private final QueryExecutor executor;
    private final DatasourceService datasourceService;

    public QueryTestService(QueryExecutor executor, DatasourceService datasourceService) {
        this.executor = executor;
        this.datasourceService = datasourceService;
    }

    public TestRunResult test(QueryDefinition query, ExecutionIdentity identity, Map<String, String> rawParams) {
        Datasource ds = datasourceService.require(query.datasourceId());
        Map<String, Object> params = ParameterBinder.resolve(query, rawParams == null ? Map.of() : rawParams);
        long start = System.nanoTime();
        ResultTable table = executor.preview(query, ds, identity, params, PREVIEW_ROWS,
                executor.effectiveTimeoutSec(query, ds));
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        List<QueryColumn> columns = SchemaSnapshotService.toColumns(query, table);
        List<Map<String, Object>> rows = new ArrayList<>(table.rows().size());
        for (Object[] row : table.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (QueryColumn c : columns) {
                Object v = row[c.ordinal()];
                m.put(c.propertyName(), v instanceof byte[] b ? "<" + b.length + " bytes>" : v);
            }
            rows.add(m);
        }
        return new TestRunResult(columns, rows, table.truncated(), durationMs);
    }
}
