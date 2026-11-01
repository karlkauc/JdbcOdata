package at.jdbcodata.datasource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Limits parallel query executions per datasource (NFR-12, {@code Datasource.maxConcurrent}). */
@Component
public class ConcurrencyLimiter {

    private record Slot(int permits, Semaphore semaphore) {
    }

    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();

    /** Blocks up to {@code wait} for a free slot; the returned handle must be closed. */
    public Permit acquire(Datasource ds, Duration wait) throws TooManyExecutionsException {
        int permits = Math.max(1, ds.maxConcurrent());
        Slot slot = slots.compute(ds.id(), (id, old) -> old != null && old.permits() == permits ? old
                : new Slot(permits, new Semaphore(permits, true)));
        try {
            if (!slot.semaphore().tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TooManyExecutionsException(ds.name(), permits);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TooManyExecutionsException(ds.name(), permits);
        }
        return new Permit(slot.semaphore());
    }

    public int available(Datasource ds) {
        Slot slot = slots.get(ds.id());
        return slot == null ? Math.max(1, ds.maxConcurrent()) : slot.semaphore().availablePermits();
    }

    /** Slots in use across all datasources. */
    public int usedPermits() {
        return slots.values().stream().mapToInt(s -> s.permits() - s.semaphore().availablePermits()).sum();
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean released;

        Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}
