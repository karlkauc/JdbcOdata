package at.jdbcodata.query;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.CurrentUser;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.DatasourceEntity;
import at.jdbcodata.datasource.DatasourceRepository;
import at.jdbcodata.identity.FixedCredentialEntity;
import at.jdbcodata.identity.FixedCredentialRepository;
import at.jdbcodata.identity.ServiceGroupRepository;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryService {

    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,99}");

    /** Input for {@link #setGrants}. */
    public record GrantSpec(PrincipalType principalType, UUID principalId) {
    }

    private final QueryDefinitionRepository repository;
    private final QueryVersionRepository versionRepository;
    private final QueryGrantRepository grantRepository;
    private final DatasourceRepository datasourceRepository;
    private final FixedCredentialRepository fixedCredentials;
    private final ServiceUserRepository serviceUsers;
    private final ServiceGroupRepository serviceGroups;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public QueryService(QueryDefinitionRepository repository, QueryVersionRepository versionRepository,
                        QueryGrantRepository grantRepository, DatasourceRepository datasourceRepository,
                        FixedCredentialRepository fixedCredentials, ServiceUserRepository serviceUsers,
                        ServiceGroupRepository serviceGroups, AuditService audit, ApplicationEventPublisher events) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.grantRepository = grantRepository;
        this.datasourceRepository = datasourceRepository;
        this.fixedCredentials = fixedCredentials;
        this.serviceUsers = serviceUsers;
        this.serviceGroups = serviceGroups;
        this.audit = audit;
        this.events = events;
    }

    // --- reads -------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<QueryDefinition> findAll() {
        return repository.findAllByOrderByName().stream().map(this::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public List<QueryDefinition> findAllEnabled() {
        return repository.findAllByEnabledTrueOrderByName().stream().map(this::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<QueryDefinition> findById(UUID id) {
        return repository.findById(id).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public Optional<QueryDefinition> findEnabledByName(String name) {
        return repository.findByName(name).filter(QueryDefinitionEntity::isEnabled).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public QueryDefinition require(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Query " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<QueryVersion> versions(UUID id) {
        requireEntity(id);
        return versionRepository.findAllByQueryIdOrderByVersionDesc(id).stream().map(QueryVersionEntity::toRecord).toList();
    }

    // --- grants ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<QueryGrant> grants(UUID queryId) {
        return grantRepository.findAllByQueryId(queryId).stream().map(this::toRecord).toList();
    }

    /** Replaces all grants of a FIXED-mode query. */
    @Transactional
    public List<QueryGrant> setGrants(UUID queryId, List<GrantSpec> specs) {
        QueryDefinitionEntity q = requireEntity(queryId);
        for (GrantSpec s : specs) {
            if (s.principalType() == null) throw new InvalidRequestException("principalType is required");
            if (s.principalType() == PrincipalType.USER && (s.principalId() == null || !serviceUsers.existsById(s.principalId()))) {
                throw new NotFoundException("Service user " + s.principalId() + " not found");
            }
            if (s.principalType() == PrincipalType.GROUP && (s.principalId() == null || !serviceGroups.existsById(s.principalId()))) {
                throw new NotFoundException("Group " + s.principalId() + " not found");
            }
        }
        grantRepository.deleteAllByQueryId(queryId);
        grantRepository.flush();
        String who = CurrentUser.name();
        for (GrantSpec s : specs) {
            UUID principal = s.principalType() == PrincipalType.ALL_AUTHENTICATED ? null : s.principalId();
            grantRepository.save(QueryGrantEntity.of(queryId, s.principalType(), principal, who));
        }
        audit.record("SET_GRANTS", "QUERY", queryId, q.getName(), Map.of("grants", specs.size()));
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return grants(queryId);
    }

    /** CALLER queries are visible to everyone; FIXED queries need a matching grant. */
    @Transactional(readOnly = true)
    public boolean isVisible(QueryDefinition query, ServiceUser serviceUser) {
        if (query.executionMode() != ExecutionMode.FIXED) return true;
        for (QueryGrantEntity g : grantRepository.findAllByQueryId(query.id())) {
            switch (g.getPrincipalType()) {
                case ALL_AUTHENTICATED -> {
                    return true;
                }
                case USER -> {
                    if (serviceUser != null && serviceUser.id().equals(g.getPrincipalId())) return true;
                }
                case GROUP -> {
                    if (serviceUser != null && serviceUser.inGroup(g.getPrincipalId())) return true;
                }
            }
        }
        return false;
    }

    // --- writes ------------------------------------------------------------------------------------------------

    @Transactional
    public QueryDefinition create(QuerySpec spec) {
        validate(spec);
        repository.findByName(spec.name()).ifPresent(q -> {
            throw new ConflictException("Query name '" + spec.name() + "' already exists");
        });
        QueryDefinitionEntity e = new QueryDefinitionEntity();
        e.setCreatedAt(Instant.now());
        apply(e, spec);
        e.setVersion(1);
        e = repository.saveAndFlush(e);
        versionRepository.save(QueryVersionEntity.of(e, CurrentUser.name(), "created"));
        QueryDefinition created = toRecord(e);
        audit.record("CREATE", "QUERY", created.id(), created.name(), Map.of("datasource", created.datasourceName(),
                "mode", created.executionMode().name()));
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return created;
    }

    @Transactional
    public QueryDefinition update(UUID id, QuerySpec spec) {
        return update(id, spec, null);
    }

    /**
     * Updates the definition. A change of SQL, datasource or parameters creates a new version; a changed SQL or
     * datasource additionally drops the schema snapshot.
     */
    @Transactional
    public QueryDefinition update(UUID id, QuerySpec spec, String comment) {
        validate(spec);
        QueryDefinitionEntity e = requireEntity(id);
        repository.findByName(spec.name()).filter(o -> !o.getId().equals(id)).ifPresent(q -> {
            throw new ConflictException("Query name '" + spec.name() + "' already exists");
        });
        List<QueryParameter> newParams = spec.parameters() == null ? List.of() : spec.parameters();
        boolean sqlChanged = !normalize(e.getSqlText()).equals(normalize(spec.sqlText()));
        boolean datasourceChanged = !e.getDatasource().getId().equals(spec.datasourceId());
        boolean paramsChanged = !e.getParameters().stream().map(QueryParameterEntity::toRecord).toList().equals(newParams);
        boolean newVersion = sqlChanged || datasourceChanged || paramsChanged;
        if (newVersion && !versionRepository.existsByQueryIdAndVersion(id, e.getVersion())) {
            // definitions created before the history existed: keep their current state as a version
            versionRepository.save(QueryVersionEntity.of(e, CurrentUser.SYSTEM, "recorded before change"));
        }
        // Remove children first and flush, otherwise Hibernate inserts the replacements before deleting the
        // old rows and violates the unique constraints on (query_id, name).
        e.getParameters().clear();
        if (sqlChanged || datasourceChanged) {
            e.getColumns().clear();
        }
        repository.flush();
        apply(e, spec);
        if (newVersion) {
            e.setVersion(e.getVersion() + 1);
        }
        e = repository.saveAndFlush(e);
        if (newVersion) {
            versionRepository.save(QueryVersionEntity.of(e, CurrentUser.name(), comment == null ? "updated" : comment));
        }
        QueryDefinition updated = toRecord(e);
        audit.record("UPDATE", "QUERY", updated.id(), updated.name(), Map.of("version", updated.version(),
                "newVersion", newVersion, "mode", updated.executionMode().name(), "enabled", updated.enabled()));
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return updated;
    }

    @Transactional
    public QueryDefinition upsertByName(QuerySpec spec) {
        return repository.findByName(spec.name())
                .map(e -> update(e.getId(), spec))
                .orElseGet(() -> create(spec));
    }

    /** Restores SQL, parameters and datasource of an older version as a new version. Other settings are kept. */
    @Transactional
    public QueryDefinition restoreVersion(UUID id, int version) {
        QueryDefinition current = require(id);
        QueryVersion v = versionRepository.findByQueryIdAndVersion(id, version).map(QueryVersionEntity::toRecord)
                .orElseThrow(() -> new NotFoundException("Version " + version + " of query " + id + " not found"));
        QuerySpec spec = new QuerySpec(current.name(), current.description(), v.datasourceId(), v.sqlText(),
                current.executionMode(), current.fixedCredentialId(), current.cacheEnabled(), current.cacheTtlSec(),
                current.timeoutSec(), current.maxRows(), current.keyMode(), current.enabled(), v.parameters());
        return update(id, spec, "restored from version " + version);
    }

    @Transactional
    public QueryDefinition replaceSnapshot(UUID id, List<QueryColumn> columns) {
        QueryDefinitionEntity e = requireEntity(id);
        e.getColumns().clear();
        repository.flush();
        e.replaceColumns(columns);
        e.setUpdatedAt(Instant.now());
        QueryDefinition updated = toRecord(repository.save(e));
        audit.record("SNAPSHOT", "QUERY", updated.id(), updated.name(), Map.of("columns", columns.size()));
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return updated;
    }

    /** Sets key and visibility flags of snapshot columns; unknown property names are ignored. */
    @Transactional
    public QueryDefinition updateColumnSettings(UUID id, List<ColumnSetting> settings) {
        QueryDefinitionEntity e = requireEntity(id);
        Map<String, ColumnSetting> byName = settings.stream()
                .collect(Collectors.toMap(ColumnSetting::propertyName, Function.identity(), (a, b) -> b));
        for (QueryColumnEntity c : e.getColumns()) {
            ColumnSetting s = byName.get(c.getPropertyName());
            if (s != null) {
                c.setKey(s.key());
                c.setVisible(s.visible() || s.key());
            }
        }
        e.setUpdatedAt(Instant.now());
        QueryDefinition updated = toRecord(repository.save(e));
        audit.record("COLUMN_SETTINGS", "QUERY", updated.id(), updated.name(), null);
        events.publishEvent(new QueryDefinitionsChangedEvent());
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        QueryDefinitionEntity e = requireEntity(id);
        repository.delete(e);
        audit.record("DELETE", "QUERY", id, e.getName(), null);
        events.publishEvent(new QueryDefinitionsChangedEvent());
    }

    /** Builds an unsaved definition from a spec, e.g. for a test run from the editor. */
    @Transactional(readOnly = true)
    public QueryDefinition transientDefinition(QuerySpec spec) {
        validate(spec);
        DatasourceEntity ds = datasourceRepository.findById(spec.datasourceId())
                .orElseThrow(() -> new NotFoundException("Datasource " + spec.datasourceId() + " not found"));
        return new QueryDefinition(null, spec.name(), spec.description(), ds.getId(), ds.getName(), spec.sqlText(),
                spec.executionMode() == null ? ExecutionMode.CALLER : spec.executionMode(), spec.fixedCredentialId(),
                fixedName(spec.fixedCredentialId()),
                Boolean.TRUE.equals(spec.cacheEnabled()), spec.cacheTtlSec() == null ? 3600 : spec.cacheTtlSec(),
                spec.timeoutSec(), spec.maxRows(), spec.keyMode() == null ? KeyMode.ROWID : spec.keyMode(), 0,
                spec.enabled() == null || spec.enabled(), spec.parameters() == null ? List.of() : spec.parameters(),
                List.of(), null, null);
    }

    public void validate(QuerySpec spec) {
        if (spec.name() == null || !NAME_PATTERN.matcher(spec.name()).matches()) {
            throw new InvalidRequestException("Query name must match [A-Za-z][A-Za-z0-9_]* and be at most 100 characters");
        }
        if (spec.sqlText() == null || spec.sqlText().isBlank()) {
            throw new InvalidRequestException("SQL text must not be empty");
        }
        if (spec.datasourceId() == null) {
            throw new InvalidRequestException("datasourceId is required");
        }
        if (spec.executionMode() == ExecutionMode.FIXED) {
            if (spec.fixedCredentialId() == null) {
                throw new InvalidRequestException("Execution mode FIXED requires a technical account (fixedCredentialId)");
            }
            FixedCredentialEntity fc = fixedCredentials.findById(spec.fixedCredentialId())
                    .orElseThrow(() -> new NotFoundException("Technical account " + spec.fixedCredentialId() + " not found"));
            if (!fc.getDatasourceId().equals(spec.datasourceId())) {
                throw new InvalidRequestException("Technical account '" + fc.getName() + "' belongs to another datasource");
            }
        }
        List<QueryParameter> params = spec.parameters() == null ? List.of() : spec.parameters();
        for (QueryParameter p : params) {
            if (p.name() == null || !NAME_PATTERN.matcher(p.name()).matches()) {
                throw new InvalidRequestException("Parameter name '" + p.name() + "' is invalid");
            }
            if (p.type() == null) {
                throw new InvalidRequestException("Parameter '" + p.name() + "' has no type");
            }
            if (params.stream().filter(o -> p.name().equals(o.name())).count() > 1) {
                throw new InvalidRequestException("Parameter '" + p.name() + "' is declared twice");
            }
            if (p.defaultValue() != null && !p.defaultValue().isBlank()) {
                ParameterBinder.convert(p, p.defaultValue()); // throws InvalidRequestException if not parseable
            }
        }
        NamedParameterSql parsed = NamedParameterSql.parse(spec.sqlText());
        List<String> declared = params.stream().map(QueryParameter::name).toList();
        for (String p : parsed.distinctNames()) {
            if (!declared.contains(p)) {
                throw new InvalidRequestException("SQL references undeclared parameter ':" + p + "'");
            }
        }
        SqlValidator.validateReadOnly(spec.sqlText());
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private QueryDefinitionEntity requireEntity(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Query " + id + " not found"));
    }

    private QueryDefinition toRecord(QueryDefinitionEntity e) {
        return e.toRecord(fixedName(e.getFixedCredentialId()));
    }

    private String fixedName(UUID fixedCredentialId) {
        return fixedCredentialId == null ? null
                : fixedCredentials.findById(fixedCredentialId).map(FixedCredentialEntity::getName).orElse(null);
    }

    private QueryGrant toRecord(QueryGrantEntity g) {
        String principalName = switch (g.getPrincipalType()) {
            case ALL_AUTHENTICATED -> "alle authentifizierten";
            case USER -> serviceUsers.findById(g.getPrincipalId()).map(u -> u.getUsername()).orElse("(gelöscht)");
            case GROUP -> serviceGroups.findById(g.getPrincipalId()).map(gr -> gr.getName()).orElse("(gelöscht)");
        };
        return new QueryGrant(g.getId(), g.getQueryId(), g.getPrincipalType(), g.getPrincipalId(), principalName,
                g.getGrantedBy(), g.getGrantedAt());
    }

    private void apply(QueryDefinitionEntity e, QuerySpec s) {
        DatasourceEntity ds = datasourceRepository.findById(s.datasourceId())
                .orElseThrow(() -> new NotFoundException("Datasource " + s.datasourceId() + " not found"));
        e.setName(s.name());
        e.setDescription(s.description());
        e.setDatasource(ds);
        e.setSqlText(s.sqlText());
        if (s.executionMode() != null) e.setExecutionMode(s.executionMode());
        e.setFixedCredentialId(e.getExecutionMode() == ExecutionMode.FIXED ? s.fixedCredentialId() : null);
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

    /** Names of all service users and groups, for grant editors. */
    @Transactional(readOnly = true)
    public Map<UUID, String> principalNames() {
        Map<UUID, String> names = new HashMap<>();
        serviceUsers.findAll().forEach(u -> names.put(u.getId(), u.getUsername()));
        serviceGroups.findAll().forEach(g -> names.put(g.getId(), g.getName()));
        return names;
    }
}
