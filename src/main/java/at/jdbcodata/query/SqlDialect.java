package at.jdbcodata.query;

import java.util.Locale;

/** SQL differences that matter for push-down: identifier quoting, paging syntax and a few functions. */
public enum SqlDialect {
    /** H2: {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}; strings compare like Java ({@code String.compareTo}). */
    STANDARD,
    /** PostgreSQL: like STANDARD; binary string order via {@code COLLATE "C"}. */
    POSTGRES,
    /** Oracle 12c and later: like STANDARD, but no time parts via EXTRACT on DATE and {@code LENGTH}. */
    ORACLE,
    /** MySQL, MariaDB: backtick quoting, {@code LIMIT ? OFFSET ?}; strings compare as utf8mb4 bytes. */
    MYSQL;

    /** The dialect of a JDBC URL, or {@code null} when push-down is not supported for that database. */
    public static SqlDialect of(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:")) return POSTGRES;
        if (url.startsWith("jdbc:h2:")) return STANDARD;
        if (url.startsWith("jdbc:oracle:")) return ORACLE;
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) return MYSQL;
        return null;
    }

    public String quote(String identifier) {
        if (this == MYSQL) return "`" + identifier.replace("`", "``") + "`";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public String lengthFunction() {
        return this == ORACLE ? "LENGTH" : "CHAR_LENGTH";
    }

    /** LIKE over {@link #binary} text is case- and accent-sensitive everywhere, like the in-memory evaluation. */
    public boolean supportsLike() {
        return true;
    }

    /**
     * A string expression in binary (code point) order and equality, as Java compares strings: the database
     * collation would otherwise order umlauts, case and blanks differently, and MySQL's default collations even
     * compare case-insensitively. PostgreSQL takes an explicit collation on one side (bind placeholders stay as they
     * are), Oracle compares NLSSORT keys; H2 already compares like Java. MySQL and MariaDB compare the utf8mb4 bytes,
     * because utf8mb4_bin ignores trailing blanks and the collations without that padding differ between the two.
     */
    public String binary(String expression) {
        return switch (this) {
            case POSTGRES -> "?".equals(expression) ? expression : "(" + expression + ") COLLATE \"C\"";
            case MYSQL -> "?".equals(expression) ? expression : "CAST(CONVERT(" + expression + " USING utf8mb4) AS BINARY)";
            case ORACLE -> "NLSSORT(" + expression + ", 'NLS_SORT=BINARY')";
            case STANDARD -> expression;
        };
    }

    /** A {@link #binary} expression as a selected value: MySQL's binary strings would arrive as bytes. */
    public String text(String binaryExpression) {
        return this == MYSQL ? "CONVERT(" + binaryExpression + " USING utf8mb4)" : binaryExpression;
    }

    /** Can MIN/MAX run over {@link #binary} strings and return the string itself? */
    public boolean supportsBinaryMinMax() {
        return this != ORACLE;
    }

    /** {@code ROW_NUMBER()} over the rows in the order the database returns them. */
    public String rowNumber() {
        return this == ORACLE ? "ROW_NUMBER() OVER (ORDER BY NULL)" : "ROW_NUMBER() OVER ()";
    }

    public boolean supportsTimeExtract() {
        return this != ORACLE;
    }
}
