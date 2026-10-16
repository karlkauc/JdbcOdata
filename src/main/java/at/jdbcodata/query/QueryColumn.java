package at.jdbcodata.query;

/** One column of the schema snapshot. {@code name} is the DB label, {@code propertyName} the OData identifier. */
public record QueryColumn(
        int ordinal,
        String name,
        String propertyName,
        int jdbcType,
        String jdbcTypeName,
        String edmType,
        Integer precision,
        Integer scale,
        boolean nullable,
        boolean key,
        boolean visible) {
}
