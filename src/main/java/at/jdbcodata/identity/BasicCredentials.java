package at.jdbcodata.identity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Credentials taken from an HTTP Basic {@code Authorization} header. */
public record BasicCredentials(String username, String password) {

    public static Optional<BasicCredentials> fromHeader(String authorization) {
        if (authorization == null) return Optional.empty();
        String trimmed = authorization.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Basic ", 0, 6)) return Optional.empty();
        try {
            String decoded = new String(Base64.getDecoder().decode(trimmed.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) return Optional.empty();
            // the name goes into logs: no forged lines
            if (decoded.substring(0, colon).chars().anyMatch(Character::isISOControl)) return Optional.empty();
            return Optional.of(new BasicCredentials(decoded.substring(0, colon), decoded.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "BasicCredentials[username=" + username + "]";
    }
}
