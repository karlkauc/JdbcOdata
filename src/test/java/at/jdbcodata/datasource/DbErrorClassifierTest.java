package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;

class DbErrorClassifierTest {

    @Test
    void detectsAuthenticationFailuresOfAllVendors() {
        assertThat(DbErrorClassifier.isAuthFailure(new SQLException("pg", "28P01"))).isTrue();
        assertThat(DbErrorClassifier.isAuthFailure(new SQLException("ora", "72000", 1017))).isTrue();
        assertThat(DbErrorClassifier.isAuthFailure(new SQLException("my", "HY000", 1045))).isTrue();
        assertThat(DbErrorClassifier.isAuthFailure(new SQLException("h2", "90001", 28000))).isTrue();
        assertThat(DbErrorClassifier.isAuthFailure(new SQLException("other", "42000", 1))).isFalse();
        SQLException chained = new SQLException("first", "42000");
        chained.setNextException(new SQLException("second", "28000"));
        assertThat(DbErrorClassifier.isAuthFailure(chained)).isTrue();
    }

    @Test
    void translatesIntoApplicationExceptions() {
        assertThat(DbErrorClassifier.translate(new SQLException("x", "28P01"), "ds"))
                .isInstanceOf(DbAuthenticationException.class).hasMessageContaining("'ds'");

        RuntimeException wrapped = new RuntimeException(new IllegalStateException(new SQLTimeoutException("slow")));
        RuntimeException t = DbErrorClassifier.translate(wrapped, "ds");
        assertThat(t).isInstanceOf(DbExecutionException.class);
        assertThat(((DbExecutionException) t).isTimeout()).isTrue();

        assertThat(((DbExecutionException) DbErrorClassifier.translate(new SQLException("c", "57014"), "ds")).isTimeout()).isTrue();
        assertThat(((DbExecutionException) DbErrorClassifier.translate(new SQLException("o", "x", 1013), "ds")).isTimeout()).isTrue();
        assertThat(((DbExecutionException) DbErrorClassifier.translate(new SQLException("s", "42000"), "ds")).isTimeout()).isFalse();

        RuntimeException generic = DbErrorClassifier.translate(new IllegalArgumentException("no sql"), "ds");
        assertThat(generic).isInstanceOf(DbExecutionException.class).hasMessageContaining("'ds'");
    }

    @Test
    void describesWithoutSqlText() {
        SQLException e = new SQLException("Column \"NOPE\" not found; SQL statement:\nSELECT nope FROM t [42122-232]", "42S22");
        assertThat(DbErrorClassifier.describe(e)).isEqualTo("Column \"NOPE\" not found [SQLState 42S22]");
        assertThat(DbErrorClassifier.describe(new SQLException((String) null))).isEqualTo("SQLException");
        assertThat(DbErrorClassifier.describe(new SQLException("x".repeat(400)))).hasSize(301);
    }
}
