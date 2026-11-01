package at.jdbcodata.admin.ui;

import at.jdbcodata.admin.account.AdminRole;
import at.jdbcodata.apikey.ApiKey;
import at.jdbcodata.apikey.ApiKeyService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserService;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/api-keys")
public class ApiKeyUiController {

    private static final String ADMIN_TARGET_PREFIX = "role:";

    /** One table row: the key and what it gives access to. */
    public record Row(ApiKey key, String target, String status) {
    }

    private final ApiKeyService keys;
    private final ServiceUserService serviceUsers;

    public ApiKeyUiController(ApiKeyService keys, ServiceUserService serviceUsers) {
        this.keys = keys;
        this.serviceUsers = serviceUsers;
    }

    @GetMapping
    public String list(Model model) {
        var users = serviceUsers.findAll().stream().collect(Collectors.toMap(ServiceUser::id, Function.identity()));
        Instant now = Instant.now();
        model.addAttribute("keys", keys.findAll().stream().map(k -> new Row(k,
                k.adminRole() != null ? "REST API, " + k.adminRole() : "Feed as " + users.get(k.serviceUserId()).username(),
                k.revokedAt() != null ? "revoked" : k.activeAt(now) ? "active" : "expired")).toList());
        model.addAttribute("serviceUsers", users.values().stream().filter(u -> !u.forcePassthrough()).toList());
        model.addAttribute("roles", AdminRole.values());
        return "admin/api-keys";
    }

    /** {@code target} is a service user id or {@code role:<ADMIN|VIEWER>}. */
    @PostMapping
    public String create(@RequestParam String name, @RequestParam String target, @RequestParam int expiresInDays,
                         RedirectAttributes redirect) {
        try {
            boolean role = target.startsWith(ADMIN_TARGET_PREFIX);
            redirect.addFlashAttribute("created", keys.create(name, role ? null : UUID.fromString(target),
                    role ? AdminRole.valueOf(target.substring(ADMIN_TARGET_PREFIX.length())) : null, expiresInDays));
        } catch (ConflictException | InvalidRequestException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/api-keys";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable UUID id, RedirectAttributes redirect) {
        keys.revoke(id);
        redirect.addFlashAttribute("message", "API key revoked.");
        return "redirect:/admin/api-keys";
    }
}
