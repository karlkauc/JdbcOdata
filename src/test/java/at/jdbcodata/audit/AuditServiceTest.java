package at.jdbcodata.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import at.jdbcodata.testutil.DemoProfileTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class AuditServiceTest extends DemoProfileTest {

    @Autowired
    AuditService audit;

    @Test
    void recordsAndListsEntries() {
        UUID id = UUID.randomUUID();
        audit.record("TEST", "THING", id, "thing-1", Map.of("k", "v"));
        audit.record("TEST", "THING", id, "thing-1", null);
        var entries = audit.forEntity("THING", id.toString(), 10);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).details()).contains("\"k\":\"v\"");
        assertThat(entries.get(0).details()).isNull();
        assertThat(entries.get(0).adminUser()).isEqualTo("system");
        assertThat(audit.latest(5)).hasSizeLessThanOrEqualTo(5).isNotEmpty();
        assertThat(audit.latest(0)).hasSize(1);
    }

    @Test
    void retentionKeepsEverythingByDefault() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        new AuditService(repository, 0).applyRetention();
        verify(repository, never()).deleteOlderThan(any());
    }

    @Test
    void retentionDeletesOldRecordsAndLeavesATrace() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.deleteOlderThan(any())).thenReturn(3);
        new AuditService(repository, 30).applyRetention();

        ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteOlderThan(before.capture());
        assertThat(Duration.between(before.getValue(), Instant.now()).toDays()).isEqualTo(30);
        ArgumentCaptor<AuditLogEntity> trace = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).save(trace.capture());
        assertThat(trace.getValue().toRecord().action()).isEqualTo("RETENTION");
        assertThat(trace.getValue().toRecord().details()).contains("\"deleted\":3");
    }

    @Test
    void testRunsAreRecordedWithSqlButWithoutCredentials() throws Exception {
        var queries = JsonMapper.builder().build().readTree(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body());
        String id = queries.get(0).get("id").asString();

        var response = send("POST", "/api/v1/queries/" + id + "/test",
                "{\"username\":\"demo\",\"password\":\"demo\",\"parameters\":{}}", ADMIN, ADMIN_PASSWORD);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var entry = audit.forEntity("QUERY", id, 20).stream().filter(e -> e.action().equals("TEST_RUN")).findFirst().orElseThrow();
        assertThat(entry.adminUser()).isEqualTo(ADMIN);
        assertThat(entry.details()).contains("\"sql\"").contains("\"dbUser\":\"demo\"").doesNotContain("password");
    }
}
