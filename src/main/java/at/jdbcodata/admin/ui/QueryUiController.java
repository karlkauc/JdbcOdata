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
import at.jdbcodata.query.QueryGrant;
import at.jdbcodata.query.SchemaSnapshotService;
import at.jdbcodata.stats.StatsService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    static final int PAGE_SIZE = 25;
    /** Days of the usage column in the list and the editor. */
    static final int USAGE_DAYS = 14;

    /** Row of the query list with its calls of the last {@link #USAGE_DAYS} days (null = never called). */
    public record QueryRow(QueryDefinition query, StatsService.QueryUsage usage) {
    }

    private final QueryService queryService;
    private final DatasourceService datasourceService;
    private final QueryTestService testService;
    private final SchemaSnapshotService snapshotService;
    private final FixedCredentialService fixedCredentials;
    private final ServiceUserService serviceUsers;
    private final StatsService stats;

    public QueryUiController(QueryService queryService, DatasourceService datasourceService,
                             QueryTestService testService, SchemaSnapshotService snapshotService,
                             FixedCredentialService fixedCredentials, ServiceUserService serviceUsers, StatsService stats) {
        this.queryService = queryService;
        this.datasourceService = datasourceService;
        this.testService = testService;
        this.snapshotService = snapshotService;
        this.fixedCredentials = fixedCredentials;
        this.serviceUsers = serviceUsers;
        this.stats = stats;
    }

    /**
     * List with search ({@code q} over name, description, datasource and SQL) and filters: {@code ds} datasource
     * name, {@code mode} CALLER/FIXED, {@code status} enabled/disabled, {@code snapshot} present/missing, {@code usage}
     * unused (no call for {@value HomeController#UNUSED_DAYS} days) or used.
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String ds,
                       @RequestParam(defaultValue = "") String mode, @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "") String snapshot, @RequestParam(defaultValue = "") String usage,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        List<QueryDefinition> all = queryService.findAll();
        Map<UUID, StatsService.QueryUsage> calls = stats.usageByQuery(USAGE_DAYS, LocalDate.now());
        Instant unusedCutoff = Instant.now().minus(Duration.ofDays(HomeController.UNUSED_DAYS));
        String needle = q.strip().toLowerCase(Locale.ROOT);
        List<QueryRow> rows = all.stream()
                .filter(x -> needle.isEmpty() || contains(needle, x.name(), x.description(), x.datasourceName(), x.sqlText()))
                .filter(x -> ds.isEmpty() || ds.equals(x.datasourceName()))
                .filter(x -> mode.isEmpty() || mode.equals(x.executionMode().name()))
                .filter(x -> status.isEmpty() || (status.equals("enabled") == x.enabled()))
                .filter(x -> snapshot.isEmpty() || (snapshot.equals("present") == x.hasSnapshot()))
                .filter(x -> usage.isEmpty() || usage.equals("unused") == unused(calls.get(x.id()), unusedCutoff))
                .map(x -> new QueryRow(x, calls.get(x.id())))
                .toList();
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));
        model.addAttribute("rows", rows.subList((current - 1) * PAGE_SIZE, Math.min(rows.size(), current * PAGE_SIZE)));
        model.addAttribute("total", all.size());
        model.addAttribute("matches", rows.size());
        model.addAttribute("page", current);
        model.addAttribute("pages", pages);
        model.addAttribute("pageFrom", rows.isEmpty() ? 0 : (current - 1) * PAGE_SIZE + 1);
        model.addAttribute("pageTo", Math.min(rows.size(), current * PAGE_SIZE));
        model.addAttribute("datasourceNames", all.stream().map(QueryDefinition::datasourceName).distinct().sorted().toList());
        model.addAttribute("q", q);
        model.addAttribute("ds", ds);
        model.addAttribute("mode", mode);
        model.addAttribute("status", status);
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("usage", usage);
        model.addAttribute("filtered", !(q.isBlank() && ds.isEmpty() && mode.isEmpty() && status.isEmpty() && snapshot.isEmpty() && usage.isEmpty()));
        model.addAttribute("usageDays", USAGE_DAYS);
        model.addAttribute("feedUrl", ServletUriComponentsBuilder.fromCurrentContextPath().path("/odata/").toUriString());
        return "admin/queries/list";
    }

    private static boolean unused(StatsService.QueryUsage u, Instant cutoff) {
        return u == null || u.lastCall().isBefore(cutoff);
    }

    private static boolean contains(String needle, String... fields) {
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
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
            redirect.addFlashAttribute("message", "Query '" + created.name() + "' created. Now take the schema snapshot.");
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

    /**
     * Saves the whole editor: the definition and, when the form carries them ({@code columnsPresent},
     * {@code grantsPresent}), the column settings and grants. Column settings are skipped when the update dropped the
     * snapshot (changed SQL or datasource); unchanged columns and grants are not written again.
     */
    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") QueryForm form, BindingResult binding,
                         @RequestParam Map<String, String> all, Model model, RedirectAttributes redirect) {
        QueryDefinition q = queryService.require(id);
        if (binding.hasErrors()) {
            return editor(model, form, q);
        }
        QueryDefinition updated;
        try {
            updated = queryService.update(id, form.toSpec());
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, q);
        }
        List<String> problems = new ArrayList<>();
        if (all.containsKey("columnsPresent") && updated.hasSnapshot()) {
            try {
                List<ColumnSetting> settings = columnSettings(updated, all);
                if (columnsChanged(updated, settings)) {
                    queryService.updateColumnSettings(id, settings);
                }
            } catch (InvalidRequestException e) {
                problems.add("columns: " + e.getMessage());
            }
        }
        if (all.containsKey("grantsPresent")) {
            List<QueryService.GrantSpec> specs = grantSpecs(all);
            if (grantsChanged(queryService.grants(id), specs)) {
                try {
                    queryService.setGrants(id, specs);
                } catch (InvalidRequestException | NotFoundException e) {
                    problems.add("grants: " + e.getMessage());
                }
            }
        }
        String saved = updated.hasSnapshot()
                ? "Query saved (version " + updated.version() + ")."
                : "Query saved (version " + updated.version() + "). The schema snapshot must be taken again.";
        if (problems.isEmpty()) {
            redirect.addFlashAttribute("message", saved);
        } else {
            redirect.addFlashAttribute("error", saved + " Not applied – " + String.join("; ", problems));
        }
        return "redirect:/admin/queries/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        QueryDefinition q = queryService.require(id);
        queryService.delete(id);
        redirect.addFlashAttribute("message", "Query '" + q.name() + "' deleted.");
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
            model.addAttribute("snapshotMessage", "Snapshot taken: " + q.columns().size() + " columns.");
        } catch (InvalidRequestException | DbAuthenticationException | DbExecutionException e) {
            model.addAttribute("snapshotError", e.getMessage());
        }
        model.addAttribute("query", q);
        return "admin/queries/columns";
    }

    /**
     * Saves key/visible flags and the published form; field names are {@code key_<property>},
     * {@code visible_<property>} and {@code collection_<property>}.
     */
    @PostMapping("/{id}/columns")
    public String columns(@PathVariable UUID id, @RequestParam Map<String, String> all, RedirectAttributes redirect) {
        QueryDefinition q = queryService.require(id);
        try {
            queryService.updateColumnSettings(id, columnSettings(q, all));
        } catch (at.jdbcodata.common.InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/queries/" + id;
        }
        redirect.addFlashAttribute("message", "Column settings saved.");
        return "redirect:/admin/queries/" + id;
    }

    /** Column settings from the fields {@code key_}, {@code visible_}, {@code collection_}, {@code textcopy_}, {@code structure_}. */
    static List<ColumnSetting> columnSettings(QueryDefinition q, Map<String, String> all) {
        List<ColumnSetting> settings = new ArrayList<>();
        for (QueryColumn c : q.columns()) {
            List<at.jdbcodata.query.ComplexField> structure = null;
            String text = all.get("structure_" + c.propertyName());
            if (text != null && !normalizeLines(text).equals(normalizeLines(c.structureLines()))) {
                try {
                    structure = parseStructure(text);
                } catch (InvalidRequestException e) {
                    throw new InvalidRequestException(c.propertyName() + ": " + e.getMessage());
                }
            }
            settings.add(new ColumnSetting(c.propertyName(), all.containsKey("key_" + c.propertyName()),
                    all.containsKey("visible_" + c.propertyName()),
                    at.jdbcodata.query.CollectionKind.parse(all.get("collection_" + c.propertyName())),
                    c.structured() ? Boolean.valueOf(all.containsKey("textcopy_" + c.propertyName())) : null, structure));
        }
        return settings;
    }

    static boolean columnsChanged(QueryDefinition q, List<ColumnSetting> settings) {
        Map<String, ColumnSetting> byName = settings.stream().collect(Collectors.toMap(ColumnSetting::propertyName, s -> s, (a, b) -> b));
        for (QueryColumn c : q.columns()) {
            ColumnSetting s = byName.get(c.propertyName());
            if (s == null) continue;
            if (s.structure() != null || s.key() != c.key() || (s.visible() || s.key()) != c.visible()
                    || (s.collection() != null && s.collection() != c.collection())
                    || (s.textCopy() != null && s.textCopy() != c.textCopy())) {
                return true;
            }
        }
        return false;
    }

    static List<at.jdbcodata.query.ComplexField> parseStructure(String text) {
        List<at.jdbcodata.query.ComplexField> fields = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            String[] parts = l.split("\\s+", 3);
            if (parts.length < 2) throw new at.jdbcodata.common.InvalidRequestException("Line '" + l + "': expected 'Name Type [JSON key]'");
            String type = parts[1];
            boolean collection = type.startsWith("Collection(") && type.endsWith(")");
            if (collection) type = type.substring("Collection(".length(), type.length() - 1);
            if (!type.startsWith("Edm.")) type = "Edm." + type;
            fields.add(new at.jdbcodata.query.ComplexField(parts.length > 2 ? parts[2].strip() : parts[0], parts[0], type, collection));
        }
        return fields;
    }

    private static String normalizeLines(String text) {
        return String.join("\n", text.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
    }

    /** Grants of a FIXED query: checkbox {@code all}, {@code user_<id>} and {@code group_<id>}. */
    @PostMapping("/{id}/grants")
    public String grants(@PathVariable UUID id, @RequestParam Map<String, String> all, RedirectAttributes redirect) {
        queryService.require(id);
        List<QueryService.GrantSpec> specs = grantSpecs(all);
        try {
            queryService.setGrants(id, specs);
            redirect.addFlashAttribute("message", "Grants saved (" + specs.size() + ").");
        } catch (InvalidRequestException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/queries/" + id;
    }

    static List<QueryService.GrantSpec> grantSpecs(Map<String, String> all) {
        List<QueryService.GrantSpec> specs = new ArrayList<>();
        if (all.containsKey("all")) {
            specs.add(new QueryService.GrantSpec(PrincipalType.ALL_AUTHENTICATED, null));
        }
        all.keySet().forEach(k -> {
            if (k.startsWith("user_")) specs.add(new QueryService.GrantSpec(PrincipalType.USER, UUID.fromString(k.substring(5))));
            if (k.startsWith("group_")) specs.add(new QueryService.GrantSpec(PrincipalType.GROUP, UUID.fromString(k.substring(6))));
        });
        return specs;
    }

    static boolean grantsChanged(List<QueryGrant> current, List<QueryService.GrantSpec> specs) {
        Set<String> now = current.stream().map(g -> g.principalType() + ":" + g.principalId()).collect(Collectors.toSet());
        Set<String> wanted = new HashSet<>();
        specs.forEach(g -> wanted.add(g.principalType() + ":" + g.principalId()));
        return !now.equals(wanted);
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public String restore(@PathVariable UUID id, @PathVariable int version, RedirectAttributes redirect) {
        try {
            QueryDefinition restored = queryService.restoreVersion(id, version);
            redirect.addFlashAttribute("message", "Version " + version + " restored as version "
                    + restored.version() + ". The schema snapshot must be taken again.");
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
        model.addAttribute("usage", query == null ? null : stats.usageByQuery(USAGE_DAYS, LocalDate.now()).get(query.id()));
        model.addAttribute("usageDays", USAGE_DAYS);
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
