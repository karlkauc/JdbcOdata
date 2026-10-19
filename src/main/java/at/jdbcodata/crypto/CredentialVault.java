package at.jdbcodata.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of stored database passwords. Current format: {@code 2:} + Base64(iv || ciphertext+tag),
 * with the owning record (e.g. service user, datasource and DB user) as additional authenticated data, so a
 * ciphertext copied into another row does not decrypt. Legacy values without prefix carry no AAD; they are still
 * readable and are re-encrypted at startup ({@code CredentialAadMigration}).
 */
@Component
public class CredentialVault {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String FORMAT_AAD = "2:";

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public CredentialVault(MasterKeyProvider provider) {
        this.key = new SecretKeySpec(provider.key(), "AES");
        this.keyId = provider.keyId();
    }

    public String keyId() {
        return keyId;
    }

    public String encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal((plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return FORMAT_AAD + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** True for values written before AAD was introduced. */
    public static boolean isLegacy(String encoded) {
        return encoded != null && !encoded.startsWith(FORMAT_AAD);
    }

    public String decrypt(String encoded, String storedKeyId, String context) {
        if (!keyId.equals(storedKeyId)) {
            throw new IllegalStateException("Credential was encrypted with key " + storedKeyId
                    + " but the configured master key is " + keyId);
        }
        try {
            boolean legacy = isLegacy(encoded);
            byte[] in = Base64.getDecoder().decode(legacy ? encoded : encoded.substring(FORMAT_AAD.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            if (!legacy) cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] pt = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
