package at.jdbcodata.usage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code sha256(daily salt | ip | user-agent)}, first 32 hex characters; the salt is an HMAC of the date. The same
 * visitor counts once per day and cannot be followed across days; the IP itself is never stored.
 */
final class VisitorHasher {

    private final byte[] secret;

    VisitorHasher(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String hash(String ip, String userAgent, LocalDate day) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String salt = HexFormat.of().formatHex(hmac.doFinal(day.toString().getBytes(StandardCharsets.UTF_8)));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + "|" + ip + "|" + userAgent).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
