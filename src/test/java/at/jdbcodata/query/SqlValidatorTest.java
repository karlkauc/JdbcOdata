package at.jdbcodata.query;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.InvalidRequestException;
import org.junit.jupiter.api.Test;

class SqlValidatorTest {

    @Test
    void acceptsSelectsWithParametersCtesAndUnions() {
        assertThatCode(() -> SqlValidator.validateReadOnly("SELECT a, b FROM t WHERE x = :p AND y::text = 'q'")).doesNotThrowAnyException();
        assertThatCode(() -> SqlValidator.validateReadOnly("WITH c AS (SELECT 1 AS n) SELECT n FROM c")).doesNotThrowAnyException();
        assertThatCode(() -> SqlValidator.validateReadOnly("SELECT 1 UNION ALL SELECT 2")).doesNotThrowAnyException();
        assertThatCode(() -> SqlValidator.validateReadOnly("(SELECT 1)")).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonSelectStatements() {
        assertThatThrownBy(() -> SqlValidator.validateReadOnly("DELETE FROM t"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Only SELECT");
        assertThatThrownBy(() -> SqlValidator.validateReadOnly("UPDATE t SET a = 1"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("UPDATE");
        assertThatThrownBy(() -> SqlValidator.validateReadOnly("SELECT * INTO t2 FROM t"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("SELECT INTO");
    }

    @Test
    void rejectsSelectsThatWrite() {
        String[] writing = {
                "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x",
                "WITH x AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM x",
                "WITH x AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM x",
                "WITH a AS (SELECT 1), b AS (DELETE FROM t RETURNING *) SELECT * FROM a",
                "SELECT 1 UNION (SELECT 2 INTO t)",
                "(SELECT 1 INTO t)",
                "SELECT 1 /*!50000 INTO OUTFILE '/tmp/x' */",
                "SELECT * FROM t FOR UPDATE",
                "SELECT * FROM t FOR SHARE"};
        for (String sql : writing) {
            assertThatThrownBy(() -> SqlValidator.validateReadOnly(sql)).as(sql).isInstanceOf(InvalidRequestException.class);
        }
        assertThatCode(() -> SqlValidator.validateReadOnly("WITH a AS (SELECT 1 AS n), b AS (SELECT n FROM a) "
                + "SELECT * FROM b UNION SELECT 2 /* plain comment */")).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnparseableSql() {
        assertThatThrownBy(() -> SqlValidator.validateReadOnly("SELEC a FROM"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("could not be parsed");
    }
}
