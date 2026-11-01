package at.jdbcodata.datasource;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits the rows being turned into responses at the same time, because a response is built completely in memory.
 * {@link ConcurrencyLimiter} does not cover this: it guards the database, and a cache hit never gets there, so any
 * number of clients could expand the same cached result at once. Counts rows, not requests, so that small feeds keep
 * running next to large ones. Held until the response is written, because the finished body stays on the heap.
 */
@Component
public class ResponseBudget {

    /** Results this small are not worth waiting for. */
    static final int FREE_ROWS = 1_000;
    /** Objects and JSON together. An estimate that held in the load test with five columns, see loadtest/. */
    private static final long BYTES_PER_ROW = 1_024;

    /** Gives its rows back once; closing twice is harmless. */
    public interface Reservation extends AutoCloseable {
        @Override
        void close();
    }

    private final int rows;
    private final long waitMs;
    private final Semaphore semaphore;

    public ResponseBudget(@Value("${jdbcodata.odata.response-rows-budget:0}") int rows,
                          @Value("${jdbcodata.odata.response-wait-ms:30000}") long waitMs) {
        this.rows = rows > 0 ? rows : forHeap(Runtime.getRuntime().maxMemory());
        this.waitMs = waitMs;
        this.semaphore = new Semaphore(this.rows, true);
    }

    /** Half of the heap; the rest is for the cache, the server and the results still coming from the databases. */
    static int forHeap(long maxHeapBytes) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(FREE_ROWS, maxHeapBytes / 2 / BYTES_PER_ROW));
    }

    /**
     * Waits for room for a result of {@code resultRows}. A result above the whole budget waits for all of it, so it
     * runs alone instead of never.
     *
     * @throws TooManyExecutionsException no room within the wait time
     */
    public Reservation reserve(int resultRows) {
        if (resultRows < FREE_ROWS) return () -> { };
        int permits = Math.min(resultRows, rows);
        try {
            if (!semaphore.tryAcquire(permits, waitMs, TimeUnit.MILLISECONDS)) {
                throw new TooManyExecutionsException("The service is busy with large responses. Try again shortly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TooManyExecutionsException("The service is busy with large responses. Try again shortly.");
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) semaphore.release(permits);
        };
    }

    public int reservedRows() {
        return rows - semaphore.availablePermits();
    }

    public int rows() {
        return rows;
    }
}
