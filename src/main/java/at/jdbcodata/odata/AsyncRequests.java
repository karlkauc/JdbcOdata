package at.jdbcodata.odata;

import at.jdbcodata.common.RequestIdFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Asynchronous requests ({@code Prefer: respond-async}): the request runs in the background, the client polls the
 * status monitor and finally receives the response there. Jobs live in memory for {@link #RESULT_LIFETIME} after
 * they finished; at most {@link #MAX_JOBS} are kept and {@link #MAX_RUNNING_PER_CALLER} may run per caller (more are
 * answered synchronously).
 */
@Component
public class AsyncRequests {

    static final int MAX_JOBS = 200;
    static final int MAX_RUNNING_PER_CALLER = 4;
    static final Duration RESULT_LIFETIME = Duration.ofHours(1);

    /** The finished response: status line, headers and body. */
    record Result(int status, Map<String, List<String>> headers, byte[] body) {
    }

    record Job(String owner, Future<Result> future, Instant created) {
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "odata-async-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger THREADS = new AtomicInteger();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Clock clock;

    public AsyncRequests() {
        this(Clock.systemUTC());
    }

    AsyncRequests(Clock clock) {
        this.clock = clock;
    }

    /** Starts {@code work} for {@code owner}; null when the caller already has too many running jobs. */
    String submit(String owner, Callable<Result> work) {
        synchronized (jobs) {
            expire();
            long running = jobs.values().stream().filter(j -> j.owner().equals(owner) && !j.future().isDone()).count();
            if (running >= MAX_RUNNING_PER_CALLER) return null;
            String id = UUID.randomUUID().toString().replace("-", "");
            jobs.put(id, new Job(owner, pool.submit(withRequestId(work)), clock.instant()));
            while (jobs.size() > MAX_JOBS) {
                Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator();
                Job oldest = it.next().getValue();
                oldest.future().cancel(true);
                it.remove();
            }
            return id;
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

    /** The job {@code id} of {@code owner}, or null. */
    Job find(String id, String owner) {
        synchronized (jobs) {
            expire();
            Job j = jobs.get(id);
            return j != null && j.owner().equals(owner) ? j : null;
        }
    }

    /** Cancels and forgets the job; false when it is unknown. */
    boolean cancel(String id, String owner) {
        synchronized (jobs) {
            Job j = jobs.get(id);
            if (j == null || !j.owner().equals(owner)) return false;
            j.future().cancel(true);
            jobs.remove(id);
            return true;
        }
    }

    private void expire() {
        Instant oldest = clock.instant().minus(RESULT_LIFETIME);
        jobs.values().removeIf(j -> j.future().isDone() && j.created().isBefore(oldest));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
