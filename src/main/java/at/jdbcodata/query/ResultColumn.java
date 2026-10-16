package at.jdbcodata.query;

public record ResultColumn(
        String name,
        int jdbcType,
        String jdbcTypeName,
        int precision,
        int scale,
        boolean nullable,
        TypeMapper.EdmMapping mapping) {
}
