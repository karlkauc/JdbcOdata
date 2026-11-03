package at.jdbcodata.query;

import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbErrorClassifier;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.stats.Tracing;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A running query whose rows are read one by one, so that the heap does not grow with the result. It holds a slot of
 * the datasource and a connection until {@link #close}; the caller closes it before the response goes to the network.
 */
public final class ResultCursor implements AutoCloseable {

    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static final long ABORT_AFTER_CANCEL_MS = 2000;

    private final QueryDefinition query;
    private final Datasource ds;
    private final ConcurrencyLimiter.Permit permit;
    private final int maxRows;
    private final int timeoutSec;
    private final Tracing.DbSpan span;
    Connection connection;
    private PreparedStatement statement;
    private ResultSet results;
    private List<ResultColumn> columns;
    private ScheduledFuture<?> deadline;
    private volatile boolean timedOut;
    private boolean exhausted;
    private volatile boolean closed;
    private long rows;
    private long dbNanos;
    private RuntimeException failure;

    ResultCursor(QueryDefinition query, Datasource ds, ConcurrencyLimiter.Permit permit, int maxRows, int timeoutSec,
                 Tracing.DbSpan span) {
        this.query = query;
        this.ds = ds;
        this.permit = permit;
        this.maxRows = maxRows;
        this.timeoutSec = timeoutSec;
        this.span = span;
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("jdbcodata-cursor-deadline").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * The statement timeout of JDBC covers single calls, not a read over many fetches. This limit covers the whole
     * read, so a slow reader cannot keep slot and connection beyond it.
     */
    void watch(PreparedStatement ps) {
        statement = ps;
        if (timeoutSec > 0) deadline = DEADLINES.schedule(this::expire, timeoutSec, TimeUnit.SECONDS);
    }

    private void expire() {
        timedOut = true;
        // a cancel opens a connection of its own and must not hold up other cursors when the database hangs
        Thread.ofPlatform().daemon().start(() -> {
            cancel();
            try {
                Thread.sleep(ABORT_AFTER_CANCEL_MS);
                // pgjdbc ignores a cancel between fetches, so a stalled fetch ends only with its connection
                if (!closed) connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException ignored) {
                // closed in the meantime
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void cancel() {
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException ignored) {
            // the statement has just ended, or the database is gone and the read fails by itself
        }
    }

    void start(ResultSet rs) throws SQLException {
        results = rs;
        span.event("executed");
        columns = List.copyOf(QueryExecutor.columns(rs));
        dbNanos = 0;
    }

    public List<ResultColumn> columns() {
        return columns;
    }

    /**
     * The next row, or {@code null} after the last one.
     *
     * @throws ResultTooLargeException more than {@code maxRows} rows
     * @throws DbExecutionException    any database error, also the time limit
     */
    public Object[] next() {
        try {
            return read();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        }
    }

    private Object[] read() {
        long start = System.nanoTime();
        try {
            if (timedOut) throw timeout(null);
            if (!results.next()) {
                exhausted = true;
                return null;
            }
            if (rows >= maxRows) throw new ResultTooLargeException(query.name(), maxRows);
            rows++;
            return QueryExecutor.row(results, columns);
        } catch (SQLException e) {
            throw timedOut ? timeout(e) : DbErrorClassifier.translate(e, ds.name());
        } finally {
            dbNanos += System.nanoTime() - start;
        }
    }

    private DbExecutionException timeout(SQLException cause) {
        return new DbExecutionException("Query '" + query.name() + "' exceeded its time limit of " + timeoutSec + " s", true, cause);
    }

    public long rows() {
        return rows;
    }

    /** Time spent waiting for the database, without the time the caller needs per row. */
    public long dbMillis() {
        return dbNanos / 1_000_000;
    }

    /** Closes after a failed start and returns {@code e} for throwing. */
    RuntimeException closeOnError(RuntimeException e) {
        failure = e;
        close();
        return e;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (deadline != null) deadline.cancel(false);
        // MariaDB's protocol has no way to stop a result: closing it would read all remaining rows first
        if (!exhausted && statement != null && SqlDialect.of(ds.jdbcUrl()) == SqlDialect.MYSQL) cancel();
        try {
            closeQuietly(results);
            closeQuietly(statement);
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // nothing to roll back for a read-only statement
                }
                closeQuietly(connection);
            }
        } finally {
            permit.close();
            if (failure == null) span.end(rows);
            else span.fail(failure);
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // a cancelled statement may report that when it closes; the connection is reset by the pool
        }
    }
}
