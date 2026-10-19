package at.jdbcodata.identity;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.crypto.CredentialVault;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service users (login to this service), their groups and their stored DB credentials per datasource. */
@Service
public class ServiceUserService {

    public static final String RECOMMENDED_PREFIX = "svc_";
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.@-]{2,100}");

    public record ServiceUserSpec(String username, String displayName, String password, Boolean forcePassthrough,
                                  Boolean enabled, List<UUID> groupIds) {
    }

    private final ServiceUserRepository users;
    private final ServiceGroupRepository groups;
    private final StoredCredentialRepository credentials;
    private final DatasourceService datasourceService;
    private final CredentialVault vault;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    /**
     * Recently verified (username, password, stored hash) triples: Excel and PowerBI send the Basic header with every
     * page and batch, and one BCrypt check at cost 12 takes about a quarter of a second. Only a SHA-256 of the
     * triple is kept; a changed password changes the stored hash and therefore misses.
     */
    private final Cache<String, Boolean> verified = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10_000).build();

    public ServiceUserService(ServiceUserRepository users, ServiceGroupRepository groups,
                              StoredCredentialRepository credentials, DatasourceService datasourceService,
                              CredentialVault vault, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.groups = groups;
        this.credentials = credentials;
        this.datasourceService = datasourceService;
        this.vault = vault;
        this.encoder = encoder;
        this.audit = audit;
    }

    // --- users -------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ServiceUser> findAll() {
        return users.findAllByOrderByUsername().stream().map(ServiceUserEntity::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ServiceUser> findById(UUID id) {
        return users.findById(id).map(ServiceUserEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public Optional<ServiceUser> findByUsername(String username) {
        return users.findByUsernameIgnoreCase(username).map(ServiceUserEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public ServiceUser require(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Service user " + id + " not found"));
    }

    /** Verifies the service password; empty if the username is not a service user. */
    @Transactional
    public Optional<ServiceUser> authenticate(String username, String password) {
        Optional<ServiceUserEntity> entity = users.findByUsernameIgnoreCase(username);
        if (entity.isEmpty()) return Optional.empty();
        ServiceUserEntity e = entity.get();
        if (!e.isEnabled() || password == null || !passwordMatches(e, password)) {
            throw new ServiceAuthenticationException(username);
        }
        e.setLastLoginAt(Instant.now());
        return Optional.of(e.toRecord());
    }

    private volatile String dummyHash;

    /**
     * Spends the time of one service-password check without a real user, so a rejected pass-through login takes
     * about as long as a wrong service password and does not tell which names are service users.
     */
    public void spendPasswordCheck(String password) {
        if (dummyHash == null) dummyHash = encoder.encode(UUID.randomUUID().toString());
        encoder.matches(password == null ? "" : password, dummyHash);
    }

    private boolean passwordMatches(ServiceUserEntity e, String password) {
        String key = sha256(e.getUsername() + '\0' + password + '\0' + e.getPasswordHash());
        if (verified.getIfPresent(key) != null) return true;
        if (!encoder.matches(password, e.getPasswordHash())) return false;
        verified.put(key, Boolean.TRUE);
        return true;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Transactional
    public ServiceUser create(ServiceUserSpec spec) {
        validate(spec, true);
        users.findByUsernameIgnoreCase(spec.username()).ifPresent(u -> {
            throw new ConflictException("Service user '" + spec.username() + "' already exists");
        });
        ServiceUserEntity e = new ServiceUserEntity();
        e.setCreatedAt(Instant.now());
        e.setPasswordHash(encoder.encode(spec.password()));
        apply(e, spec);
        ServiceUser created = users.save(e).toRecord();
        audit.record("CREATE", "SERVICE_USER", created.id(), created.username(), Map.of("forcePassthrough", created.forcePassthrough()));
        return created;
    }

    @Transactional
    public ServiceUser update(UUID id, ServiceUserSpec spec) {
        validate(spec, false);
        ServiceUserEntity e = users.findById(id).orElseThrow(() -> new NotFoundException("Service user " + id + " not found"));
        users.findByUsernameIgnoreCase(spec.username()).filter(o -> !o.getId().equals(id)).ifPresent(u -> {
            throw new ConflictException("Service user '" + spec.username() + "' already exists");
        });
        if (spec.password() != null && !spec.password().isBlank()) {
            e.setPasswordHash(encoder.encode(spec.password()));
        }
        apply(e, spec);
        ServiceUser updated = users.save(e).toRecord();
        audit.record("UPDATE", "SERVICE_USER", updated.id(), updated.username(),
                Map.of("passwordChanged", spec.password() != null && !spec.password().isBlank(), "enabled", updated.enabled()));
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        ServiceUserEntity e = users.findById(id).orElseThrow(() -> new NotFoundException("Service user " + id + " not found"));
        users.delete(e);
        audit.record("DELETE", "SERVICE_USER", id, e.getUsername(), null);
    }

    private void validate(ServiceUserSpec spec, boolean create) {
        if (spec.username() == null || !USERNAME.matcher(spec.username()).matches()) {
            throw new InvalidRequestException("Username must be 2-100 characters of letters, digits, _ . @ -");
        }
        if (create && (spec.password() == null || spec.password().length() < 8)) {
            throw new InvalidRequestException("Password must have at least 8 characters");
        }
        if (!create && spec.password() != null && !spec.password().isBlank() && spec.password().length() < 8) {
            throw new InvalidRequestException("Password must have at least 8 characters");
        }
    }

    private void apply(ServiceUserEntity e, ServiceUserSpec s) {
        e.setUsername(s.username());
        e.setDisplayName(s.displayName() == null || s.displayName().isBlank() ? null : s.displayName());
        if (s.forcePassthrough() != null) e.setForcePassthrough(s.forcePassthrough());
        if (s.enabled() != null) e.setEnabled(s.enabled());
        if (s.groupIds() != null) {
            e.getGroups().clear();
            for (UUID gid : s.groupIds()) {
                e.getGroups().add(groups.findById(gid).orElseThrow(() -> new NotFoundException("Group " + gid + " not found")));
            }
        }
        e.setUpdatedAt(Instant.now());
    }

    // --- groups ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ServiceGroup> findAllGroups() {
        return groups.findAllByOrderByName().stream().map(ServiceGroupEntity::toRecord).toList();
    }

    @Transactional
    public ServiceGroup createGroup(String name, String description) {
        if (name == null || !USERNAME.matcher(name).matches()) {
            throw new InvalidRequestException("Group name must be 2-100 characters of letters, digits, _ . @ -");
        }
        groups.findByNameIgnoreCase(name).ifPresent(g -> {
            throw new ConflictException("Group '" + name + "' already exists");
        });
        ServiceGroupEntity g = new ServiceGroupEntity();
        g.setName(name);
        g.setDescription(description == null || description.isBlank() ? null : description);
        ServiceGroup created = groups.save(g).toRecord();
        audit.record("CREATE", "SERVICE_GROUP", created.id(), created.name(), null);
        return created;
    }

    @Transactional
    public void deleteGroup(UUID id) {
        ServiceGroupEntity g = groups.findById(id).orElseThrow(() -> new NotFoundException("Group " + id + " not found"));
        for (ServiceUserEntity u : users.findAll()) {
            u.getGroups().remove(g);
        }
        groups.delete(g);
        audit.record("DELETE", "SERVICE_GROUP", id, g.getName(), null);
    }

    // --- stored credentials -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StoredCredential> credentialsOf(UUID serviceUserId) {
        return credentials.findAllByServiceUserId(serviceUserId).stream().map(this::toRecord).toList();
    }

    @Transactional
    public StoredCredential storeCredential(UUID serviceUserId, UUID datasourceId, String dbUsername, String dbPassword) {
        ServiceUser user = require(serviceUserId);
        Datasource ds = datasourceService.require(datasourceId);
        if (dbUsername == null || dbUsername.isBlank()) {
            throw new InvalidRequestException("dbUsername is required");
        }
        StoredCredentialEntity e = credentials.findByServiceUserIdAndDatasourceId(serviceUserId, datasourceId)
                .orElseGet(StoredCredentialEntity::new);
        e.setServiceUserId(serviceUserId);
        e.setDatasourceId(datasourceId);
        e.setDbUsername(dbUsername);
        e.setDbPasswordEnc(vault.encrypt(dbPassword == null ? "" : dbPassword, aad(e)));
        e.setKeyId(vault.keyId());
        e.setUpdatedAt(Instant.now());
        StoredCredential saved = toRecord(credentials.save(e));
        audit.record("STORE_CREDENTIAL", "SERVICE_USER", user.id(), user.username(),
                Map.of("datasource", ds.name(), "dbUsername", dbUsername));
        return saved;
    }

    @Transactional
    public void removeCredential(UUID serviceUserId, UUID datasourceId) {
        ServiceUser user = require(serviceUserId);
        StoredCredentialEntity e = credentials.findByServiceUserIdAndDatasourceId(serviceUserId, datasourceId)
                .orElseThrow(() -> new NotFoundException("No stored credential for this datasource"));
        credentials.delete(e);
        audit.record("REMOVE_CREDENTIAL", "SERVICE_USER", user.id(), user.username(), Map.of("datasource", datasourceId.toString()));
    }

    /** Decrypted DB login of a service user for a datasource, if stored. */
    @Transactional(readOnly = true)
    public Optional<DbLogin> resolveStoredLogin(UUID serviceUserId, UUID datasourceId) {
        return credentials.findByServiceUserIdAndDatasourceId(serviceUserId, datasourceId)
                .map(e -> new DbLogin(e.getDbUsername(), vault.decrypt(e.getDbPasswordEnc(), e.getKeyId(), aad(e))));
    }

    /** Binds a stored password to its service user, datasource and DB user (see {@link CredentialVault}). */
    static String aad(StoredCredentialEntity e) {
        return "stored:" + e.getServiceUserId() + ":" + e.getDatasourceId() + ":" + e.getDbUsername();
    }

    public record DbLogin(String username, String password) {
        @Override
        public String toString() {
            return "DbLogin[" + username + "]";
        }
    }

    private StoredCredential toRecord(StoredCredentialEntity e) {
        String dsName = datasourceService.findById(e.getDatasourceId()).map(Datasource::name).orElse("?");
        return new StoredCredential(e.getId(), e.getServiceUserId(), e.getDatasourceId(), dsName, e.getDbUsername(),
                e.getKeyId(), e.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public Set<UUID> groupIdsOf(ServiceUser user) {
        return user.groups().stream().map(ServiceGroup::id).collect(java.util.stream.Collectors.toSet());
    }
}
