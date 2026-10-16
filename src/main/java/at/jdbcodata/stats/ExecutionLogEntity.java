package at.jdbcodata.stats;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_log")
public class ExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;
    @Column(name = "query_id")
    private UUID queryId;
    @Column(name = "query_name", nullable = false, length = 100)
    private String queryName;
    @Column(name = "query_version")
    private Integer queryVersion;
    @Column(name = "datasource_id")
    private UUID datasourceId;
    @Column(name = "datasource_name", length = 100)
    private String datasourceName;
    @Column(nullable = false, length = 200)
    private String caller;
    @Column(name = "effective_db_user", length = 200)
    private String effectiveDbUser;
    @Column(name = "execution_mode", length = 20)
    private String executionMode;
    @Column(nullable = false, length = 20)
    private String client;
    @Column(name = "user_agent", length = 500)
    private String userAgent;
    @Column(name = "client_ip", length = 100)
    private String clientIp;
    @Column
    private String parameters;
    @Column(name = "odata_options", length = 2000)
    private String odataOptions;
    @Column(name = "cache_status", nullable = false, length = 20)
    private String cacheStatus;
    @Column(name = "db_duration_ms")
    private Long dbDurationMs;
    @Column(name = "total_duration_ms", nullable = false)
    private long totalDurationMs;
    @Column(name = "row_count")
    private Integer rowCount;
    @Column(name = "bytes_sent")
    private Long bytesSent;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    static ExecutionLogEntity of(ExecutionRecord r) {
        ExecutionLogEntity e = new ExecutionLogEntity();
        e.startedAt = r.startedAt();
        e.finishedAt = r.finishedAt();
        e.queryId = r.queryId();
        e.queryName = r.queryName();
        e.queryVersion = r.queryVersion();
        e.datasourceId = r.datasourceId();
        e.datasourceName = r.datasourceName();
        e.caller = r.caller();
        e.effectiveDbUser = r.effectiveDbUser();
        e.executionMode = r.executionMode() == null ? null : r.executionMode().name();
        e.client = r.client().name();
        e.userAgent = trim(r.userAgent(), 500);
        e.clientIp = r.clientIp();
        e.parameters = r.parameters();
        e.odataOptions = trim(r.odataOptions(), 2000);
        e.cacheStatus = r.cacheStatus().name();
        e.dbDurationMs = r.dbDurationMs();
        e.totalDurationMs = r.totalDurationMs();
        e.rowCount = r.rowCount();
        e.bytesSent = r.bytesSent();
        e.status = r.status().name();
        e.errorMessage = trim(r.errorMessage(), 1000);
        return e;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
