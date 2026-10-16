package at.jdbcodata.audit;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
