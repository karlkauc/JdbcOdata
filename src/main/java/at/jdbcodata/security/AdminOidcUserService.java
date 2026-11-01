package at.jdbcodata.security;

import at.jdbcodata.admin.account.AdminRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Maps an OIDC user to an admin role, on every sign-in. No {@code admin_user} row is created: the provider owns these
 * users, so a role taken away there applies from the next sign-in.
 */
@Component
public class AdminOidcUserService extends OidcUserService {

    /** Not allowed in names of local accounts, so an IdP user can never pass for one, also not in the audit log. */
    static final String PREFIX = "oidc:";
    private static final int MAX_NAME = 100; // audit_log.admin_user

    private final OidcProperties props;

    public AdminOidcUserService(OidcProperties props) {
        this.props = props;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        return toAdmin(super.loadUser(request));
    }

    OidcUser toAdmin(OidcUser user) {
        Object claim = user.getClaims().get(props.usernameClaim());
        if (claim == null || claim.toString().isBlank()) {
            throw new OidcLoginRejected(PREFIX + user.getSubject(), "Claim " + props.usernameClaim() + " is missing");
        }
        String name = PREFIX + claim;
        if (name.length() > MAX_NAME) {
            throw new OidcLoginRejected(name.substring(0, MAX_NAME), "Claim " + props.usernameClaim() + " is too long");
        }
        AdminRole role = role(user.getClaims().get(props.roleClaim()))
                .orElseThrow(() -> new OidcLoginRejected(name, "No role in claim " + props.roleClaim()));
        return new AdminOidcUser(name, role, user.getIdToken(), user.getUserInfo());
    }

    private Optional<AdminRole> role(Object claim) {
        List<?> values = claim instanceof Collection<?> c ? List.copyOf(c) : claim == null ? List.of() : List.of(claim);
        if (values.stream().anyMatch(props.adminValues()::contains)) return Optional.of(AdminRole.ADMIN);
        if (values.stream().anyMatch(props.viewerValues()::contains)) return Optional.of(AdminRole.VIEWER);
        return Optional.empty();
    }

    /** An OIDC user whose name is the prefixed claim instead of the provider's opaque subject. */
    private static final class AdminOidcUser extends DefaultOidcUser {

        private static final long serialVersionUID = 1L;

        private final String name;

        AdminOidcUser(String name, AdminRole role, OidcIdToken idToken, OidcUserInfo userInfo) {
            super(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())), idToken, userInfo);
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
