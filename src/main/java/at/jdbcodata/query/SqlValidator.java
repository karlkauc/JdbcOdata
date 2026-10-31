package at.jdbcodata.query;

import at.jdbcodata.common.InvalidRequestException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/** Accepts only parseable, read-only SELECT statements (PRD FR-12). */
public final class SqlValidator {

    private SqlValidator() {
    }

    public static void validateReadOnly(String sql) {
        String jdbcSql = NamedParameterSql.parse(sql).jdbcSql();
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(jdbcSql);
        } catch (JSQLParserException e) {
            throw new InvalidRequestException("SQL could not be parsed: " + firstLine(e));
        }
        if (!(statement instanceof Select select)) {
            throw new InvalidRequestException("Only SELECT statements are allowed, found "
                    + statement.getClass().getSimpleName().toUpperCase());
        }
        // MySQL/MariaDB run the content of /*! ... */ and MariaDB also /*M! ... */, although every parser treats it as a comment
        if (sql.contains("/*!") || sql.contains("/*M!") || sql.contains("/*m!")) {
            throw new InvalidRequestException("Executable comments (/*! ... */) are not allowed");
        }
        checkReadOnly(select);
    }

    /**
     * A {@link Select} can still write: data-modifying CTEs ({@code WITH x AS (DELETE ... RETURNING *)}),
     * {@code SELECT INTO} in any branch of a UNION, and row locks. The connection's read-only flag is not enforced
     * by every driver, so these are rejected here.
     */
    private static void checkReadOnly(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (!(item.getStatement() instanceof ParenthesedSelect cte)) {
                    throw new InvalidRequestException("Data-modifying WITH clauses are not allowed");
                }
                checkReadOnly(cte);
            }
        }
        if (select.getForMode() != null || select.getForUpdate() != null) {
            throw new InvalidRequestException("FOR UPDATE / FOR SHARE is not allowed");
        }
        switch (select) {
            case PlainSelect plain -> {
                if ((plain.getIntoTables() != null && !plain.getIntoTables().isEmpty())
                        || plain.getMySqlSelectIntoClause() != null || plain.getIntoTempTable() != null) {
                    throw new InvalidRequestException("SELECT INTO is not allowed");
                }
            }
            case SetOperationList set -> set.getSelects().forEach(SqlValidator::checkReadOnly);
            case ParenthesedSelect parenthesed -> checkReadOnly(parenthesed.getSelect());
            default -> {
            }
        }
    }

    private static String firstLine(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int nl = msg.indexOf('\n');
        msg = nl > 0 ? msg.substring(0, nl) : msg;
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
