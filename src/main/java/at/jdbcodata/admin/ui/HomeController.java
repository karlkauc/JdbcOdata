package at.jdbcodata.admin.ui;

import at.jdbcodata.health.DatasourceHealthService;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.stats.StatsService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    /** A query is reported as unused when it is older than this and had no call in this period. */
    static final int UNUSED_DAYS = 30;

    /** Key figure of the dashboard; {@code tone} colors the comparison line (ok, warn, err, muted). */
    public record Kpi(String label, String value, String delta, String tone, String sparkPoints, String sparkTitle,
                      String href) {
    }

    /** Entry of "Needs attention"; {@code post} = the action is a form submit instead of a link. */
    public record Attention(String tone, String icon, String title, String detail, String action, String href, boolean post) {
    }

    private final QueryService queryService;
    private final StatsService stats;
    private final DatasourceHealthService health;
    private final UiFormat fmt;

    public HomeController(QueryService queryService, StatsService stats, DatasourceHealthService health, UiFormat fmt) {
        this.queryService = queryService;
        this.stats = stats;
        this.health = health;
        this.fmt = fmt;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/admin";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        Instant now = Instant.now();
        List<QueryDefinition> queries = queryService.findAll();
        StatsService.Summary day = stats.summary(now.minus(Duration.ofHours(24)), now);
        StatsService.Summary before = stats.summary(now.minus(Duration.ofHours(48)), now.minus(Duration.ofHours(24)));
        List<StatsService.HourStat> hours = stats.perHour(now, 24);
        List<DatasourceHealthService.Health> sources = health.all();

        model.addAttribute("kpis", kpis(day, before, hours));
        List<Attention> attention = attention(queries, sources, day, now);
        model.addAttribute("attention", attention);
        model.addAttribute("health", sources);
        model.addAttribute("recentQueries", queries.stream()
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt())).limit(5).toList());
        model.addAttribute("summary", day);
        return "admin/dashboard";
    }

    private List<Kpi> kpis(StatsService.Summary day, StatsService.Summary before, List<StatsService.HourStat> hours) {
        List<Kpi> kpis = new ArrayList<>();
        String execDelta = before.executions() == 0
                ? (day.executions() == 0 ? "no executions" : "previous day: none")
                : signed(Math.round((day.executions() - before.executions()) * 100.0 / before.executions())) + " % vs. previous day";
        kpis.add(new Kpi("Executions · 24 h", fmt.num(day.executions()), execDelta, "muted",
                fmt.spark(hours.stream().map(StatsService.HourStat::executions).toList(), 96, 30), "Executions per hour", "/admin/stats?period=24h"));
        String faultText = fmt.num(day.faults()) + (day.faults() == 1 ? " error" : " errors")
                + (day.authFailures() > 0 ? " · " + fmt.num(day.authFailures()) + " rejected sign-in" + (day.authFailures() == 1 ? "" : "s") : "");
        kpis.add(new Kpi("Error rate", fmt.pct(day.faultRatio(), 1), faultText, day.faults() == 0 ? "ok" : "err",
                fmt.spark(hours.stream().map(StatsService.HourStat::faults).toList(), 96, 30),
                "Errors per hour (without rejected sign-ins)", "/admin/stats?period=24h&status=" + StatsService.FAULTS));
        long ppDelta = Math.round((day.cacheHitRatio() - before.cacheHitRatio()) * 100);
        kpis.add(new Kpi("Cache hit rate", fmt.pct(day.cacheHitRatio(), 0),
                before.executions() == 0 ? fmt.num(day.cacheHits()) + " hits" : signed(ppDelta) + " pp vs. previous day", "muted",
                fmt.spark(hours.stream().map(h -> h.executions() == 0 ? 0.0 : (double) h.cacheHits() / h.executions()).toList(), 96, 30),
                "Hit rate per hour", "/admin/cache"));
        long p95Delta = day.p95TotalMs() - before.p95TotalMs();
        kpis.add(new Kpi("Duration P50 / P95", day.p50TotalMs() + " / " + day.p95TotalMs() + " ms",
                before.executions() == 0 ? "avg " + Math.round(day.avgTotalMs()) + " ms" : "P95 " + signed(p95Delta) + " ms vs. previous day",
                before.executions() > 0 && p95Delta > Math.max(50, before.p95TotalMs() / 5) ? "warn" : "muted",
                fmt.spark(hours.stream().map(StatsService.HourStat::avgTotalMs).toList(), 96, 30), "Average duration per hour", "/admin/stats?period=24h"));
        return kpis;
    }

    private List<Attention> attention(List<QueryDefinition> queries, List<DatasourceHealthService.Health> sources,
                                      StatsService.Summary day, Instant now) {
        List<Attention> items = new ArrayList<>();
        for (DatasourceHealthService.Health h : sources) {
            if (h.state() == DatasourceHealthService.State.DOWN) {
                items.add(new Attention("err", "alert", "Datasource \"" + h.name() + "\" is unreachable",
                        fmt.ago(h.checkedAt()) + " · " + h.detail(), "Check again",
                        "/admin/datasources/" + h.datasourceId() + "/health", true));
            } else if (h.state() == DatasourceHealthService.State.WARN) {
                items.add(new Attention("warn", "alert", "Datasource \"" + h.name() + "\": last execution failed",
                        fmt.ago(h.checkedAt()) + " · " + h.detail(), "Test connection", "/admin/datasources/" + h.datasourceId(), false));
            }
        }
        for (QueryDefinition q : queries) {
            if (q.enabled() && !q.hasSnapshot()) {
                items.add(new Attention("warn", "table", "Query \"" + q.name() + "\" has no schema snapshot",
                        "It is missing from the service document until a snapshot is taken.", "Take snapshot",
                        "/admin/queries/" + q.id() + "#spalten", false));
            }
        }
        if (day.faults() > 0) {
            items.add(new Attention("err", "alert", day.faults() + (day.faults() == 1 ? " failed execution" : " failed executions") + " in 24 h",
                    "Error rate " + fmt.pct(day.faultRatio(), 1) + " – details in the statistics.", "View",
                    "/admin/stats?period=24h&status=" + StatsService.FAULTS, false));
        }
        if (day.authFailures() > 0) {
            items.add(new Attention("info", "lock", day.authFailures() + (day.authFailures() == 1 ? " rejected sign-in" : " rejected sign-ins") + " in 24 h",
                    "The database rejected the credentials (wrong password or unknown user). Usually a typo in Excel or a test.",
                    "View", "/admin/stats?period=24h&status=AUTH_FAILED", false));
        }
        Map<UUID, StatsService.QueryUsage> usage = stats.usageByQuery(UNUSED_DAYS, LocalDate.now());
        Instant cutoff = now.minus(Duration.ofDays(UNUSED_DAYS));
        List<String> unused = queries.stream()
                .filter(q -> q.enabled() && q.hasSnapshot() && q.createdAt() != null && q.createdAt().isBefore(cutoff))
                .filter(q -> usage.get(q.id()) == null || usage.get(q.id()).lastCall().isBefore(cutoff))
                .map(QueryDefinition::name).toList();
        if (!unused.isEmpty()) {
            items.add(new Attention("info", "clock", unused.size() + (unused.size() == 1 ? " query" : " queries") + " not called for "
                    + UNUSED_DAYS + " days", String.join(", ", unused.stream().limit(5).toList())
                    + (unused.size() > 5 ? " …" : "") + " – disable or delete?", "View", "/admin/queries?usage=unused", false));
        }
        return items;
    }

    private static String signed(long n) {
        return (n > 0 ? "+" : n < 0 ? "−" : "±") + Math.abs(n);
    }
}
