package at.jdbcodata.admin.api;

import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
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

/** Technical database accounts for FIXED-mode queries. The password is write-only. */
@RestController
@RequestMapping("/api/v1/fixed-credentials")
public class FixedCredentialController {

    record FixedCredentialRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description,
                                  @NotNull UUID datasourceId, @NotBlank @Size(max = 200) String dbUsername, String dbPassword) {
        FixedCredentialService.FixedCredentialSpec toSpec() {
            return new FixedCredentialService.FixedCredentialSpec(name, description, datasourceId, dbUsername, dbPassword);
        }
    }

    private final FixedCredentialService service;

    public FixedCredentialController(FixedCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public List<FixedCredential> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public FixedCredential get(@PathVariable UUID id) {
        return service.require(id);
    }

    @PostMapping
    public ResponseEntity<FixedCredential> create(@Valid @RequestBody FixedCredentialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.toSpec()));
    }

    @PutMapping("/{id}")
    public FixedCredential update(@PathVariable UUID id, @Valid @RequestBody FixedCredentialRequest request) {
        return service.update(id, request.toSpec());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
