package at.jdbcodata.crypto;

import at.jdbcodata.config.JdbcOdataProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Provides the AES-256 master key used by {@link CredentialVault}. Sources in order: property
 * {@code jdbcodata.master-key} / env {@code JDBCODATA_MASTER_KEY} (Base64, 32 bytes), otherwise a key file
 * {@code <data-dir>/master.key} that is generated on first start. The key is never stored in the metadata DB.
 */
@Component
public class MasterKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyProvider.class);
    static final int KEY_BYTES = 32;

    private final byte[] key;
    private final String keyId;
    private final String source;

    public MasterKeyProvider(JdbcOdataProperties props) {
        byte[] configured = decode(props.masterKey());
        if (configured != null) {
            key = configured;
            source = "configuration";
        } else {
            key = loadOrCreateKeyFile(Path.of(props.dataDir(), "master.key"));
            source = "key file";
        }
        keyId = computeKeyId(key);
        log.info("Credential master key loaded from {} (key id {})", source, keyId);
    }

    byte[] key() {
        return key.clone();
    }

    /** Short identifier stored next to each ciphertext so keys can be rotated later. */
    public String keyId() {
        return keyId;
    }

    public String source() {
        return source;
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("jdbcodata.master-key is not valid Base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("jdbcodata.master-key must decode to " + KEY_BYTES + " bytes, got " + bytes.length);
        }
        return bytes;
    }

    private static byte[] loadOrCreateKeyFile(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] bytes = decode(Files.readString(file, StandardCharsets.UTF_8));
                if (bytes == null) throw new IllegalStateException("Key file " + file + " is empty");
                return bytes;
            }
            byte[] bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(bytes), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows or non-POSIX file system
            }
            log.warn("No master key configured; generated {}. Back it up: stored DB credentials cannot be decrypted without it.",
                    file.toAbsolutePath());
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create master key file " + file, e);
        }
    }

    static String computeKeyId(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
