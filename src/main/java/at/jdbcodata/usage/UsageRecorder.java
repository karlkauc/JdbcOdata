package at.jdbcodata.usage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes usage events in batches on its own thread. A full queue or an unreachable database loses events, never a
 * request. Plain JDBC on purpose: a second {@code DataSource} bean would switch off the metadata database's
 * auto-configuration.
 */
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    static final int QUEUE_SIZE = 1000;
    private static final int BATCH_SIZE = 200;
    private static final int ATTEMPTS = 3;
    private static final long RETRY_WAIT_MS = 1000;
    private static final long STOP_WAIT_MS = 5000;

    private static final String INSERT = """
            INSERT INTO usage_event (event_type, visitor_hash, user_agent, device, status_code, app_version, source,
                                     target_namespace, duration_ms, status, query_options, auth_used)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final UsageProperties props;
    private final BlockingQueue<UsageEvent> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private volatile boolean running;
    private Thread worker;

    public UsageRecorder(UsageProperties props) {
        this.props = props;
    }

    /** Never blocks: when the queue is full the event is dropped. */
    public boolean record(UsageEvent event) {
        return queue.offer(event);
    }

    public void start() {
        running = true;
        worker = new Thread(this::run, "usage-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    /** Lets the worker write what is queued, so a restart loses nothing while the database is reachable. */
    public void stop() throws InterruptedException {
        running = false;
        worker.join(STOP_WAIT_MS);
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            try {
                UsageEvent first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                List<UsageEvent> batch = new ArrayList<>(List.of(first));
                queue.drainTo(batch, BATCH_SIZE - 1);
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void write(List<UsageEvent> batch) throws InterruptedException {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                insert(batch);
                return;
            } catch (SQLException e) {
                if (attempt == ATTEMPTS || !running) {
                    log.warn("Dropped {} usage events: {}", batch.size(), e.getMessage());
                    return;
                }
                Thread.sleep(RETRY_WAIT_MS);
            }
        }
    }

    private void insert(List<UsageEvent> batch) throws SQLException {
        Properties connection = new Properties();
        connection.setProperty("user", props.dbUser());
        connection.setProperty("password", props.dbPassword());
        connection.setProperty("connectTimeout", "5");
        connection.setProperty("socketTimeout", "10");
        try (Connection c = DriverManager.getConnection(props.dbUrl(), connection);
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            for (UsageEvent e : batch) {
                ps.setString(1, e.eventType());
                ps.setString(2, e.visitorHash());
                ps.setString(3, e.userAgent());
                ps.setString(4, e.device());
                ps.setInt(5, e.statusCode());
                ps.setString(6, e.appVersion());
                ps.setString(7, e.source());
                ps.setString(8, e.targetNamespace());
                ps.setInt(9, e.durationMs());
                ps.setString(10, e.status());
                ps.setString(11, e.queryOptions());
                ps.setBoolean(12, e.authUsed());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
