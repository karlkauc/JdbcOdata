package at.jdbcodata.admin.ui;

import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final DatasourceService datasourceService;
    private final QueryService queryService;

    public HomeController(DatasourceService datasourceService, QueryService queryService) {
        this.datasourceService = datasourceService;
        this.queryService = queryService;
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
        model.addAttribute("datasourceCount", datasourceService.findAll().size());
        model.addAttribute("queryCount", queries.size());
        model.addAttribute("queriesWithoutSnapshot", queries.stream().filter(q -> !q.hasSnapshot()).map(QueryDefinition::name).toList());
        model.addAttribute("recentQueries", queries.stream()
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt())).limit(5).toList());
        return "admin/dashboard";
    }
}
