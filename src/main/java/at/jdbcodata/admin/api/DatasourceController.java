package at.jdbcodata.admin.api;

import at.jdbcodata.admin.api.DatasourceDtos.ConnectionTestRequest;
import at.jdbcodata.admin.api.DatasourceDtos.DatasourceRequest;
import at.jdbcodata.admin.api.DatasourceDtos.DatasourceResponse;
import at.jdbcodata.datasource.ConnectionTestResult;
import at.jdbcodata.datasource.DatasourceService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/v1/datasources")
public class DatasourceController {

    private final DatasourceService service;

    public DatasourceController(DatasourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<DatasourceResponse> list() {
        return service.findAll().stream().map(DatasourceResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DatasourceResponse get(@PathVariable UUID id) {
        return DatasourceResponse.from(service.require(id));
    }

    @PostMapping
    public ResponseEntity<DatasourceResponse> create(@Valid @RequestBody DatasourceRequest request) {
        DatasourceResponse created = DatasourceResponse.from(service.create(request.toSpec()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public DatasourceResponse update(@PathVariable UUID id, @Valid @RequestBody DatasourceRequest request) {
        return DatasourceResponse.from(service.update(id, request.toSpec()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    public ConnectionTestResult test(@PathVariable UUID id, @Valid @RequestBody ConnectionTestRequest request) {
        return service.testConnection(id, request.username(), request.password());
    }
}
