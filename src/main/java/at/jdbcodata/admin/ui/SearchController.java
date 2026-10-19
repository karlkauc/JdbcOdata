package at.jdbcodata.admin.ui;

import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.QueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Global search of the top bar: queries, datasources, service users, technical accounts and pages. */
@Controller
public class SearchController {

    static final int PER_GROUP = 6;

    public record Hit(String label, String detail, String href, String icon) {
    }

    public record Group(String title, List<Hit> hits) {
    }

    private record Page(String label, String href, String icon, String keywords) {
    }

    private static final List<Page> PAGES = List.of(
            new Page("Overview", "/admin", "home", "home dashboard start"),
            new Page("Datasources", "/admin/datasources", "database", "jdbc connection"),
            new Page("New datasource", "/admin/datasources/new", "database", "create add"),
            new Page("Queries", "/admin/queries", "code", "sql entityset feed"),
            new Page("New query", "/admin/queries/new", "code", "create add sql"),
            new Page("Service users", "/admin/users", "users", "login user"),
            new Page("Groups", "/admin/groups", "users", "service users"),
            new Page("Technical accounts", "/admin/accounts", "key", "fixed account"),
            new Page("Cache", "/admin/cache", "layers", "invalidate results"),
            new Page("Statistics", "/admin/stats", "chart", "executions errors duration"),
            new Page("Audit log", "/admin/audit", "audit", "changes history"),
            new Page("Admin accounts", "/admin/admins", "shield", "administrator viewer"),
            new Page("Settings", "/admin/settings", "settings", "configuration master key"));

    private final QueryService queries;
    private final DatasourceService datasources;
    private final ServiceUserService serviceUsers;
    private final FixedCredentialService accounts;

    public SearchController(QueryService queries, DatasourceService datasources, ServiceUserService serviceUsers,
                            FixedCredentialService accounts) {
        this.queries = queries;
        this.datasources = datasources;
        this.serviceUsers = serviceUsers;
        this.accounts = accounts;
    }

    @GetMapping("/admin/search")
    public String search(@RequestParam(defaultValue = "") String q, Model model,
                         @RequestHeader(value = "HX-Request", required = false) String htmx) {
        model.addAttribute("q", q);
        model.addAttribute("groups", search(q));
        return "true".equals(htmx) ? "admin/search :: results" : "admin/search";
    }

    List<Group> search(String q) {
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<Group> groups = new ArrayList<>();
        add(groups, "Queries", queries.findAll().stream()
                .filter(x -> matches(needle, x.name(), x.description(), x.datasourceName()))
                .map(x -> new Hit(x.name(), x.datasourceName() + " · " + x.executionMode()
                        + (x.hasSnapshot() ? "" : " · no snapshot"), "/admin/queries/" + x.id(), "code")));
        add(groups, "Datasources", datasources.findAll().stream()
                .filter(x -> matches(needle, x.name(), x.description(), x.jdbcUrl()))
                .map(x -> new Hit(x.name(), x.jdbcUrl(), "/admin/datasources/" + x.id(), "database")));
        add(groups, "Service users", serviceUsers.findAll().stream()
                .filter(x -> matches(needle, x.username(), x.displayName()))
                .map(x -> new Hit(x.username(), x.displayName() == null ? "" : x.displayName(), "/admin/users/" + x.id(), "users")));
        add(groups, "Technical accounts", accounts.findAll().stream()
                .filter(x -> matches(needle, x.name(), x.dbUsername(), x.datasourceName()))
                .map(x -> new Hit(x.name(), x.datasourceName() + " / " + x.dbUsername(), "/admin/accounts/" + x.id(), "key")));
        add(groups, "Pages", PAGES.stream()
                .filter(p -> matches(needle, p.label(), p.keywords()))
                .map(p -> new Hit(p.label(), "", p.href(), p.icon())));
        return groups;
    }

    private static void add(List<Group> groups, String title, Stream<Hit> hits) {
        List<Hit> list = hits.limit(PER_GROUP).toList();
        if (!list.isEmpty()) {
            groups.add(new Group(title, list));
        }
    }

    private static boolean matches(String needle, String... fields) {
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }
}
