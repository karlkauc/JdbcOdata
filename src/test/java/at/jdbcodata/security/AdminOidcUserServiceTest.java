package at.jdbcodata.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class AdminOidcUserServiceTest {

    private final AdminOidcUserService service = new AdminOidcUserService(new OidcProperties(true, "preferred_username",
            "groups", List.of("admins"), List.of("viewers", "readers")));

    @Test
    void adminWinsOverViewer() {
        OidcUser user = service.toAdmin(user(Map.of("preferred_username", "anna", "groups", List.of("viewers", "admins"))));

        assertThat(user.getName()).isEqualTo("oidc:anna");
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void singleValueClaimCounts() {
        OidcUser user = service.toAdmin(user(Map.of("preferred_username", "vera", "groups", "readers")));

        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_VIEWER");
    }

    @Test
    void valuesMatchExactly() {
        assertThatThrownBy(() -> service.toAdmin(user(Map.of("preferred_username", "x", "groups", List.of("Admins", "admins2")))))
                .isInstanceOfSatisfying(OidcLoginRejected.class, e -> assertThat(e.username()).isEqualTo("oidc:x"));
    }

    @Test
    void missingRoleClaimIsRejected() {
        assertThatThrownBy(() -> service.toAdmin(user(Map.of("preferred_username", "x"))))
                .isInstanceOf(OidcLoginRejected.class);
    }

    @Test
    void missingNameClaimIsRejectedWithTheSubject() {
        assertThatThrownBy(() -> service.toAdmin(user(Map.of("groups", "admins"))))
                .isInstanceOfSatisfying(OidcLoginRejected.class, e -> assertThat(e.username()).isEqualTo("oidc:subject"));
    }

    @Test
    void nameThatDoesNotFitTheAuditLogIsRejected() {
        assertThatThrownBy(() -> service.toAdmin(user(Map.of("preferred_username", "x".repeat(97), "groups", "admins"))))
                .isInstanceOfSatisfying(OidcLoginRejected.class, e -> assertThat(e.username()).hasSize(100));
    }

    private static OidcUser user(Map<String, Object> claims) {
        OidcIdToken.Builder token = OidcIdToken.withTokenValue("token").subject("subject").issuedAt(Instant.now());
        claims.forEach(token::claim);
        return new DefaultOidcUser(List.of(), token.build());
    }
}
