package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.odata.AsyncRequests.Result;
import at.jdbcodata.testutil.DemoProfileTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AsyncRequestsTest extends DemoProfileTest {

    @Autowired
    AsyncRequests requests;
    @Autowired
    AsyncResultStore store;
    @Autowired
    JdbcTemplate jdbc;

    private static String caller() {
        return "async-" + UUID.randomUUID();
    }

    private AsyncRequests.Status finished(String id, String owner) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            AsyncRequests.Status status = requests.find(id, owner);
            if (status.result() != null) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("Request did not finish");
    }

    @Test
    void headersAndBodySurviveStorage() throws Exception {
        String owner = caller();
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("text/plain"), "Vary", List.of("A", "B"));

        String id = requests.submit(owner, () -> new Result(404, headers, "äöü".getBytes("UTF-8")));

        Result result = finished(id, owner).result();
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.headers()).isEqualTo(headers);
        assertThat(new String(result.body(), "UTF-8")).isEqualTo("äöü");
    }

    @Test
    void resultsAreEncryptedAtRest() throws Exception {
        String owner = caller();
        String id = requests.submit(owner, () -> Result.error(400, "secret"));
        finished(id, owner);

        String payload = jdbc.queryForObject("SELECT payload FROM async_request WHERE id = ?", String.class, id);

        assertThat(payload).startsWith("2:"); // the format of CredentialVault
        jdbc.update("UPDATE async_request SET id = ? WHERE id = ?", "moved" + id.substring(5), id);
        assertThatThrownBy(() -> requests.find("moved" + id.substring(5), owner)).isInstanceOf(RuntimeException.class); // bound to the record
        jdbc.update("DELETE FROM async_request WHERE id = ?", "moved" + id.substring(5));
    }

    @Test
    void onlyTheOwnerSeesOrCancelsARequest() throws Exception {
        String owner = caller();
        String id = requests.submit(owner, () -> Result.error(400, "x"));
        finished(id, owner);

        assertThat(requests.find(id, caller())).isNull();
        assertThat(requests.cancel(id, caller())).isFalse();
        assertThat(requests.cancel(id, owner)).isTrue();
        assertThat(requests.find(id, owner)).isNull();
    }

    @Test
    void aRequestWhoseInstanceStoppedIsReportedAsLost() {
        String owner = caller();
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        String id = UUID.randomUUID().toString().replace("-", "");
        store.start(id, owner, longAgo);

        assertThat(requests.find(id, owner).result().status()).isEqualTo(503);
        assertThat(store.running(owner, Instant.now().minus(AsyncRequests.HEARTBEAT_TIMEOUT))).isZero();
    }

    @Test
    void heartbeatsKeepARunningRequestAlive() throws Exception {
        String owner = caller();
        CountDownLatch release = new CountDownLatch(1);
        String id = requests.submit(owner, () -> {
            release.await();
            return Result.error(400, "x");
        });
        try {
            Instant before = store.find(id, owner).heartbeat();
            Thread.sleep(5);
            requests.sendHeartbeats();
            assertThat(store.find(id, owner).heartbeat()).isAfter(before);
            assertThat(requests.find(id, owner).result()).isNull();
        } finally {
            release.countDown();
        }
        finished(id, owner);
    }

    @Test
    void aCallerCannotRunMoreThanTheLimit() {
        String owner = caller();
        Instant now = Instant.now();
        for (int i = 0; i < AsyncRequests.MAX_RUNNING_PER_CALLER; i++) {
            store.start(UUID.randomUUID().toString().replace("-", ""), owner, now);
        }

        assertThat(requests.submit(owner, () -> Result.error(400, "x"))).isNull();
    }

    @Test
    void expiredResultsAreDeletedRunningRequestsStay() {
        String owner = caller();
        Instant old = Instant.now().minus(AsyncRequests.RESULT_LIFETIME).minusSeconds(60);
        String done = UUID.randomUUID().toString().replace("-", "");
        store.start(done, owner, old);
        store.finish(done, Result.error(400, "x"));
        String alive = UUID.randomUUID().toString().replace("-", "");
        store.start(alive, owner, old);
        store.heartbeat(List.of(alive), Instant.now());

        assertThat(requests.find(done, owner)).isNull();
        requests.deleteExpired();

        assertThat(store.find(done, owner)).isNull();
        assertThat(store.find(alive, owner)).isNotNull();
        store.delete(alive, owner);
    }
}
