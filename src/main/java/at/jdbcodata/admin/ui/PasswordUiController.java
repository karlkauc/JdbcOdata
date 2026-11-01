package at.jdbcodata.admin.ui;

import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.admin.account.PasswordChangeFilter;
import at.jdbcodata.common.InvalidRequestException;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Own password of a local account, for VIEWER too. Identity provider users have none here. */
@Controller
public class PasswordUiController {

    private final AdminUserService admins;

    public PasswordUiController(AdminUserService admins) {
        this.admins = admins;
    }

    @GetMapping(PasswordChangeFilter.PAGE)
    public String form(Principal principal, Model model) {
        if (!admins.isLocal(principal.getName())) return "redirect:/admin";
        model.addAttribute("required", admins.mustChangePassword(principal.getName()));
        return "admin/password";
    }

    @PostMapping(PasswordChangeFilter.PAGE)
    public String change(Principal principal, @RequestParam String current, @RequestParam String password,
                         @RequestParam String confirm, RedirectAttributes redirect) {
        if (!admins.isLocal(principal.getName())) return "redirect:/admin";
        try {
            if (!password.equals(confirm)) throw new InvalidRequestException("The new passwords do not match");
            admins.changeOwnPassword(principal.getName(), current, password);
        } catch (InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:" + PasswordChangeFilter.PAGE;
        }
        redirect.addFlashAttribute("message", "Password changed.");
        return "redirect:/admin";
    }
}
