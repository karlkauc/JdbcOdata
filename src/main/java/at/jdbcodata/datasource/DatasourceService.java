package at.jdbcodata.datasource;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.identity.FixedCredentialRepository;
import at.jdbcodata.query.QueryDefinitionRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasourceService {

    private final DatasourceRepository repository;
    private final QueryDefinitionRepository queryRepository;
    private final FixedCredentialRepository fixedCredentials;
    private final IdentityPoolManager pools;
    private final AuditService audit;
    private final JdbcUrlPolicy urlPolicy;

    public DatasourceService(DatasourceRepository repository, QueryDefinitionRepository queryRepository,
                             FixedCredentialRepository fixedCredentials, IdentityPoolManager pools, AuditService audit,
                             JdbcUrlPolicy urlPolicy) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.fixedCredentials = fixedCredentials;
        this.pools = pools;
        this.audit = audit;
        this.urlPolicy = urlPolicy;
    }

    @Transactional(readOnly = true)
    public List<Datasource> findAll() {
        return repository.findAll().stream().map(DatasourceEntity::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Datasource> findById(UUID id) {
        return repository.findById(id).map(DatasourceEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public Optional<Datasource> findByName(String name) {
        return repository.findByName(name).map(DatasourceEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public Datasource require(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Datasource " + id + " not found"));
    }

    @Transactional
    public Datasource create(DatasourceSpec spec) {
        repository.findByName(spec.name()).ifPresent(d -> {
            throw new ConflictException("Datasource name '" + spec.name() + "' already exists");
        });
        DatasourceEntity e = new DatasourceEntity();
        e.setCreatedAt(Instant.now());
        apply(e, spec);
        Datasource created = repository.save(e).toRecord();
        audit.record("CREATE", "DATASOURCE", created.id(), created.name(), Map.of("jdbcUrl", created.jdbcUrl(),
                "sessionSettings", created.sessionSettings(), "driverProperties", created.driverProperties()));
        return created;
    }

    @Transactional
    public Datasource update(UUID id, DatasourceSpec spec) {
        DatasourceEntity e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Datasource " + id + " not found"));
        repository.findByName(spec.name()).filter(o -> !o.getId().equals(id)).ifPresent(d -> {
            throw new ConflictException("Datasource name '" + spec.name() + "' already exists");
        });
        apply(e, spec);
        // query overrides were checked against the old database; another dialect has other keys
        for (var q : queryRepository.findAllByDatasourceId(id)) {
            try {
                SessionSettings.validate(e.getJdbcUrl(), q.getSessionSettings());
            } catch (InvalidRequestException ex) {
                throw new InvalidRequestException("Query '" + q.getName() + "': " + ex.getMessage());
            }
        }
        Datasource updated = repository.save(e).toRecord();
        pools.closePoolsFor(id);
        audit.record("UPDATE", "DATASOURCE", updated.id(), updated.name(), Map.of("jdbcUrl", updated.jdbcUrl(), "enabled", updated.enabled(),
                "sessionSettings", updated.sessionSettings(), "driverProperties", updated.driverProperties()));
        return updated;
    }

    /** Creates the datasource if the name is unknown, otherwise updates it. Used by the seed loader. */
    @Transactional
    public Datasource upsertByName(DatasourceSpec spec) {
        return repository.findByName(spec.name())
                .map(e -> update(e.getId(), spec))
                .orElseGet(() -> create(spec));
    }

    @Transactional
    public void delete(UUID id) {
        DatasourceEntity e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Datasource " + id + " not found"));
        long references = queryRepository.countByDatasourceId(id);
        if (references > 0) {
            throw new ConflictException("Datasource is referenced by " + references + " query definition(s)");
        }
        long accounts = fixedCredentials.countByDatasourceId(id);
        if (accounts > 0) {
            throw new ConflictException("Datasource is referenced by " + accounts + " technical account(s)");
        }
        repository.delete(e);
        pools.closePoolsFor(id);
        audit.record("DELETE", "DATASOURCE", id, e.getName(), null);
    }

    /** Opens one connection with the given credentials outside of any pool. Credentials are not stored. */
    public ConnectionTestResult testConnection(UUID id, String username, String password) {
        Datasource ds = require(id);
        long start = System.nanoTime();
        try {
            // also covers datasources stored before the policy existed
            urlPolicy.check(ds.jdbcUrl(), ds.driverClass());
            urlPolicy.checkDriverProperties(ds.driverProperties());
        } catch (InvalidRequestException e) {
            return ConnectionTestResult.failure(e.getMessage(), elapsedMs(start));
        }
        try {
            if (ds.driverClass() != null && !ds.driverClass().isBlank()) {
                Class.forName(ds.driverClass());
            }
            Properties props = new Properties();
            props.putAll(ds.driverProperties());
            props.setProperty("user", username);
            props.setProperty("password", password == null ? "" : password);
            DriverManager.setLoginTimeout(10);
            try (Connection c = DriverManager.getConnection(ds.jdbcUrl(), props)) {
                String initSql = SessionSettings.initSql(ds.jdbcUrl(), ds.sessionSettings());
                if (initSql != null) {
                    try (Statement st = c.createStatement()) {
                        st.execute(initSql);
                    } catch (SQLException e) {
                        return ConnectionTestResult.failure("Session settings rejected: " + DbErrorClassifier.describe(e),
                                elapsedMs(start));
                    }
                }
                var meta = c.getMetaData();
                return ConnectionTestResult.success(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(),
                        elapsedMs(start));
            }
        } catch (ClassNotFoundException e) {
            return ConnectionTestResult.failure("JDBC driver class not found: " + ds.driverClass(), elapsedMs(start));
        } catch (SQLException e) {
            return ConnectionTestResult.failure(DbErrorClassifier.describe(e), elapsedMs(start));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void apply(DatasourceEntity e, DatasourceSpec s) {
        urlPolicy.check(s.jdbcUrl(), s.driverClass());
        e.setName(s.name());
        e.setDescription(s.description());
        e.setJdbcUrl(s.jdbcUrl());
        e.setDriverClass(blankToNull(s.driverClass()));
        e.setTimezone(blankToNull(s.timezone()));
        if (s.defaultTimeoutSec() != null) e.setDefaultTimeoutSec(s.defaultTimeoutSec());
        if (s.maxRows() != null) e.setMaxRows(s.maxRows());
        if (s.maxConcurrent() != null) e.setMaxConcurrent(s.maxConcurrent());
        if (s.enabled() != null) e.setEnabled(s.enabled());
        // checked again when kept: the JDBC URL may now point to another database
        e.setSessionSettings(SessionSettings.validate(s.jdbcUrl(),
                s.sessionSettings() != null ? s.sessionSettings() : e.getSessionSettings()));
        if (s.driverProperties() != null) {
            urlPolicy.checkDriverProperties(s.driverProperties());
            e.setDriverProperties(trimKeys(s.driverProperties()));
        }
        e.setUpdatedAt(Instant.now());
    }

    private static Map<String, String> trimKeys(Map<String, String> map) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> result.put(k.strip(), v.strip()));
        return result;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
