package at.jdbcodata.identity;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.crypto.CredentialVault;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.query.QueryDefinitionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Technical database accounts for FIXED-mode queries. */
@Service
public class FixedCredentialService {

    public record FixedCredentialSpec(String name, String description, UUID datasourceId, String dbUsername, String dbPassword) {
    }

    private final FixedCredentialRepository repository;
    private final QueryDefinitionRepository queries;
    private final DatasourceService datasourceService;
    private final CredentialVault vault;
    private final AuditService audit;

    public FixedCredentialService(FixedCredentialRepository repository, QueryDefinitionRepository queries,
                                  DatasourceService datasourceService, CredentialVault vault, AuditService audit) {
        this.repository = repository;
        this.queries = queries;
        this.datasourceService = datasourceService;
        this.vault = vault;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<FixedCredential> findAll() {
        return repository.findAllByOrderByName().stream().map(this::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<FixedCredential> findById(UUID id) {
        return repository.findById(id).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public FixedCredential require(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Technical account " + id + " not found"));
    }

    @Transactional
    public FixedCredential create(FixedCredentialSpec spec) {
        validate(spec, true);
        repository.findByNameIgnoreCase(spec.name()).ifPresent(c -> {
            throw new ConflictException("Technical account '" + spec.name() + "' already exists");
        });
        FixedCredentialEntity e = new FixedCredentialEntity();
        e.setCreatedAt(Instant.now());
        apply(e, spec);
        FixedCredential created = toRecord(repository.save(e));
        audit.record("CREATE", "FIXED_CREDENTIAL", created.id(), created.name(),
                Map.of("datasource", created.datasourceName(), "dbUsername", created.dbUsername()));
        return created;
    }

    @Transactional
    public FixedCredential update(UUID id, FixedCredentialSpec spec) {
        validate(spec, false);
        FixedCredentialEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Technical account " + id + " not found"));
        repository.findByNameIgnoreCase(spec.name()).filter(o -> !o.getId().equals(id)).ifPresent(c -> {
            throw new ConflictException("Technical account '" + spec.name() + "' already exists");
        });
        apply(e, spec);
        FixedCredential updated = toRecord(repository.save(e));
        audit.record("UPDATE", "FIXED_CREDENTIAL", updated.id(), updated.name(),
                Map.of("passwordChanged", spec.dbPassword() != null && !spec.dbPassword().isBlank()));
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        FixedCredentialEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Technical account " + id + " not found"));
        long used = queries.countByFixedCredentialId(id);
        if (used > 0) {
            throw new ConflictException("Technical account is used by " + used + " query definition(s)");
        }
        repository.delete(e);
        audit.record("DELETE", "FIXED_CREDENTIAL", id, e.getName(), null);
    }

    /** Decrypted login of a technical account. */
    @Transactional(readOnly = true)
    public ServiceUserService.DbLogin resolveLogin(UUID id) {
        FixedCredentialEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Technical account " + id + " not found"));
        return new ServiceUserService.DbLogin(e.getDbUsername(), vault.decrypt(e.getDbPasswordEnc(), e.getKeyId()));
    }

    private void validate(FixedCredentialSpec spec, boolean create) {
        if (spec.name() == null || !ServiceUserService.USERNAME.matcher(spec.name()).matches()) {
            throw new InvalidRequestException("Name must be 2-100 characters of letters, digits, _ . @ -");
        }
        if (spec.datasourceId() == null) {
            throw new InvalidRequestException("datasourceId is required");
        }
        if (spec.dbUsername() == null || spec.dbUsername().isBlank()) {
            throw new InvalidRequestException("dbUsername is required");
        }
        if (create && spec.dbPassword() == null) {
            throw new InvalidRequestException("dbPassword is required");
        }
    }

    private void apply(FixedCredentialEntity e, FixedCredentialSpec s) {
        datasourceService.require(s.datasourceId());
        e.setName(s.name());
        e.setDescription(s.description() == null || s.description().isBlank() ? null : s.description());
        e.setDatasourceId(s.datasourceId());
        e.setDbUsername(s.dbUsername());
        if (s.dbPassword() != null && (e.getDbPasswordEnc() == null || !s.dbPassword().isEmpty())) {
            e.setDbPasswordEnc(vault.encrypt(s.dbPassword()));
            e.setKeyId(vault.keyId());
        }
        e.setUpdatedAt(Instant.now());
    }

    private FixedCredential toRecord(FixedCredentialEntity e) {
        String dsName = datasourceService.findById(e.getDatasourceId()).map(Datasource::name).orElse("?");
        return e.toRecord(dsName);
    }
}
