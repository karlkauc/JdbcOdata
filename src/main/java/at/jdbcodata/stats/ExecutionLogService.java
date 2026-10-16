package at.jdbcodata.stats;

import at.jdbcodata.config.JdbcOdataProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes execution records off the request thread and enforces the retention period. */
@Service
public class ExecutionLogService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogService.class);

    private final ExecutionLogRepository repository;
    private final TransactionTemplate tx;
    private final JdbcOdataProperties.Stats config;
    private final ExecutorService writer;
    private final AtomicLong dropped = new AtomicLong();

    public ExecutionLogService(ExecutionLogRepository repository, TransactionTemplate tx, JdbcOdataProperties props) {
        this.repository = repository;
        this.tx = tx;
        this.config = props.stats();
        this.writer = config.async() ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "execution-log-writer");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    public void record(ExecutionRecord record) {
        if (writer == null) {
            write(record);
            return;
        }
        try {
            writer.submit(() -> write(record));
        } catch (RejectedExecutionException e) {
            dropped.incrementAndGet();
        }
    }

    private void write(ExecutionRecord record) {
        try {
            tx.executeWithoutResult(status -> repository.save(ExecutionLogEntity.of(record)));
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
            log.warn("Could not write execution log record for query {}: {}", record.queryName(), e.getMessage());
        }
    }

    public long droppedRecords() {
        return dropped.get();
    }

    /** Daily retention job; the first run happens one minute after start. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void applyRetention() {
        int n = deleteOlderThan(Duration.ofDays(config.retentionDays()));
        if (n > 0) log.info("Execution log retention: deleted {} records older than {} days", n, config.retentionDays());
    }

    public int deleteOlderThan(Duration age) {
        Instant before = Instant.now().minus(age);
        Integer n = tx.execute(status -> repository.deleteOlderThan(before));
        return n == null ? 0 : n;
    }

    @PreDestroy
    public void shutdown() {
        if (writer != null) {
            writer.shutdown();
        }
    }
}
