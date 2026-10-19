package at.jdbcodata.query;

/** Admin-editable flags of a snapshot column; {@code collection == null} leaves the published form unchanged. */
public record ColumnSetting(String propertyName, boolean key, boolean visible, CollectionKind collection) {

    public ColumnSetting(String propertyName, boolean key, boolean visible) {
        this(propertyName, key, visible, null);
    }
}
