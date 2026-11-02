package at.jdbcodata.odata;

import at.jdbcodata.common.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asynchronous requests ({@code Prefer: respond-async}): the request runs in the background, the client polls the
 * status monitor and finally receives the response there. State and results are in the metadata DB, so any instance
 * can answer the monitor. They are kept for {@link #RESULT_LIFETIME}; at most {@link #MAX_JOBS} are kept and
 * {@link #MAX_RUNNING_PER_CALLER} may run per caller (more are answered synchronously).
 * A request whose instance has stopped sending heartbeats is reported as lost instead of running forever.
 */
@Component
public class AsyncRequests {

    private static final Logger log = LoggerFactory.getLogger(AsyncRequests.class);

    static final int MAX_JOBS = 200;
    static final int MAX_RUNNING_PER_CALLER = 4;
    static final Duration RESULT_LIFETIME = Duration.ofHours(1);
    static final Duration HEARTBEAT_TIMEOUT = Duration.ofMinutes(1);

    /** The finished response: status line, headers and body. */
    record Result(int status, Map<String, List<String>> headers, byte[] body) {

        static Result error(int status, String message) {
            String body = "{\"error\":{\"code\":\"" + status + "\",\"message\":\"" + String.valueOf(message).replace("\"", "'") + "\"}}";
            return new Result(status, Map.of("Content-Type", List.of("application/json")), body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** {@code result} is null while the request runs. */
    record Status(Result result) {
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "odata-async-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger THREADS = new AtomicInteger();
    /** The requests running on this instance. */
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private final AsyncResultStore store;
    private final Clock clock;

    @Autowired
    public AsyncRequests(AsyncResultStore store) {
        this(store, Clock.systemUTC());
    }

    AsyncRequests(AsyncResultStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Starts {@code work} for {@code owner}; null when the caller already has too many running jobs. */
    String submit(String owner, Callable<Result> work) {
        synchronized (running) {
            Instant now = clock.instant();
            if (store.running(owner, now.minus(HEARTBEAT_TIMEOUT)) >= MAX_RUNNING_PER_CALLER) return null;
            String id = UUID.randomUUID().toString().replace("-", "");
            store.start(id, owner, now);
            Callable<Result> job = withRequestId(work);
            FutureTask<Void> task = new FutureTask<>(() -> run(id, job), null);
            running.put(id, task);
            pool.execute(task);
            store.trim(MAX_JOBS);
            return id;
        }
    }

    private void run(String id, Callable<Result> work) {
        try {
            store.finish(id, resultOf(id, work));
        } catch (DataAccessException e) {
            log.error("Cannot store the result of asynchronous OData request {}: {}", id, e.getMessage());
        } finally {
            running.remove(id);
        }
    }

    private Result resultOf(String id, Callable<Result> work) {
        try {
            return work.call();
        } catch (Exception e) {
            if (running.containsKey(id)) log.error("Asynchronous OData request {} failed", id, e); // otherwise cancelled
            return Result.error(500, "Internal server error");
        }
    }

    /** Log lines of the job keep the ID of the request that started it. */
    private static Callable<Result> withRequestId(Callable<Result> work) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null) return work;
        return () -> {
            try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestIdFilter.MDC_KEY, requestId)) {
                return work.call();
            }
        };
    }

    /** The state of job {@code id} of {@code owner}, or null. */
    Status find(String id, String owner) {
        AsyncResultStore.Stored stored = store.find(id, owner);
        if (stored == null || expired(stored)) return null;
        if (stored.result() == null && stored.heartbeat().isBefore(clock.instant().minus(HEARTBEAT_TIMEOUT))) {
            return new Status(Result.error(503, "The instance running this request stopped. Send the request again."));
        }
        return new Status(stored.result());
    }

    private boolean expired(AsyncResultStore.Stored stored) {
        return stored.result() != null && stored.created().isBefore(clock.instant().minus(RESULT_LIFETIME));
    }

    /** Cancels and forgets the job; false when it is unknown. On another instance, it only loses its result. */
    boolean cancel(String id, String owner) {
        if (!store.delete(id, owner)) return false;
        Future<?> task = running.remove(id);
        if (task != null) task.cancel(true);
        return true;
    }

    @Scheduled(fixedDelay = 10_000)
    void sendHeartbeats() {
        if (running.isEmpty()) return;
        try {
            store.heartbeat(List.copyOf(running.keySet()), clock.instant());
        } catch (DataAccessException e) {
            log.warn("Cannot refresh asynchronous requests in the metadata DB: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    void deleteExpired() {
        try {
            Instant now = clock.instant();
            store.deleteExpired(now.minus(RESULT_LIFETIME), now.minus(HEARTBEAT_TIMEOUT));
        } catch (DataAccessException e) {
            log.warn("Cannot delete expired asynchronous requests from the metadata DB: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
