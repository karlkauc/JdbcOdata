package at.jdbcodata.admin.api;

import at.jdbcodata.admin.account.AdminRole;
import at.jdbcodata.admin.account.AdminUser;
import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.audit.AuditEntry;
import at.jdbcodata.audit.AuditService;
import at.jdbcodata.cache.CacheEntryInfo;
import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.stats.StatsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cache, statistics, audit log and admin accounts. */
@RestController
@RequestMapping("/api/v1")
public class OperationsController {

    record AdminUserRequest(@NotBlank @Size(max = 100) String username, String password, AdminRole role, Boolean enabled) {
    }

    private final ResultCache cache;
    private final StatsService stats;
    private final AuditService audit;
    private final AdminUserService admins;

    public OperationsController(ResultCache cache, StatsService stats, AuditService audit, AdminUserService admins) {
        this.cache = cache;
        this.stats = stats;
        this.audit = audit;
        this.admins = admins;
    }

    // --- cache -------------------------------------------------------------------------------------------------

    @GetMapping("/cache")
    public Map<String, Object> cache(@RequestParam(defaultValue = "200") int limit) {
        List<CacheEntryInfo> entries = cache.list(limit);
        return Map.of("stats", cache.stats(), "entries", entries);
    }

    @DeleteMapping("/cache")
    public Map<String, Object> invalidate(@RequestParam(required = false) UUID queryId,
                                          @RequestParam(required = false) String dbUser) {
        long n = queryId == null ? cache.invalidateAllByAdmin() : cache.invalidateByAdmin(queryId, dbUser);
        return Map.of("invalidated", n);
    }

    // --- stats -------------------------------------------------------------------------------------------------

    @GetMapping("/stats/summary")
    public Map<String, Object> summary(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant[] range = range(from, to);
        return Map.of(
                "from", range[0], "to", range[1],
                "summary", stats.summary(range[0], range[1]),
                "topQueries", stats.topQueries(range[0], range[1], 10),
                "slowestQueries", stats.slowestQueries(range[0], range[1], 10),
                "callers", stats.callers(range[0], range[1], 20),
                "perDay", stats.perDay(range[0], range[1]));
    }

    @GetMapping("/stats/executions")
    public List<StatsService.Execution> executions(@RequestParam(required = false) Instant from,
                                                   @RequestParam(required = false) Instant to,
                                                   @RequestParam(required = false) String queryName,
                                                   @RequestParam(required = false) String caller,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "100") int limit,
                                                   @RequestParam(defaultValue = "0") int offset) {
        Instant[] range = range(from, to);
        return stats.executions(new StatsService.Filter(range[0], range[1], queryName, caller, status), limit, offset);
    }

    @GetMapping(value = "/stats/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to,
                                            @RequestParam(required = false) String queryName,
                                            @RequestParam(required = false) String caller,
                                            @RequestParam(required = false) String status) {
        Instant[] range = range(from, to);
        List<StatsService.Execution> rows = stats.executions(new StatsService.Filter(range[0], range[1], queryName, caller, status), 10_000, 0);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"executions.csv\"")
                .body(stats.toCsv(rows));
    }

    static Instant[] range(Instant from, Instant to) {
        Instant end = to == null ? Instant.now().plus(Duration.ofMinutes(1)) : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        return new Instant[] {start, end};
    }

    // --- audit -------------------------------------------------------------------------------------------------

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "200") int limit,
                                  @RequestParam(required = false) String entityType,
                                  @RequestParam(required = false) String entityId) {
        if (entityType != null && entityId != null) {
            return audit.forEntity(entityType, entityId, limit);
        }
        return audit.latest(limit);
    }

    // --- admin accounts ----------------------------------------------------------------------------------------

    @GetMapping("/admins")
    public List<AdminUser> admins() {
        return admins.findAll();
    }

    @PostMapping("/admins")
    public ResponseEntity<AdminUser> createAdmin(@Valid @RequestBody AdminUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admins.create(
                new AdminUserService.AdminUserSpec(request.username(), request.password(), request.role(), request.enabled())));
    }

    @PutMapping("/admins/{id}")
    public AdminUser updateAdmin(@PathVariable UUID id, @Valid @RequestBody AdminUserRequest request) {
        return admins.update(id, new AdminUserService.AdminUserSpec(request.username(), request.password(), request.role(), request.enabled()));
    }

    @DeleteMapping("/admins/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdmin(@PathVariable UUID id) {
        admins.delete(id);
    }
}
