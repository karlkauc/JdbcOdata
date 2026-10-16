package at.jdbcodata.admin.account;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.CurrentUser;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin accounts for UI and API; also the Spring Security {@link UserDetailsService}. */
@Service
public class AdminUserService implements UserDetailsService {

    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.@-]{2,100}");

    public record AdminUserSpec(String username, String password, AdminRole role, Boolean enabled) {
    }

    private final AdminUserRepository repository;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminUserService(AdminUserRepository repository, PasswordEncoder encoder, AuditService audit) {
        this.repository = repository;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Override
    @Transactional
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUserEntity e = repository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown admin user"));
        e.setLastLoginAt(Instant.now());
        return User.withUsername(e.getUsername()).password(e.getPasswordHash()).roles(e.getRole().name())
                .disabled(!e.isEnabled()).build();
    }

    @Transactional(readOnly = true)
    public List<AdminUser> findAll() {
        return repository.findAllByOrderByUsername().stream().map(AdminUserEntity::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminUser> findById(UUID id) {
        return repository.findById(id).map(AdminUserEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public AdminUser require(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Admin user " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Transactional
    public AdminUser create(AdminUserSpec spec) {
        return create(spec, true);
    }

    /** {@code enforcePasswordPolicy=false} is used for the configured initial admin only. */
    @Transactional
    public AdminUser create(AdminUserSpec spec, boolean enforcePasswordPolicy) {
        if (spec.username() == null || !USERNAME.matcher(spec.username()).matches()) {
            throw new InvalidRequestException("Username must be 2-100 characters of letters, digits, _ . @ -");
        }
        if (spec.password() == null || spec.password().isEmpty() || (enforcePasswordPolicy && spec.password().length() < 8)) {
            throw new InvalidRequestException("Password must have at least 8 characters");
        }
        repository.findByUsernameIgnoreCase(spec.username()).ifPresent(u -> {
            throw new ConflictException("Admin user '" + spec.username() + "' already exists");
        });
        AdminUserEntity e = new AdminUserEntity();
        e.setUsername(spec.username());
        e.setPasswordHash(encoder.encode(spec.password()));
        e.setRole(spec.role() == null ? AdminRole.ADMIN : spec.role());
        e.setEnabled(spec.enabled() == null || spec.enabled());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        AdminUser created = repository.save(e).toRecord();
        audit.record("CREATE", "ADMIN_USER", created.id(), created.username(), Map.of("role", created.role().name()));
        return created;
    }

    @Transactional
    public AdminUser update(UUID id, AdminUserSpec spec) {
        AdminUserEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Admin user " + id + " not found"));
        if (spec.password() != null && !spec.password().isBlank()) {
            if (spec.password().length() < 8) throw new InvalidRequestException("Password must have at least 8 characters");
            e.setPasswordHash(encoder.encode(spec.password()));
        }
        AdminRole newRole = spec.role() == null ? e.getRole() : spec.role();
        boolean newEnabled = spec.enabled() == null ? e.isEnabled() : spec.enabled();
        if ((e.getRole() == AdminRole.ADMIN && e.isEnabled()) && (newRole != AdminRole.ADMIN || !newEnabled)
                && repository.countByRoleAndEnabledTrue(AdminRole.ADMIN) <= 1) {
            throw new ConflictException("At least one enabled ADMIN account must remain");
        }
        e.setRole(newRole);
        e.setEnabled(newEnabled);
        e.setUpdatedAt(Instant.now());
        AdminUser updated = repository.save(e).toRecord();
        audit.record("UPDATE", "ADMIN_USER", updated.id(), updated.username(),
                Map.of("role", updated.role().name(), "enabled", updated.enabled(),
                        "passwordChanged", spec.password() != null && !spec.password().isBlank()));
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        AdminUserEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Admin user " + id + " not found"));
        if (e.getUsername().equalsIgnoreCase(CurrentUser.name())) {
            throw new ConflictException("You cannot delete your own account");
        }
        if (e.getRole() == AdminRole.ADMIN && e.isEnabled() && repository.countByRoleAndEnabledTrue(AdminRole.ADMIN) <= 1) {
            throw new ConflictException("At least one enabled ADMIN account must remain");
        }
        repository.delete(e);
        audit.record("DELETE", "ADMIN_USER", id, e.getUsername(), null);
    }
}
