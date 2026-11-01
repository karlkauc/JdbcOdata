package at.jdbcodata.apikey;

import at.jdbcodata.admin.account.AdminRole;
import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.CurrentUser;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.identity.BasicCredentials;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API keys for machine clients. A key holds 256 random bits, so SHA-256 is enough and lets a request find its key
 * by hash; BCrypt protects guessable passwords and would cost every request 200 ms.
 */
@Service
public class ApiKeyService {

    /** Marks the keys, so a future Bearer JWT can never be mistaken for one. */
    public static final String KEY_PREFIX = "jdo_";
    /** Not allowed in names of local accounts, so a key can never pass for one, also not in the audit log. */
    public static final String CALLER_PREFIX = "apikey:";
    /** Basic Auth user for clients that cannot send a Bearer header, such as Excel. */
    public static final String BASIC_USER = "apikey";
    static final int MAX_DAYS = 3650;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{2,80}");
    private static final int SHOWN_CHARS = 12;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    /** The only moment the secret exists outside the client. */
    public record Created(ApiKey key, String secret) {
    }

    private final ApiKeyRepository repository;
    private final ServiceUserService serviceUsers;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository, ServiceUserService serviceUsers, AuditService audit) {
        this.repository = repository;
        this.serviceUsers = serviceUsers;
        this.audit = audit;
    }

    /** The key in an {@code Authorization} header, as {@code Bearer} token or as password of the user "apikey". */
    public static Optional<String> keyIn(String authorization) {
        if (authorization == null) return Optional.empty();
        String trimmed = authorization.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.of(trimmed.substring(7).trim()).filter(k -> k.startsWith(KEY_PREFIX));
        }
        return BasicCredentials.fromHeader(authorization).filter(c -> c.username().equalsIgnoreCase(BASIC_USER))
                .map(BasicCredentials::password).filter(k -> k.startsWith(KEY_PREFIX));
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(ApiKeyEntity::toRecord).toList();
    }

    /** Exactly one of {@code serviceUserId} (feeds) and {@code adminRole} (REST API) must be given. */
    @Transactional
    public Created create(String name, UUID serviceUserId, AdminRole adminRole, int expiresInDays) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidRequestException("Name must be 2-80 characters of letters, digits, _ . -");
        }
        if ((serviceUserId == null) == (adminRole == null)) {
            throw new InvalidRequestException("A key is either for a service user or for a REST API role");
        }
        if (expiresInDays < 1 || expiresInDays > MAX_DAYS) {
            throw new InvalidRequestException("Expiry must be between 1 and " + MAX_DAYS + " days");
        }
        if (repository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("API key '" + name + "' already exists");
        }
        if (serviceUserId != null && serviceUsers.require(serviceUserId).forcePassthrough()) {
            throw new InvalidRequestException("A force-passthrough service user needs a database login, a key has none");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        ApiKeyEntity e = new ApiKeyEntity();
        e.setName(name);
        e.setKeyPrefix(secret.substring(0, SHOWN_CHARS));
        e.setKeyHash(hash(secret));
        e.setServiceUserId(serviceUserId);
        e.setAdminRole(adminRole);
        e.setCreatedBy(CurrentUser.name());
        e.setCreatedAt(now);
        e.setExpiresAt(now.plus(Duration.ofDays(expiresInDays)));
        ApiKey created = repository.save(e).toRecord();
        audit.record("CREATE", "API_KEY", created.id(), name, Map.of("target",
                adminRole != null ? adminRole.name() : serviceUserId.toString(), "expiresAt", created.expiresAt().toString()));
        return new Created(created, secret);
    }

    /** Kept as a record of who used the service; the key stops working at once. */
    @Transactional
    public void revoke(UUID id) {
        ApiKeyEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("API key " + id + " not found"));
        if (e.getRevokedAt() == null) {
            e.setRevokedAt(Instant.now());
            audit.record("REVOKE", "API_KEY", id, e.getName(), null);
        }
    }

    /** The active key with this secret. */
    @Transactional
    public Optional<ApiKey> authenticate(String secret) {
        Instant now = Instant.now();
        Optional<ApiKey> key = repository.findByKeyHash(hash(secret)).map(ApiKeyEntity::toRecord).filter(k -> k.activeAt(now));
        key.ifPresent(k -> repository.touch(k.id(), now, now.minus(TOUCH_INTERVAL)));
        return key;
    }

    /** The service user a feed key acts as. Pass-through users have no login to run as, disabled ones no access. */
    @Transactional
    public Optional<Map.Entry<ApiKey, ServiceUser>> authenticateForFeed(String secret) {
        return authenticate(secret).filter(k -> k.serviceUserId() != null).flatMap(k -> serviceUsers.findById(k.serviceUserId())
                .filter(ServiceUser::enabled).filter(u -> !u.forcePassthrough()).map(u -> Map.entry(k, u)));
    }

    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
