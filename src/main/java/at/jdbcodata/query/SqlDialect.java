package at.jdbcodata.query;

import java.util.Locale;

/** SQL differences that matter for push-down: identifier quoting, paging syntax and a few functions. */
public enum SqlDialect {
    /** PostgreSQL, H2: {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}. */
    STANDARD,
    /** Oracle 12c and later: like STANDARD, but no time parts via EXTRACT on DATE and {@code LENGTH}. */
    ORACLE,
    /** MySQL, MariaDB: backtick quoting, {@code LIMIT ? OFFSET ?}; LIKE is not pushed (case-insensitive collations). */
    MYSQL;

    /** The dialect of a JDBC URL, or {@code null} when push-down is not supported for that database. */
    public static SqlDialect of(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:h2:")) return STANDARD;
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

    public boolean supportsLike() {
        return this != MYSQL;
    }

    public boolean supportsTimeExtract() {
        return this != ORACLE;
    }
}
