package at.jdbcodata.query;

import java.util.List;

/**
 * Admin-editable flags of a snapshot column. {@code null} leaves a value unchanged: {@code collection} (published
 * form), {@code textCopy} (additional text property) and {@code structure} (fields of JSON objects: order, names,
 * types; the JSON keys must be ones the snapshot found or new ones).
 */
public record ColumnSetting(String propertyName, boolean key, boolean visible, CollectionKind collection,
                            Boolean textCopy, List<ComplexField> structure) {

    public ColumnSetting(String propertyName, boolean key, boolean visible) {
        this(propertyName, key, visible, null, null, null);
    }

    public ColumnSetting(String propertyName, boolean key, boolean visible, CollectionKind collection) {
        this(propertyName, key, visible, collection, null, null);
    }
}
