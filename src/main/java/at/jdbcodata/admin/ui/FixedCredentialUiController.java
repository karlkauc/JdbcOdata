package at.jdbcodata.admin.ui;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceUserService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/accounts")
public class FixedCredentialUiController {

    private final FixedCredentialService service;
    private final DatasourceService datasourceService;

    public FixedCredentialUiController(FixedCredentialService service, DatasourceService datasourceService) {
        this.service = service;
        this.datasourceService = datasourceService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("accounts", service.findAll());
        return "admin/accounts/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        return editor(model, new FixedCredentialForm(), null);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") FixedCredentialForm form, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return editor(model, form, null);
        }
        try {
            FixedCredential created = service.create(form.toSpec());
            redirect.addFlashAttribute("message", "Technischer Account '" + created.name() + "' angelegt.");
            return "redirect:/admin/accounts/" + created.id();
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, null);
        }
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable UUID id, Model model) {
        FixedCredential c = service.require(id);
        return editor(model, FixedCredentialForm.from(c), c);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") FixedCredentialForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        FixedCredential c = service.require(id);
        if (binding.hasErrors()) {
            return editor(model, form, c);
        }
        try {
            service.update(id, form.toSpec());
            redirect.addFlashAttribute("message", "Technischer Account gespeichert.");
            return "redirect:/admin/accounts/" + id;
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, c);
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            service.delete(id);
            redirect.addFlashAttribute("message", "Technischer Account gelöscht.");
            return "redirect:/admin/accounts";
        } catch (ConflictException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/accounts/" + id;
        }
    }

    /** htmx: connection test with the stored (decrypted) login. */
    @PostMapping("/{id}/test")
    public String test(@PathVariable UUID id, Model model) {
        FixedCredential c = service.require(id);
        ServiceUserService.DbLogin login = service.resolveLogin(id);
        model.addAttribute("result", datasourceService.testConnection(c.datasourceId(), login.username(), login.password()));
        return "admin/datasources/test-result";
    }

    private String editor(Model model, FixedCredentialForm form, FixedCredential account) {
        model.addAttribute("form", form);
        model.addAttribute("account", account);
        model.addAttribute("datasources", datasourceService.findAll());
        return "admin/accounts/form";
    }
}
