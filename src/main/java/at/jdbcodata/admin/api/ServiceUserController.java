package at.jdbcodata.admin.api;

import at.jdbcodata.identity.ServiceGroup;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.identity.StoredCredential;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service users, their groups and stored DB credentials. Passwords are write-only. */
@RestController
@RequestMapping("/api/v1")
public class ServiceUserController {

    record ServiceUserRequest(@NotBlank @Size(max = 100) String username, @Size(max = 200) String displayName,
                              String password, Boolean forcePassthrough, Boolean enabled, List<UUID> groupIds) {
        ServiceUserService.ServiceUserSpec toSpec() {
            return new ServiceUserService.ServiceUserSpec(username, displayName, password, forcePassthrough, enabled, groupIds);
        }
    }

    record CredentialRequest(@NotBlank @Size(max = 200) String dbUsername, @NotNull String dbPassword) {
    }

    record GroupRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {
    }

    private final ServiceUserService service;

    public ServiceUserController(ServiceUserService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<ServiceUser> list() {
        return service.findAll();
    }

    @GetMapping("/users/{id}")
    public ServiceUser get(@PathVariable UUID id) {
        return service.require(id);
    }

    @PostMapping("/users")
    public ResponseEntity<ServiceUser> create(@Valid @RequestBody ServiceUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.toSpec()));
    }

    @PutMapping("/users/{id}")
    public ServiceUser update(@PathVariable UUID id, @Valid @RequestBody ServiceUserRequest request) {
        return service.update(id, request.toSpec());
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/users/{id}/credentials")
    public List<StoredCredential> credentials(@PathVariable UUID id) {
        service.require(id);
        return service.credentialsOf(id);
    }

    @PutMapping("/users/{id}/credentials/{datasourceId}")
    public StoredCredential storeCredential(@PathVariable UUID id, @PathVariable UUID datasourceId,
                                            @Valid @RequestBody CredentialRequest request) {
        return service.storeCredential(id, datasourceId, request.dbUsername(), request.dbPassword());
    }

    @DeleteMapping("/users/{id}/credentials/{datasourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCredential(@PathVariable UUID id, @PathVariable UUID datasourceId) {
        service.removeCredential(id, datasourceId);
    }

    @GetMapping("/groups")
    public List<ServiceGroup> groups() {
        return service.findAllGroups();
    }

    @PostMapping("/groups")
    public ResponseEntity<ServiceGroup> createGroup(@Valid @RequestBody GroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGroup(request.name(), request.description()));
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable UUID id) {
        service.deleteGroup(id);
    }
}
