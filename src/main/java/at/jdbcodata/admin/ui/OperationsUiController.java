package at.jdbcodata.admin.ui;

import at.jdbcodata.admin.account.AdminRole;
import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.audit.AuditService;
import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.crypto.MasterKeyProvider;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.odata.EdmRegistry;
import at.jdbcodata.stats.ExecutionLogService;
import at.jdbcodata.stats.StatsService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Cache, statistics, audit log, admin accounts and settings pages. */
@Controller
@RequestMapping("/admin")
public class OperationsUiController {

    private final ResultCache cache;
    private final StatsService stats;
    private final AuditService audit;
    private final AdminUserService admins;
    private final JdbcOdataProperties props;
    private final MasterKeyProvider masterKey;
    private final IdentityPoolManager pools;
    private final EdmRegistry edmRegistry;
    private final ExecutionLogService executionLog;

    public OperationsUiController(ResultCache cache, StatsService stats, AuditService audit, AdminUserService admins,
                                  JdbcOdataProperties props, MasterKeyProvider masterKey, IdentityPoolManager pools,
                                  EdmRegistry edmRegistry, ExecutionLogService executionLog) {
        this.cache = cache;
        this.stats = stats;
        this.audit = audit;
        this.admins = admins;
        this.props = props;
        this.masterKey = masterKey;
        this.pools = pools;
        this.edmRegistry = edmRegistry;
        this.executionLog = executionLog;
    }

    @GetMapping("/cache")
    public String cache(Model model) {
        model.addAttribute("stats", cache.stats());
        model.addAttribute("entries", cache.list(500));
        return "admin/cache";
    }

    @PostMapping("/cache/invalidate")
    public String invalidate(@RequestParam(required = false) UUID queryId, @RequestParam(required = false) String dbUser,
                             RedirectAttributes redirect) {
        long n = queryId == null ? cache.invalidateAllByAdmin() : cache.invalidateByAdmin(queryId, dbUser);
        redirect.addFlashAttribute("message", n + (n == 1 ? " cache entry" : " cache entries") + " invalidated.");
        return "redirect:/admin/cache";
    }

    @GetMapping("/stats")
    public String stats(@RequestParam(defaultValue = "7d") String period, @RequestParam(required = false) String queryName,
                        @RequestParam(required = false) String caller, @RequestParam(required = false) String status,
                        Model model) {
        Duration d = switch (period) {
            case "24h" -> Duration.ofHours(24);
            case "30d" -> Duration.ofDays(30);
            case "90d" -> Duration.ofDays(90);
            default -> Duration.ofDays(7);
        };
        Instant to = Instant.now().plus(Duration.ofMinutes(1));
        Instant from = to.minus(d);
        model.addAttribute("period", period);
        model.addAttribute("from", from);
        model.addAttribute("summary", stats.summary(from, to));
        model.addAttribute("topQueries", stats.topQueries(from, to, 10));
        model.addAttribute("slowest", stats.slowestQueries(from, to, 10));
        model.addAttribute("callers", stats.callers(from, to, 20));
        model.addAttribute("perDay", stats.perDay(from, to));
        model.addAttribute("executions", stats.executions(new StatsService.Filter(from, to, queryName, caller, status), 100, 0));
        model.addAttribute("queryName", queryName);
        model.addAttribute("caller", caller);
        model.addAttribute("status", status);
        return "admin/stats";
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(defaultValue = "200") int limit, Model model) {
        model.addAttribute("entries", audit.latest(limit));
        return "admin/audit";
    }

    @GetMapping("/admins")
    public String admins(Model model) {
        model.addAttribute("admins", admins.findAll());
        model.addAttribute("roles", AdminRole.values());
        return "admin/admins";
    }

    @PostMapping("/admins")
    public String createAdmin(@RequestParam String username, @RequestParam String password,
                              @RequestParam(defaultValue = "ADMIN") AdminRole role, RedirectAttributes redirect) {
        try {
            admins.create(new AdminUserService.AdminUserSpec(username, password, role, true));
            redirect.addFlashAttribute("message", "Admin account '" + username + "' created.");
        } catch (ConflictException | InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/admins";
    }

    @PostMapping("/admins/{id}")
    public String updateAdmin(@PathVariable UUID id, @RequestParam(required = false) String password,
                              @RequestParam(required = false) AdminRole role, @RequestParam(required = false) Boolean enabled,
                              RedirectAttributes redirect) {
        try {
            admins.update(id, new AdminUserService.AdminUserSpec(null, password, role, enabled != null && enabled));
            redirect.addFlashAttribute("message", "Admin account saved.");
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/admins";
    }

    @PostMapping("/admins/{id}/delete")
    public String deleteAdmin(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            admins.delete(id);
            redirect.addFlashAttribute("message", "Admin account deleted.");
        } catch (ConflictException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/admins";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("props", props);
        model.addAttribute("keySource", masterKey.source());
        model.addAttribute("keyId", masterKey.keyId());
        model.addAttribute("pools", pools.activePoolCount());
        model.addAttribute("edmModels", edmRegistry.size());
        model.addAttribute("droppedLogRecords", executionLog.droppedRecords());
        model.addAttribute("cacheStats", cache.stats());
        return "admin/settings";
    }
}
