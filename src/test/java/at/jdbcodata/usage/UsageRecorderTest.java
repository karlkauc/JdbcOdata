package at.jdbcodata.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UsageRecorderTest {

    private static final UsageEvent EVENT = new UsageEvent("metadata", "h", "curl", "unknown", 200, "1", "script", null,
            1, "ok", null, false);

    @Test
    void aFullQueueDropsEventsWithoutWaiting() {
        UsageRecorder recorder = new UsageRecorder(new UsageProperties("", "", "", "")); // not started, nothing drains
        for (int i = 0; i < UsageRecorder.QUEUE_SIZE; i++) assertThat(recorder.record(EVENT)).isTrue();
        assertThat(recorder.record(EVENT)).isFalse();
    }

    @Test
    void anUnreachableDatabaseOnlyLosesTheEvents() throws Exception {
        UsageRecorder recorder = new UsageRecorder(
                new UsageProperties("jdbc:postgresql://127.0.0.1:1/none", "u", "p", "s"));
        recorder.start();
        assertThat(recorder.record(EVENT)).isTrue();
        long start = System.nanoTime();
        recorder.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aMissingHashSecretStopsTheStart() {
        assertThatThrownBy(() -> new UsageConfig().usageRecorder(new UsageProperties("jdbc:postgresql://db/x", "", "", " ")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hash-secret");
    }
}
