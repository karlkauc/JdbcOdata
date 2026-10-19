package at.jdbcodata.query;

/**
 * One field of the elements of a JSON column published as collection of a complex type: {@code key} is the JSON
 * member name, {@code name} the OData property name, {@code edmType} the primitive type of the value (or of the
 * elements when {@code collection}).
 */
public record ComplexField(String key, String name, String edmType, boolean collection) {
}
