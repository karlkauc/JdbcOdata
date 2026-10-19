package at.jdbcodata.admin.ui;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.ServiceUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class ServiceUserUiController {

    private final ServiceUserService service;
    private final DatasourceService datasourceService;

    public ServiceUserUiController(ServiceUserService service, DatasourceService datasourceService) {
        this.service = service;
        this.datasourceService = datasourceService;
    }

    @GetMapping("/users")
    public String list(Model model) {
        model.addAttribute("users", service.findAll());
        model.addAttribute("prefix", ServiceUserService.RECOMMENDED_PREFIX);
        return "admin/users/list";
    }

    @GetMapping("/users/new")
    public String createForm(Model model) {
        ServiceUserForm form = new ServiceUserForm();
        form.setUsername(ServiceUserService.RECOMMENDED_PREFIX);
        return editor(model, form, null);
    }

    @PostMapping("/users")
    public String create(@Valid @ModelAttribute("form") ServiceUserForm form, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return editor(model, form, null);
        }
        try {
            ServiceUser created = service.create(form.toSpec());
            redirect.addFlashAttribute("message", "Service user '" + created.username() + "' created.");
            return "redirect:/admin/users/" + created.id();
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, null);
        }
    }

    @GetMapping("/users/{id}")
    public String editForm(@PathVariable UUID id, Model model) {
        ServiceUser user = service.require(id);
        return editor(model, ServiceUserForm.from(user), user);
    }

    @PostMapping("/users/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") ServiceUserForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        ServiceUser user = service.require(id);
        if (binding.hasErrors()) {
            return editor(model, form, user);
        }
        try {
            service.update(id, form.toSpec());
            redirect.addFlashAttribute("message", "Service user saved.");
            return "redirect:/admin/users/" + id;
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return editor(model, form, user);
        }
    }

    @PostMapping("/users/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        ServiceUser user = service.require(id);
        service.delete(id);
        redirect.addFlashAttribute("message", "Service user '" + user.username() + "' deleted.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/credentials")
    public String storeCredential(@PathVariable UUID id, @RequestParam UUID datasourceId, @RequestParam String dbUsername,
                                  @RequestParam(defaultValue = "") String dbPassword, RedirectAttributes redirect) {
        try {
            service.storeCredential(id, datasourceId, dbUsername, dbPassword);
            redirect.addFlashAttribute("message", "Database credentials stored.");
        } catch (InvalidRequestException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/users/{id}/credentials/{datasourceId}/delete")
    public String removeCredential(@PathVariable UUID id, @PathVariable UUID datasourceId, RedirectAttributes redirect) {
        try {
            service.removeCredential(id, datasourceId);
            redirect.addFlashAttribute("message", "Database credentials removed.");
        } catch (NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @GetMapping("/groups")
    public String groups(Model model) {
        model.addAttribute("groups", service.findAllGroups());
        model.addAttribute("users", service.findAll());
        return "admin/users/groups";
    }

    @PostMapping("/groups")
    public String createGroup(@RequestParam String name, @RequestParam(required = false) String description,
                              RedirectAttributes redirect) {
        try {
            service.createGroup(name, description);
            redirect.addFlashAttribute("message", "Group '" + name + "' created.");
        } catch (ConflictException | InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }

    @PostMapping("/groups/{id}/delete")
    public String deleteGroup(@PathVariable UUID id, RedirectAttributes redirect) {
        service.deleteGroup(id);
        redirect.addFlashAttribute("message", "Group deleted.");
        return "redirect:/admin/groups";
    }

    private String editor(Model model, ServiceUserForm form, ServiceUser user) {
        model.addAttribute("form", form);
        model.addAttribute("user", user);
        model.addAttribute("groups", service.findAllGroups());
        model.addAttribute("datasources", datasourceService.findAll());
        model.addAttribute("credentials", user == null ? java.util.List.of() : service.credentialsOf(user.id()));
        return "admin/users/form";
    }
}
