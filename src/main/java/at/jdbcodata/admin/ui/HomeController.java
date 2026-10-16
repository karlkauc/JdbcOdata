package at.jdbcodata.admin.ui;

import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.stats.StatsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class HomeController {

    private final DatasourceService datasourceService;
    private final QueryService queryService;
    private final ServiceUserService serviceUsers;
    private final FixedCredentialService fixedCredentials;
    private final StatsService stats;
    private final ResultCache cache;

    public HomeController(DatasourceService datasourceService, QueryService queryService, ServiceUserService serviceUsers,
                          FixedCredentialService fixedCredentials, StatsService stats, ResultCache cache) {
        this.datasourceService = datasourceService;
        this.queryService = queryService;
        this.serviceUsers = serviceUsers;
        this.fixedCredentials = fixedCredentials;
        this.stats = stats;
        this.cache = cache;
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
        var queries = queryService.findAll();
        Instant now = Instant.now();
        model.addAttribute("datasourceCount", datasourceService.findAll().size());
        model.addAttribute("queryCount", queries.size());
        model.addAttribute("serviceUserCount", serviceUsers.findAll().size());
        model.addAttribute("accountCount", fixedCredentials.findAll().size());
        model.addAttribute("summary", stats.summary(now.minus(Duration.ofHours(24)), now));
        model.addAttribute("cacheStats", cache.stats());
        model.addAttribute("queriesWithoutSnapshot", queries.stream().filter(q -> !q.hasSnapshot()).map(QueryDefinition::name).toList());
        model.addAttribute("recentQueries", queries.stream()
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt())).limit(5).toList());
        model.addAttribute("serviceRoot", ServletUriComponentsBuilder.fromCurrentContextPath().path("/odata/").toUriString());
        return "admin/dashboard";
    }
}
