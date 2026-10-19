package at.jdbcodata.query;

import java.util.List;

/**
 * One column of the schema snapshot. {@code name} is the DB label, {@code propertyName} the OData identifier.
 * {@code elementType} (SQL arrays, JSON arrays of values) and {@code structure} (JSON arrays of objects) are what the
 * snapshot found the column could be published as; {@code collection} is what the admin chose. {@code edmType} is
 * the type of the plain property ({@code Edm.String} for arrays and JSON). {@code singleObject}: the structure
 * describes JSON objects (not arrays of them); {@code textCopy}: a collection or complex column is additionally
 * published as text property {@code <Property>Text} (for clients like Excel that show structured values poorly).
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
        CollectionKind collection,
        boolean singleObject,
        boolean textCopy) {

    public QueryColumn {
        structure = structure == null ? List.of() : List.copyOf(structure);
        if (collection == null) collection = CollectionKind.NONE;
    }

    public QueryColumn(int ordinal, String name, String propertyName, int jdbcType, String jdbcTypeName, String edmType,
                       Integer precision, Integer scale, boolean nullable, boolean key, boolean visible) {
        this(ordinal, name, propertyName, jdbcType, jdbcTypeName, edmType, precision, scale, nullable, key, visible,
                null, List.of(), CollectionKind.NONE, false, false);
    }

    public QueryColumn(int ordinal, String name, String propertyName, int jdbcType, String jdbcTypeName, String edmType,
                       Integer precision, Integer scale, boolean nullable, boolean key, boolean visible,
                       String elementType, List<ComplexField> structure, CollectionKind collection) {
        this(ordinal, name, propertyName, jdbcType, jdbcTypeName, edmType, precision, scale, nullable, key, visible,
                elementType, structure, collection, false, false);
    }

    /** Published in a structured form: collection or complex value (not plain text). */
    public boolean collectionValued() {
        return collection != CollectionKind.NONE;
    }

    /** May be published as collection of primitive values / of a complex type. */
    public boolean canBePrimitiveCollection() {
        return elementType != null;
    }

    public boolean canBeComplexCollection() {
        return !structure.isEmpty() && !singleObject;
    }

    /** May be published as one complex value (the column holds JSON objects). */
    public boolean canBeComplexObject() {
        return !structure.isEmpty() && singleObject;
    }

    /** Can be anything else than plain text. */
    public boolean structured() {
        return canBePrimitiveCollection() || !structure.isEmpty();
    }

    /** Published as collection-valued property (not a single complex value). */
    public boolean collectionProperty() {
        return collection == CollectionKind.PRIMITIVE || collection == CollectionKind.COMPLEX;
    }

    /** SQL compares the column like its plain EDM type (not for collections and SQL arrays). */
    public boolean sqlComparable() {
        return collection == CollectionKind.NONE && jdbcType != java.sql.Types.ARRAY;
    }


    /** The structure as editable lines {@code Name Typ [JSON-Schlüssel]} (admin UI). */
    public String structureLines() {
        StringBuilder out = new StringBuilder();
        for (ComplexField f : structure) {
            out.append(f.name()).append(' ').append(f.collection() ? "Collection(" + f.edmType() + ")" : f.edmType());
            if (!f.key().equals(f.name())) out.append(' ').append(f.key());
            out.append('\n');
        }
        return out.toString();
    }
}
