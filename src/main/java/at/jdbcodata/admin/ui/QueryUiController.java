package at.jdbcodata.admin.ui;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.PrincipalType;
import at.jdbcodata.query.ColumnSetting;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QueryTestService;
import at.jdbcodata.query.SchemaSnapshotService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/admin/queries")
public class QueryUiController {

    static final String TEST_PARAM_PREFIX = "tp_";

    private final QueryService queryService;
    private final DatasourceService datasourceService;
    private final QueryTestService testService;
    private final SchemaSnapshotService snapshotService;
    private final FixedCredentialService fixedCredentials;
    private final ServiceUserService serviceUsers;

    public QueryUiController(QueryService queryService, DatasourceService datasourceService,
                             QueryTestService testService, SchemaSnapshotService snapshotService,
                             FixedCredentialService fixedCredentials, ServiceUserService serviceUsers) {
        this.queryService = queryService;
        this.datasourceService = datasourceService;
        this.testService = testService;
        this.snapshotService = snapshotService;
        this.fixedCredentials = fixedCredentials;
        this.serviceUsers = serviceUsers;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("queries", queryService.findAll());
        return "admin/queries/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        return editor(model, new QueryForm(), null);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") QueryForm form, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return editor(model, form, null);
        }
        try {
            QueryDefinition created = queryService.create(form.toSpec());
            redirect.addFlashAttribute("message", "Query '" + created.name() + "' angelegt. Jetzt Schema-Snapshot erstellen.");
            return "redirect:/admin/queries/" + created.id();
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, null);
        }
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable UUID id, Model model) {
        QueryDefinition q = queryService.require(id);
        return editor(model, QueryForm.from(q), q);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") QueryForm form, BindingResult binding,
                         Model model, RedirectAttributes redirect) {
        QueryDefinition q = queryService.require(id);
        if (binding.hasErrors()) {
            return editor(model, form, q);
        }
        try {
            QueryDefinition updated = queryService.update(id, form.toSpec());
            redirect.addFlashAttribute("message", updated.hasSnapshot()
                    ? "Query gespeichert (Version " + updated.version() + ")."
                    : "Query gespeichert (Version " + updated.version() + "). Schema-Snapshot muss erneuert werden.");
            return "redirect:/admin/queries/" + id;
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, q);
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        QueryDefinition q = queryService.require(id);
        queryService.delete(id);
        redirect.addFlashAttribute("message", "Query '" + q.name() + "' gelöscht.");
        return "redirect:/admin/queries";
    }

    /** htmx: runs the SQL currently in the editor (saved or not) and renders a preview fragment. */
    @PostMapping("/test")
    public String test(@ModelAttribute("form") QueryForm form, @RequestParam String dbUser,
                       @RequestParam(defaultValue = "") String dbPassword, @RequestParam Map<String, String> all,
                       Model model) {
        try {
            QueryDefinition q = queryService.transientDefinition(form.toSpec());
            model.addAttribute("result", testService.test(q, identity(dbUser, dbPassword), testParams(all)));
        } catch (InvalidRequestException | NotFoundException | DbAuthenticationException | DbExecutionException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "admin/queries/test-result";
    }

    /** htmx: takes the schema snapshot and renders the columns fragment. */
    @PostMapping("/{id}/snapshot")
    public String snapshot(@PathVariable UUID id, @RequestParam String dbUser,
                           @RequestParam(defaultValue = "") String dbPassword, @RequestParam Map<String, String> all,
                           Model model) {
        QueryDefinition q = queryService.require(id);
        try {
            q = snapshotService.snapshot(q, identity(dbUser, dbPassword), testParams(all));
            model.addAttribute("snapshotMessage", "Snapshot erstellt: " + q.columns().size() + " Spalten.");
        } catch (InvalidRequestException | DbAuthenticationException | DbExecutionException e) {
            model.addAttribute("snapshotError", e.getMessage());
        }
        model.addAttribute("query", q);
        return "admin/queries/columns";
    }

    /** Saves key/visible flags; checkbox names are {@code key_<property>} and {@code visible_<property>}. */
    @PostMapping("/{id}/columns")
    public String columns(@PathVariable UUID id, @RequestParam Map<String, String> all, RedirectAttributes redirect) {
        QueryDefinition q = queryService.require(id);
        List<ColumnSetting> settings = new ArrayList<>();
        for (QueryColumn c : q.columns()) {
            settings.add(new ColumnSetting(c.propertyName(), all.containsKey("key_" + c.propertyName()),
                    all.containsKey("visible_" + c.propertyName())));
        }
        queryService.updateColumnSettings(id, settings);
        redirect.addFlashAttribute("message", "Spalteneinstellungen gespeichert.");
        return "redirect:/admin/queries/" + id;
    }

    /** Grants of a FIXED query: checkbox {@code all}, {@code user_<id>} and {@code group_<id>}. */
    @PostMapping("/{id}/grants")
    public String grants(@PathVariable UUID id, @RequestParam Map<String, String> all, RedirectAttributes redirect) {
        queryService.require(id);
        List<QueryService.GrantSpec> specs = new ArrayList<>();
        if (all.containsKey("all")) {
            specs.add(new QueryService.GrantSpec(PrincipalType.ALL_AUTHENTICATED, null));
        }
        all.keySet().forEach(k -> {
            if (k.startsWith("user_")) specs.add(new QueryService.GrantSpec(PrincipalType.USER, UUID.fromString(k.substring(5))));
            if (k.startsWith("group_")) specs.add(new QueryService.GrantSpec(PrincipalType.GROUP, UUID.fromString(k.substring(6))));
        });
        try {
            queryService.setGrants(id, specs);
            redirect.addFlashAttribute("message", "Freigaben gespeichert (" + specs.size() + ").");
        } catch (InvalidRequestException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/queries/" + id;
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public String restore(@PathVariable UUID id, @PathVariable int version, RedirectAttributes redirect) {
        try {
            QueryDefinition restored = queryService.restoreVersion(id, version);
            redirect.addFlashAttribute("message", "Version " + version + " wiederhergestellt als Version "
                    + restored.version() + ". Schema-Snapshot muss erneuert werden.");
        } catch (InvalidRequestException | ConflictException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/queries/" + id;
    }

    private String editor(Model model, QueryForm form, QueryDefinition query) {
        model.addAttribute("form", form);
        model.addAttribute("query", query);
        model.addAttribute("datasources", datasourceService.findAll());
        model.addAttribute("parameterTypes", ParameterType.values());
        model.addAttribute("queryNames", queryService.findAll().stream().map(QueryDefinition::name).toList());
        model.addAttribute("executionModes", ExecutionMode.values());
        model.addAttribute("versions", query == null ? List.of() : queryService.versions(query.id()));
        model.addAttribute("feedUrl", ServletUriComponentsBuilder.fromCurrentContextPath().path("/odata/").toUriString());
        model.addAttribute("fixedCredentials", fixedCredentials.findAll());
        model.addAttribute("serviceUsers", serviceUsers.findAll());
        model.addAttribute("serviceGroups", serviceUsers.findAllGroups());
        model.addAttribute("grants", query == null ? List.of() : queryService.grants(query.id()));
        model.addAttribute("grantedIds", query == null ? java.util.Set.of()
                : queryService.grants(query.id()).stream().map(g -> g.principalId()).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("grantedAll", query != null && queryService.grants(query.id()).stream().anyMatch(g -> g.principalType() == PrincipalType.ALL_AUTHENTICATED));
        return "admin/queries/form";
    }

    static Map<String, String> testParams(Map<String, String> all) {
        Map<String, String> params = new HashMap<>();
        all.forEach((k, v) -> {
            if (k.startsWith(TEST_PARAM_PREFIX)) params.put(k.substring(TEST_PARAM_PREFIX.length()), v);
        });
        return params;
    }

    private static ExecutionIdentity identity(String user, String password) {
        return new ExecutionIdentity(user, user, password, ExecutionMode.CALLER);
    }
}
