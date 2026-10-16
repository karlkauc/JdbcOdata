package at.jdbcodata.query;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.DatasourceEntity;
import at.jdbcodata.datasource.DatasourceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryService {

    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,99}");

    private final QueryDefinitionRepository repository;
    private final DatasourceRepository datasourceRepository;
    private final ApplicationEventPublisher events;

    public QueryService(QueryDefinitionRepository repository, DatasourceRepository datasourceRepository,
                        ApplicationEventPublisher events) {
        this.repository = repository;
        this.datasourceRepository = datasourceRepository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<QueryDefinition> findAll() {
        return repository.findAllByOrderByName().stream().map(QueryDefinitionEntity::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public List<QueryDefinition> findAllEnabled() {
        return repository.findAllByEnabledTrueOrderByName().stream().map(QueryDefinitionEntity::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<QueryDefinition> findById(UUID id) {
        return repository.findById(id).map(QueryDefinitionEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public Optional<QueryDefinition> findEnabledByName(String name) {
        return repository.findByName(name).filter(QueryDefinitionEntity::isEnabled).map(QueryDefinitionEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public QueryDefinition require(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Query " + id + " not found"));
    }

    @Transactional
    public QueryDefinition create(QuerySpec spec) {
        validate(spec);
        repository.findByName(spec.name()).ifPresent(q -> {
            throw new ConflictException("Query name '" + spec.name() + "' already exists");
        });
        QueryDefinitionEntity e = new QueryDefinitionEntity();
        e.setCreatedAt(Instant.now());
        apply(e, spec);
        QueryDefinition created = repository.save(e).toRecord();
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return created;
    }

    /** Updates the definition. A changed SQL text drops the schema snapshot and bumps the version. */
    @Transactional
    public QueryDefinition update(UUID id, QuerySpec spec) {
        validate(spec);
        QueryDefinitionEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Query " + id + " not found"));
        repository.findByName(spec.name()).filter(o -> !o.getId().equals(id)).ifPresent(q -> {
            throw new ConflictException("Query name '" + spec.name() + "' already exists");
        });
        boolean sqlChanged = !normalize(e.getSqlText()).equals(normalize(spec.sqlText()));
        boolean datasourceChanged = !e.getDatasource().getId().equals(spec.datasourceId());
        // Remove children first and flush, otherwise Hibernate inserts the replacements before deleting the
        // old rows and violates the unique constraints on (query_id, name).
        e.getParameters().clear();
        if (sqlChanged || datasourceChanged) {
            e.getColumns().clear();
            e.setVersion(e.getVersion() + 1);
        }
        repository.flush();
        apply(e, spec);
        QueryDefinition updated = repository.save(e).toRecord();
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return updated;
    }

    @Transactional
    public QueryDefinition upsertByName(QuerySpec spec) {
        return repository.findByName(spec.name())
                .map(e -> update(e.getId(), spec))
                .orElseGet(() -> create(spec));
    }

    @Transactional
    public QueryDefinition replaceSnapshot(UUID id, List<QueryColumn> columns) {
        QueryDefinitionEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Query " + id + " not found"));
        e.getColumns().clear();
        repository.flush();
        e.replaceColumns(columns);
        e.setUpdatedAt(Instant.now());
        QueryDefinition updated = repository.save(e).toRecord();
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        QueryDefinitionEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Query " + id + " not found"));
        repository.delete(e);
        events.publishEvent(new QueryDefinitionsChangedEvent());
    }

    private void validate(QuerySpec spec) {
        if (spec.name() == null || !NAME_PATTERN.matcher(spec.name()).matches()) {
            throw new InvalidRequestException("Query name must match [A-Za-z][A-Za-z0-9_]* and be at most 100 characters");
        }
        if (spec.sqlText() == null || spec.sqlText().isBlank()) {
            throw new InvalidRequestException("SQL text must not be empty");
        }
        if (spec.datasourceId() == null) {
            throw new InvalidRequestException("datasourceId is required");
        }
        NamedParameterSql parsed = NamedParameterSql.parse(spec.sqlText());
        List<QueryParameter> params = spec.parameters() == null ? List.of() : spec.parameters();
        var declared = params.stream().map(QueryParameter::name).toList();
        for (String p : parsed.distinctNames()) {
            if (!declared.contains(p)) {
                throw new InvalidRequestException("SQL references undeclared parameter ':" + p + "'");
            }
        }
        for (QueryParameter p : params) {
            if (p.name() == null || !NAME_PATTERN.matcher(p.name()).matches()) {
                throw new InvalidRequestException("Parameter name '" + p.name() + "' is invalid");
            }
            if (p.type() == null) {
                throw new InvalidRequestException("Parameter '" + p.name() + "' has no type");
            }
            if (p.defaultValue() != null && !p.defaultValue().isBlank()) {
                ParameterBinder.convert(p, p.defaultValue()); // throws InvalidRequestException if not parseable
            }
        }
    }

    private void apply(QueryDefinitionEntity e, QuerySpec s) {
        DatasourceEntity ds = datasourceRepository.findById(s.datasourceId())
                .orElseThrow(() -> new NotFoundException("Datasource " + s.datasourceId() + " not found"));
        e.setName(s.name());
        e.setDescription(s.description());
        e.setDatasource(ds);
        e.setSqlText(s.sqlText());
        if (s.executionMode() != null) e.setExecutionMode(s.executionMode());
        if (s.cacheEnabled() != null) e.setCacheEnabled(s.cacheEnabled());
        if (s.cacheTtlSec() != null) e.setCacheTtlSec(s.cacheTtlSec());
        e.setTimeoutSec(s.timeoutSec());
        e.setMaxRows(s.maxRows());
        if (s.keyMode() != null) e.setKeyMode(s.keyMode());
        if (s.enabled() != null) e.setEnabled(s.enabled());
        e.replaceParameters(s.parameters() == null ? List.of() : s.parameters());
        e.setUpdatedAt(Instant.now());
    }

    private static String normalize(String sql) {
        return sql == null ? "" : sql.strip().replaceAll("\\s+", " ");
    }
}
