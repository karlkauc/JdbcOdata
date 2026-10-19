package at.jdbcodata.query;

import java.util.List;

/**
 * One column of the schema snapshot. {@code name} is the DB label, {@code propertyName} the OData identifier.
 * {@code elementType} (SQL arrays, JSON arrays of values) and {@code structure} (JSON arrays of objects) are what the
 * snapshot found the column could be published as; {@code collection} is what the admin chose. {@code edmType} is
 * the type of the plain property ({@code Edm.String} for arrays and JSON).
 */
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
        boolean visible,
        String elementType,
        List<ComplexField> structure,
        CollectionKind collection) {

    public QueryColumn {
        structure = structure == null ? List.of() : List.copyOf(structure);
        if (collection == null) collection = CollectionKind.NONE;
    }

    public QueryColumn(int ordinal, String name, String propertyName, int jdbcType, String jdbcTypeName, String edmType,
                       Integer precision, Integer scale, boolean nullable, boolean key, boolean visible) {
        this(ordinal, name, propertyName, jdbcType, jdbcTypeName, edmType, precision, scale, nullable, key, visible,
                null, List.of(), CollectionKind.NONE);
    }

    /** Published as collection-valued property. */
    public boolean collectionValued() {
        return collection != CollectionKind.NONE;
    }

    /** May be published as collection of primitive values / of a complex type. */
    public boolean canBePrimitiveCollection() {
        return elementType != null;
    }

    public boolean canBeComplexCollection() {
        return !structure.isEmpty();
    }

    /** SQL compares the column like its plain EDM type (not for collections and SQL arrays). */
    public boolean sqlComparable() {
        return collection == CollectionKind.NONE && jdbcType != java.sql.Types.ARRAY;
    }

}
