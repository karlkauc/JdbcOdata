package at.jdbcodata.datasource;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stops connection attempts to a datasource that cannot be reached. Each attempt would hold a thread and an execution
 * slot for the connect timeout, and a pass-through caller is checked against every datasource, so one dead database
 * would slow down all requests. After {@code failures} connection failures in a row within {@code window} the breaker
 * opens; after {@code open} one request tries again and its result decides. Only connection failures count: a wrong
 * password, a failed statement or a full pool say nothing about the database, and one user could lock out all others.
 * Per instance and per change of the datasource, so a corrected URL is tried at once.
 */
@Component
public class DatasourceCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(DatasourceCircuitBreaker.class);

    private static final class State {
        final Instant datasourceUpdatedAt;
        final Deque<Long> failures = new ArrayDeque<>();
        boolean open;
        boolean probing;
        long retryAt;

        State(Instant datasourceUpdatedAt) {
            this.datasourceUpdatedAt = datasourceUpdatedAt;
        }
    }

    private final int failures;
    private final long windowNanos;
    private final long openNanos;
    private final LongSupplier clock;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final LongAdder rejected = new LongAdder();

    @Autowired
    public DatasourceCircuitBreaker(@Value("${jdbcodata.odata.breaker-failures:5}") int failures,
                                    @Value("${jdbcodata.odata.breaker-window-sec:120}") int windowSec,
                                    @Value("${jdbcodata.odata.breaker-open-sec:30}") int openSec) {
        this(failures, windowSec, openSec, System::nanoTime);
    }

    DatasourceCircuitBreaker(int failures, int windowSec, int openSec, LongSupplier clock) {
        this.failures = failures;
        this.windowNanos = windowSec * 1_000_000_000L;
        this.openNanos = openSec * 1_000_000_000L;
        this.clock = clock;
    }

    /**
     * Before a connection attempt; every attempt that passes must end in {@link #record}.
     *
     * @throws DatasourceUnavailableException the breaker is open
     */
    public void check(Datasource ds) {
        if (failures <= 0) return;
        State s = state(ds);
        synchronized (s) {
            if (!s.open) return;
            if (s.probing || clock.getAsLong() - s.retryAt < 0) {
                rejected.increment();
                throw new DatasourceUnavailableException(ds.name());
            }
            s.probing = true;
        }
    }

    /** Result of a connection attempt; {@code failure} is {@code null} if it succeeded. */
    public void record(Datasource ds, Throwable failure) {
        if (failures <= 0) return;
        State s = state(ds);
        synchronized (s) {
            if (failure == null || DbErrorClassifier.isRejectedLogin(failure)) {
                // a rejected login is an answer of the database
                if (s.open) log.info("Datasource '{}' is reachable again", ds.name());
                s.open = false;
                s.probing = false;
                s.failures.clear();
            } else if (!DbErrorClassifier.isConnectionFailure(failure)) {
                s.probing = false;
            } else if (s.open) {
                s.probing = false;
                s.retryAt = clock.getAsLong() + openNanos;
            } else {
                long now = clock.getAsLong();
                s.failures.addLast(now);
                while (now - s.failures.peekFirst() > windowNanos) s.failures.removeFirst();
                if (s.failures.size() >= failures) {
                    log.warn("Datasource '{}' is not reachable: answering 503 for {} s before the next attempt", ds.name(),
                            openNanos / 1_000_000_000L);
                    s.open = true;
                    s.retryAt = now + openNanos;
                    s.failures.clear();
                }
            }
        }
    }

    public boolean isOpen(Datasource ds) {
        State s = states.get(ds.id());
        if (s == null || !Objects.equals(s.datasourceUpdatedAt, ds.updatedAt())) return false;
        synchronized (s) {
            return s.open;
        }
    }

    /**
     * Datasources this instance does not try to reach at the moment. Only a request can close the breaker, so one left
     * open by a deleted or unused datasource stops counting after the window instead of raising an alert for ever.
     */
    public long openCount() {
        long now = clock.getAsLong();
        return states.values().stream().filter(s -> {
            synchronized (s) {
                return s.open && now - s.retryAt < windowNanos;
            }
        }).count();
    }

    /** Requests answered without a connection attempt. */
    public long rejected() {
        return rejected.sum();
    }

    private State state(Datasource ds) {
        return states.compute(ds.id(), (id, old) -> old != null && Objects.equals(old.datasourceUpdatedAt, ds.updatedAt())
                ? old : new State(ds.updatedAt()));
    }
}
