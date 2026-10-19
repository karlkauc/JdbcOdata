package at.jdbcodata.query;

/**
 * How a snapshot column is published: as a plain property ({@code NONE}, the default), as a collection of primitive
 * values ({@code PRIMITIVE}: SQL arrays, JSON arrays of values), as a collection of a complex type ({@code COMPLEX}:
 * JSON arrays of objects) or as one complex value ({@code OBJECT}: JSON objects). Collection-valued properties support
 * {@code any}/{@code all}, {@code /$count} and {@code $select} with options ({@code $select=Tags($top=2)}), complex
 * values member paths ({@code Adresse/Ort}).
 */
public enum CollectionKind {
    NONE, PRIMITIVE, COMPLEX, OBJECT;

    /** {@code null} and unknown names are {@code null} (no change). */
    public static CollectionKind parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return valueOf(text.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new at.jdbcodata.common.InvalidRequestException("Unknown collection kind '" + text + "' (NONE, PRIMITIVE, COMPLEX, OBJECT)");
        }
    }
}
