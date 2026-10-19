package at.jdbcodata.admin.ui;

import at.jdbcodata.datasource.DatasourceRepository;
import at.jdbcodata.health.DatasourceHealthService;
import at.jdbcodata.identity.FixedCredentialRepository;
import at.jdbcodata.identity.ServiceUserRepository;
import at.jdbcodata.query.QueryDefinitionRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Model attributes every admin page needs: user, active path, navigation counts, breadcrumb and environment. */
@ControllerAdvice(basePackages = "at.jdbcodata.admin.ui")
public class UiModelAdvice {

    /** Counts and warning dots of the sidebar. */
    public record NavSummary(long datasources, long queries, long serviceUsers, long accounts,
                             boolean datasourceProblem, long queriesWithoutSnapshot) {
    }

    /** Section of the navigation, optional parent list page of a detail page. */
    public record Breadcrumb(String section, String parentLabel, String parentHref) {
    }

    private record Area(String prefix, String section, String label) {
    }

    private static final List<Area> AREAS = List.of(
            new Area("/admin/datasources", "Data", "Datasources"),
            new Area("/admin/queries", "Data", "Queries"),
            new Area("/admin/users", "Identity", "Service users"),
            new Area("/admin/groups", "Identity", "Service users"),
            new Area("/admin/accounts", "Identity", "Technical accounts"),
            new Area("/admin/cache", "Operations", "Cache"),
            new Area("/admin/stats", "Operations", "Statistics"),
            new Area("/admin/audit", "Operations", "Audit log"),
            new Area("/admin/admins", "System", "Admin accounts"),
            new Area("/admin/settings", "System", "Settings"),
            new Area("/admin/search", "Home", "Search"));

    private final DatasourceRepository datasources;
    private final QueryDefinitionRepository queries;
    private final ServiceUserRepository serviceUsers;
    private final FixedCredentialRepository accounts;
    private final DatasourceHealthService health;
    private final String environment;

    public UiModelAdvice(DatasourceRepository datasources, QueryDefinitionRepository queries, ServiceUserRepository serviceUsers,
                         FixedCredentialRepository accounts, DatasourceHealthService health,
                         @Value("${jdbcodata.ui.environment:}") String environment) {
        this.datasources = datasources;
        this.queries = queries;
        this.serviceUsers = serviceUsers;
        this.accounts = accounts;
        this.health = health;
        this.environment = environment == null ? "" : environment.strip();
    }

    @ModelAttribute("currentUser")
    public String currentUser(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    /** Request path without context path, used to highlight the active navigation entry. */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri;
    }

    @ModelAttribute("nav")
    public NavSummary nav(HttpServletRequest request) {
        if ("true".equals(request.getHeader("HX-Request"))) {
            return null; // fragments do not render the shell
        }
        return new NavSummary(datasources.count(), queries.count(), serviceUsers.count(), accounts.count(),
                health.all().stream().anyMatch(DatasourceHealthService.Health::problem),
                queries.countByEnabledTrueAndColumnsIsEmpty());
    }

    @ModelAttribute("breadcrumb")
    public Breadcrumb breadcrumb(HttpServletRequest request) {
        String path = currentPath(request);
        for (Area a : AREAS) {
            if (path.equals(a.prefix())) {
                return new Breadcrumb(a.section(), null, null);
            }
            if (path.startsWith(a.prefix() + "/")) {
                return new Breadcrumb(a.section(), a.label(), a.prefix());
            }
        }
        return new Breadcrumb("Home", null, null);
    }

    /** OData service root, shown in the sidebar for quick copying. */
    @ModelAttribute("serviceRoot")
    public String serviceRoot() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/odata/").toUriString();
    }

    /** Label of the deployment (e.g. DEV, TEST, PROD) from {@code jdbcodata.ui.environment}; empty = no badge. */
    @ModelAttribute("environment")
    public String environment() {
        return environment;
    }

    /** Badge tone of the environment: production red, test/staging amber, everything else blue. */
    @ModelAttribute("environmentTone")
    public String environmentTone() {
        String e = environment.toUpperCase();
        if (e.startsWith("PROD") || e.equals("LIVE")) return "err";
        if (e.startsWith("TEST") || e.startsWith("STAG") || e.startsWith("QA") || e.startsWith("INT")) return "warn";
        return "info";
    }
}
