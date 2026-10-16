package at.jdbcodata.admin.api;

import at.jdbcodata.admin.api.QueryDtos.QueryResponse;
import at.jdbcodata.admin.api.QueryDtos.SnapshotRequest;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.SchemaSnapshotService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read access to query definitions plus the schema snapshot. Full CRUD follows in milestone M2. */
@RestController
@RequestMapping("/api/v1/queries")
public class QueryController {

    private final QueryService queryService;
    private final SchemaSnapshotService snapshotService;

    public QueryController(QueryService queryService, SchemaSnapshotService snapshotService) {
        this.queryService = queryService;
        this.snapshotService = snapshotService;
    }

    @GetMapping
    public List<QueryResponse> list() {
        return queryService.findAll().stream().map(QueryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public QueryResponse get(@PathVariable UUID id) {
        return QueryResponse.from(queryService.require(id));
    }

    @PostMapping("/{id}/snapshot")
    public QueryResponse snapshot(@PathVariable UUID id, @Valid @RequestBody SnapshotRequest request) {
        QueryDefinition query = queryService.require(id);
        ExecutionIdentity identity = new ExecutionIdentity(request.username(), request.username(), request.password(),
                ExecutionMode.CALLER);
        Map<String, String> params = request.parameters() == null ? Map.of() : request.parameters();
        return QueryResponse.from(snapshotService.snapshot(query, identity, params));
    }
}
