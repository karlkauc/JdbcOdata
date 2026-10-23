package at.jdbcodata.datasource;

import static at.jdbcodata.testutil.TestData.datasource;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionSettingsTest {

    private static final String ORACLE = "jdbc:oracle:thin:@//db:1521/ORCL";
    private static final String POSTGRES = "jdbc:postgresql://db/x";
    private static final String MARIADB = "jdbc:mariadb://db/x";

    @Test
    void canonicalisesKeysPerDialect() {
        assertThat(SessionSettings.validate(ORACLE, Map.of("nls_sort", " GERMAN ")))
                .containsExactly(Map.entry("NLS_SORT", "GERMAN"));
        assertThat(SessionSettings.validate(POSTGRES, Map.of("datestyle", "ISO, DMY")))
                .containsExactly(Map.entry("DateStyle", "ISO, DMY"));
        assertThat(SessionSettings.validate("jdbc:h2:mem:x", Map.of("time_zone", "+05:00")))
                .containsExactly(Map.entry("TIME_ZONE", "+05:00"));
        assertThat(SessionSettings.validate(ORACLE, null)).isEmpty();
    }

    @Test
    void rejectsUnknownKeysAndUnsupportedDatabases() {
        assertThatThrownBy(() -> SessionSettings.validate(ORACLE, Map.of("NLS_COMP", "LINGUISTIC")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("NLS_COMP");
        assertThatThrownBy(() -> SessionSettings.validate(POSTGRES, Map.of("NLS_SORT", "GERMAN")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Allowed: DateStyle");
        assertThatThrownBy(() -> SessionSettings.validate(POSTGRES, Map.of("DateStyle", "German, DMY")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("ISO");
        assertThatThrownBy(() -> SessionSettings.validate("jdbc:sqlserver://db", Map.of("x", "y")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not supported");
        Map<String, String> twice = new LinkedHashMap<>();
        twice.put("NLS_SORT", "GERMAN");
        twice.put("nls_sort", "BINARY");
        assertThatThrownBy(() -> SessionSettings.validate(ORACLE, twice))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("twice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GERMAN'; DROP TABLE x --", "a;b", "a\\b", "", "x'", "line\nbreak"})
    void rejectsValuesThatCouldLeaveTheLiteral(String value) {
        assertThatThrownBy(() -> SessionSettings.validate(ORACLE, Map.of("NLS_SORT", value)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void generatesOneStatementPerDialect() {
        assertThat(SessionSettings.initSql(ORACLE, Map.of("NLS_SORT", "GERMAN", "NLS_DATE_FORMAT", "DD.MM.YYYY HH24:MI:SS")))
                .isEqualTo("ALTER SESSION SET NLS_DATE_FORMAT='DD.MM.YYYY HH24:MI:SS' NLS_SORT='GERMAN'");
        assertThat(SessionSettings.initSql(POSTGRES, Map.of("DateStyle", "ISO, DMY", "TimeZone", "Europe/Vienna")))
                .isEqualTo("SELECT set_config('DateStyle', 'ISO, DMY', false), set_config('TimeZone', 'Europe/Vienna', false)");
        assertThat(SessionSettings.initSql(MARIADB, Map.of("lc_time_names", "de_DE", "div_precision_increment", "6")))
                .isEqualTo("SET SESSION div_precision_increment=6, lc_time_names='de_DE'");
        assertThat(SessionSettings.initSql("jdbc:h2:tcp://db/x", Map.of("TIME_ZONE", "+05:00")))
                .isEqualTo("SET TIME ZONE '+05:00'");
        assertThat(SessionSettings.initSql(ORACLE, Map.of())).isNull();
    }

    @Test
    void queryOverridesDatasourceDefaults() {
        Datasource base = datasource("ds", ORACLE);
        Datasource ds = new Datasource(base.id(), "ds", null, ORACLE, null, null, 30, 1000, 10, true, Instant.now(),
                Instant.now(), Map.of("NLS_SORT", "BINARY", "NLS_LANGUAGE", "GERMAN"), Map.of());
        QueryDefinition q = query("Q", "SELECT 1 FROM dual", List.of(), List.of(), KeyMode.ROWID);
        QueryDefinition withOverride = new QueryDefinition(UUID.randomUUID(), "Q", null, ds.id(), "ds", q.sqlText(),
                q.executionMode(), null, null, false, 3600, null, null, KeyMode.ROWID, 1, true, List.of(), List.of(),
                null, null, List.of(), false, Map.of("NLS_SORT", "GERMAN"));
        assertThat(SessionSettings.effective(ds, q)).containsExactly(
                Map.entry("NLS_LANGUAGE", "GERMAN"), Map.entry("NLS_SORT", "BINARY"));
        assertThat(SessionSettings.effective(ds, withOverride)).containsExactly(
                Map.entry("NLS_LANGUAGE", "GERMAN"), Map.entry("NLS_SORT", "GERMAN"));
    }

    @Test
    void parsesAndFormatsLines() {
        Map<String, String> parsed = SessionSettings.fromLines("# comment\nNLS_SORT = GERMAN\n\nNLS_DATE_FORMAT=DD.MM.YYYY\n");
        assertThat(parsed).containsExactly(Map.entry("NLS_SORT", "GERMAN"), Map.entry("NLS_DATE_FORMAT", "DD.MM.YYYY"));
        assertThat(SessionSettings.toLines(parsed)).isEqualTo("NLS_SORT=GERMAN\nNLS_DATE_FORMAT=DD.MM.YYYY");
        assertThatThrownBy(() -> SessionSettings.fromLines("NLS_SORT"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("KEY=VALUE");
    }
}
