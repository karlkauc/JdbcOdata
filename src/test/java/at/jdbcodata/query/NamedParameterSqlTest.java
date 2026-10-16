package at.jdbcodata.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NamedParameterSqlTest {

    @Test
    void replacesNamedParametersWithMarkers() {
        var parsed = NamedParameterSql.parse("SELECT * FROM t WHERE a = :p_a AND b = :p_b AND c = :p_a");
        assertThat(parsed.jdbcSql()).isEqualTo("SELECT * FROM t WHERE a = ? AND b = ? AND c = ?");
        assertThat(parsed.parameterNames()).containsExactly("p_a", "p_b", "p_a");
        assertThat(parsed.distinctNames()).containsExactly("p_a", "p_b");
    }

    @Test
    void ignoresStringLiteralsCommentsAndCasts() {
        String sql = """
                SELECT 'time: 12:30' AS lit, x::text, "weird:name" -- comment :not_a_param
                FROM t /* :also_not */ WHERE y = :real
                """;
        var parsed = NamedParameterSql.parse(sql);
        assertThat(parsed.parameterNames()).containsExactly("real");
        assertThat(parsed.jdbcSql()).contains("'time: 12:30'").contains("x::text").contains("\"weird:name\"")
                .contains("-- comment :not_a_param").contains("/* :also_not */").contains("y = ?");
    }

    @Test
    void handlesEscapedQuotes() {
        var parsed = NamedParameterSql.parse("SELECT 'it''s :x' WHERE a = :a");
        assertThat(parsed.jdbcSql()).isEqualTo("SELECT 'it''s :x' WHERE a = ?");
        assertThat(parsed.parameterNames()).containsExactly("a");
    }

    @Test
    void leavesSqlWithoutParametersUntouched() {
        String sql = "SELECT 1";
        assertThat(NamedParameterSql.parse(sql).jdbcSql()).isEqualTo(sql);
    }
}
