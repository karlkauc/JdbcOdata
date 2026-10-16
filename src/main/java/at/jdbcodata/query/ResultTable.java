package at.jdbcodata.query;

import java.util.List;

/** Fully materialised query result. Values are already normalised to the Java types Olingo accepts. */
public record ResultTable(List<ResultColumn> columns, List<Object[]> rows, boolean truncated) {

    public int indexOf(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) return i;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnName)) return i;
        }
        return -1;
    }
}
