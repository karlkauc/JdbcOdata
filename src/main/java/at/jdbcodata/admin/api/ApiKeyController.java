package at.jdbcodata.admin.api;

import at.jdbcodata.admin.account.AdminRole;
import at.jdbcodata.apikey.ApiKey;
import at.jdbcodata.apikey.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API keys. The secret appears only in the answer to the POST. */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    record ApiKeyRequest(@NotBlank String name, UUID serviceUserId, AdminRole adminRole, int expiresInDays) {
    }

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiKey> list() {
        return service.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.Created create(@Valid @RequestBody ApiKeyRequest request) {
        return service.create(request.name(), request.serviceUserId(), request.adminRole(), request.expiresInDays());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        service.revoke(id);
    }
}
