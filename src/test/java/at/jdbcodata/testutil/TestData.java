package at.jdbcodata.testutil;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builders for domain records used across unit tests. */
public final class TestData {

    private TestData() {
    }

    public static QueryParameter param(String name, ParameterType type, boolean required, String def) {
        return new QueryParameter(name, type, required, def, null, false);
    }

    public static QueryColumn column(int ordinal, String name, String edmType, Integer precision, Integer scale,
                                     boolean key, boolean visible) {
        return new QueryColumn(ordinal, name, name, Types.OTHER, "x", edmType, precision, scale, true, key, visible);
    }

    public static QueryDefinition query(String name, String sql, List<QueryParameter> params, List<QueryColumn> columns,
                                        KeyMode keyMode) {
        return new QueryDefinition(UUID.randomUUID(), name, null, UUID.randomUUID(), "ds", sql, ExecutionMode.CALLER,
                null, null, false, 3600, null, null, keyMode, 1, true, params, columns, Instant.now(), Instant.now());
    }

    public static JdbcOdataProperties props(int maxRows, int defaultTimeoutSec, String seedFile) {
        return new JdbcOdataProperties(new JdbcOdataProperties.Admin("a", "b"),
                new JdbcOdataProperties.OData(maxRows, defaultTimeoutSec, 2000, "", 0), new JdbcOdataProperties.Seed(seedFile),
                new JdbcOdataProperties.Cache(16, 4, 3_600_000), new JdbcOdataProperties.Stats(90, false),
                new JdbcOdataProperties.Security(true, 20), "", "./data");
    }

    public static Datasource datasource(String name, String jdbcUrl) {
        return new Datasource(UUID.randomUUID(), name, null, jdbcUrl, null, null, 30, 1000, 10, true,
                Instant.now(), Instant.now());
    }
}
