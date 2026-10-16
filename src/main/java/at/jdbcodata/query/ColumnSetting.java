package at.jdbcodata.query;

/** Admin-editable flags of a snapshot column. */
public record ColumnSetting(String propertyName, boolean key, boolean visible) {
}
