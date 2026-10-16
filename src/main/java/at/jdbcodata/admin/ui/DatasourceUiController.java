package at.jdbcodata.admin.ui;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import jakarta.validation.Valid;
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

@Controller
@RequestMapping("/admin/datasources")
public class DatasourceUiController {

    private final DatasourceService service;

    public DatasourceUiController(DatasourceService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("datasources", service.findAll());
        return "admin/datasources/list";
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
            redirect.addFlashAttribute("message", "Datenquelle '" + created.name() + "' angelegt.");
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
            redirect.addFlashAttribute("message", "Datenquelle gespeichert.");
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
            redirect.addFlashAttribute("message", "Datenquelle gelöscht.");
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
