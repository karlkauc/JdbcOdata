package at.jdbcodata.identity;

import at.jdbcodata.crypto.CredentialVault;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings stored passwords up to date at each start: values from an older master key ({@code jdbcodata.master-key-previous})
 * move to the current one, and values stored before {@link CredentialVault} bound ciphertexts to their record get that
 * binding. What cannot be read stays as it is for the operator, and does not stop the start.
 */
@Component
@Order(1)
public class CredentialReEncryption implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialReEncryption.class);

    private final StoredCredentialRepository stored;
    private final FixedCredentialRepository fixed;
    private final CredentialVault vault;

    public CredentialReEncryption(StoredCredentialRepository stored, FixedCredentialRepository fixed, CredentialVault vault) {
        this.stored = stored;
        this.fixed = fixed;
        this.vault = vault;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int[] counts = new int[2]; // re-encrypted, unreadable
        for (StoredCredentialEntity e : stored.findAll()) {
            update(e.getDbPasswordEnc(), e.getKeyId(), ServiceUserService.aad(e), e::setDbPasswordEnc, e::setKeyId, counts);
        }
        for (FixedCredentialEntity e : fixed.findAll()) {
            update(e.getDbPasswordEnc(), e.getKeyId(), FixedCredentialService.aad(e), e::setDbPasswordEnc, e::setKeyId, counts);
        }
        if (counts[0] > 0) log.info("Re-encrypted {} stored password(s) with key {}", counts[0], vault.keyId());
        if (counts[1] > 0) {
            log.warn("{} stored password(s) cannot be read with the configured master keys; they stay unusable until the "
                    + "old key is in jdbcodata.master-key-previous or they are entered again", counts[1]);
        }
    }

    private void update(String encrypted, String keyId, String context, Consumer<String> setEncrypted, Consumer<String> setKeyId, int[] counts) {
        if (vault.keyId().equals(keyId) && !CredentialVault.isLegacy(encrypted)) return;
        try {
            String password = vault.decrypt(encrypted, keyId, context);
            setEncrypted.accept(vault.encrypt(password, context));
            setKeyId.accept(vault.keyId());
            counts[0]++;
        } catch (IllegalStateException e) {
            counts[1]++;
        }
    }
}
