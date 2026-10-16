package at.jdbcodata.datasource;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.query.QueryDefinitionRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasourceService {

    private final DatasourceRepository repository;
    private final QueryDefinitionRepository queryRepository;
    private final IdentityPoolManager pools;

    public DatasourceService(DatasourceRepository repository, QueryDefinitionRepository queryRepository,
                             IdentityPoolManager pools) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.pools = pools;
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
        return repository.save(e).toRecord();
    }

    @Transactional
    public Datasource update(UUID id, DatasourceSpec spec) {
        DatasourceEntity e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Datasource " + id + " not found"));
        repository.findByName(spec.name()).filter(o -> !o.getId().equals(id)).ifPresent(d -> {
            throw new ConflictException("Datasource name '" + spec.name() + "' already exists");
        });
        apply(e, spec);
        Datasource updated = repository.save(e).toRecord();
        pools.closePoolsFor(id);
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
        repository.delete(e);
        pools.closePoolsFor(id);
    }

    /** Opens one connection with the given credentials outside of any pool. Credentials are not stored. */
    public ConnectionTestResult testConnection(UUID id, String username, String password) {
        Datasource ds = require(id);
        long start = System.nanoTime();
        try {
            if (ds.driverClass() != null && !ds.driverClass().isBlank()) {
                Class.forName(ds.driverClass());
            }
            Properties props = new Properties();
            props.setProperty("user", username);
            props.setProperty("password", password == null ? "" : password);
            DriverManager.setLoginTimeout(10);
            try (Connection c = DriverManager.getConnection(ds.jdbcUrl(), props)) {
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

    private static void apply(DatasourceEntity e, DatasourceSpec s) {
        e.setName(s.name());
        e.setDescription(s.description());
        e.setJdbcUrl(s.jdbcUrl());
        e.setDriverClass(blankToNull(s.driverClass()));
        e.setTimezone(blankToNull(s.timezone()));
        if (s.defaultTimeoutSec() != null) e.setDefaultTimeoutSec(s.defaultTimeoutSec());
        if (s.maxRows() != null) e.setMaxRows(s.maxRows());
        if (s.maxConcurrent() != null) e.setMaxConcurrent(s.maxConcurrent());
        if (s.enabled() != null) e.setEnabled(s.enabled());
        e.setUpdatedAt(Instant.now());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
