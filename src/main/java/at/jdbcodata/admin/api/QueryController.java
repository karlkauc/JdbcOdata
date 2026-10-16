package at.jdbcodata.admin.api;

import at.jdbcodata.admin.api.QueryDtos.AdHocTestRequest;
import at.jdbcodata.admin.api.QueryDtos.ColumnSettingRequest;
import at.jdbcodata.admin.api.QueryDtos.ColumnSettingsRequest;
import at.jdbcodata.admin.api.QueryDtos.GrantRequest;
import at.jdbcodata.admin.api.QueryDtos.GrantsRequest;
import at.jdbcodata.admin.api.QueryDtos.QueryRequest;
import at.jdbcodata.admin.api.QueryDtos.QueryResponse;
import at.jdbcodata.admin.api.QueryDtos.QueryVersionResponse;
import at.jdbcodata.admin.api.QueryDtos.SnapshotRequest;
import at.jdbcodata.admin.api.QueryDtos.TestRunResponse;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryGrant;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QueryTestService;
import at.jdbcodata.query.SchemaSnapshotService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/queries")
public class QueryController {

    private final QueryService queryService;
    private final SchemaSnapshotService snapshotService;
    private final QueryTestService testService;

    public QueryController(QueryService queryService, SchemaSnapshotService snapshotService, QueryTestService testService) {
        this.queryService = queryService;
        this.snapshotService = snapshotService;
        this.testService = testService;
    }

    @GetMapping
    public List<QueryResponse> list() {
        return queryService.findAll().stream().map(QueryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public QueryResponse get(@PathVariable UUID id) {
        return QueryResponse.from(queryService.require(id));
    }

    @PostMapping
    public ResponseEntity<QueryResponse> create(@Valid @RequestBody QueryRequest request) {
        QueryResponse created = QueryResponse.from(queryService.create(request.toSpec()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public QueryResponse update(@PathVariable UUID id, @Valid @RequestBody QueryRequest request) {
        return QueryResponse.from(queryService.update(id, request.toSpec()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        queryService.delete(id);
    }

    @PostMapping("/{id}/snapshot")
    public QueryResponse snapshot(@PathVariable UUID id, @Valid @RequestBody SnapshotRequest request) {
        QueryDefinition query = queryService.require(id);
        return QueryResponse.from(snapshotService.snapshot(query, identity(request.username(), request.password()),
                orEmpty(request.parameters())));
    }

    @PostMapping("/{id}/test")
    public TestRunResponse test(@PathVariable UUID id, @Valid @RequestBody SnapshotRequest request) {
        QueryDefinition query = queryService.require(id);
        return TestRunResponse.from(testService.test(query, identity(request.username(), request.password()),
                orEmpty(request.parameters())));
    }

    @PostMapping("/test")
    public TestRunResponse testAdHoc(@Valid @RequestBody AdHocTestRequest request) {
        QueryDefinition query = queryService.transientDefinition(request.query().toSpec());
        return TestRunResponse.from(testService.test(query, identity(request.username(), request.password()),
                orEmpty(request.parameters())));
    }

    @GetMapping("/{id}/versions")
    public List<QueryVersionResponse> versions(@PathVariable UUID id) {
        return queryService.versions(id).stream().map(QueryVersionResponse::from).toList();
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public QueryResponse restore(@PathVariable UUID id, @PathVariable int version) {
        return QueryResponse.from(queryService.restoreVersion(id, version));
    }

    @PutMapping("/{id}/columns")
    public QueryResponse columns(@PathVariable UUID id, @Valid @RequestBody ColumnSettingsRequest request) {
        return QueryResponse.from(queryService.updateColumnSettings(id,
                request.columns().stream().map(ColumnSettingRequest::toRecord).toList()));
    }

    @GetMapping("/{id}/grants")
    public List<QueryGrant> grants(@PathVariable UUID id) {
        queryService.require(id);
        return queryService.grants(id);
    }

    @PutMapping("/{id}/grants")
    public List<QueryGrant> setGrants(@PathVariable UUID id, @Valid @RequestBody GrantsRequest request) {
        return queryService.setGrants(id, request.grants().stream().map(GrantRequest::toSpec).toList());
    }

    private static ExecutionIdentity identity(String username, String password) {
        return new ExecutionIdentity(username, username, password, ExecutionMode.CALLER);
    }

    private static Map<String, String> orEmpty(Map<String, String> m) {
        return m == null ? Map.of() : m;
    }
}
