package at.jdbcodata.query;

import at.jdbcodata.common.InvalidRequestException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

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
        if (select instanceof PlainSelect plain && plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
            throw new InvalidRequestException("SELECT INTO is not allowed");
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
