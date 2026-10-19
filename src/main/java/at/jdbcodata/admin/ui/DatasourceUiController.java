package at.jdbcodata.admin.ui;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.health.DatasourceHealthService;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
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

@Controller
@RequestMapping("/admin/datasources")
public class DatasourceUiController {

    private final DatasourceService service;
    private final DatasourceHealthService health;

    public DatasourceUiController(DatasourceService service, DatasourceHealthService health) {
        this.service = service;
        this.health = health;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("datasources", service.findAll());
        model.addAttribute("health", health.all().stream()
                .collect(Collectors.toMap(DatasourceHealthService.Health::datasourceId, Function.identity())));
        return "admin/datasources/list";
    }

    /** Repeats the active check with the technical account; without one the admin is sent to the manual test. */
    @PostMapping("/{id}/health")
    public String recheck(@PathVariable UUID id, RedirectAttributes redirect) {
        Datasource ds = service.require(id);
        var result = health.check(id);
        if (result.isEmpty()) {
            redirect.addFlashAttribute("error", "\"" + ds.name() + "\" has no technical account. "
                    + "Test the connection below with your own credentials.");
            return "redirect:/admin/datasources/" + id;
        }
        var h = result.get();
        if (h.state() == DatasourceHealthService.State.OK) {
            redirect.addFlashAttribute("message", "Datasource \"" + ds.name() + "\" is reachable (" + h.latencyMs() + " ms).");
        } else {
            redirect.addFlashAttribute("error", "Datasource \"" + ds.name() + "\" is unreachable: " + h.detail());
        }
        return "redirect:/admin";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new DatasourceForm());
        model.addAttribute("datasource", null);
        return "admin/datasources/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") DatasourceForm form, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("datasource", null);
            return "admin/datasources/form";
        }
        try {
            Datasource created = service.create(form.toSpec());
            redirect.addFlashAttribute("message", "Datasource '" + created.name() + "' created.");
            return "redirect:/admin/datasources/" + created.id();
        } catch (ConflictException | InvalidRequestException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("datasource", null);
            return "admin/datasources/form";
        }
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable UUID id, Model model) {
        Datasource ds = service.require(id);
        model.addAttribute("form", DatasourceForm.from(ds));
        model.addAttribute("datasource", ds);
        return "admin/datasources/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") DatasourceForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        Datasource ds = service.require(id);
        if (binding.hasErrors()) {
            model.addAttribute("datasource", ds);
            return "admin/datasources/form";
        }
        try {
            service.update(id, form.toSpec());
            redirect.addFlashAttribute("message", "Datasource saved.");
            return "redirect:/admin/datasources/" + id;
        } catch (ConflictException | InvalidRequestException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("datasource", ds);
            return "admin/datasources/form";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            service.delete(id);
            redirect.addFlashAttribute("message", "Datasource deleted.");
            return "redirect:/admin/datasources";
        } catch (ConflictException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/datasources/" + id;
        }
    }

    /** htmx: connection test with credentials typed into the form; returns a fragment. */
    @PostMapping("/{id}/test")
    public String test(@PathVariable UUID id, @RequestParam String dbUser, @RequestParam(defaultValue = "") String dbPassword,
                       Model model) {
        model.addAttribute("result", service.testConnection(id, dbUser, dbPassword));
        return "admin/datasources/test-result";
    }
}
