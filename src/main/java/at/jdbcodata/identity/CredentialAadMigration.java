package at.jdbcodata.identity;

import at.jdbcodata.crypto.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-encrypts passwords stored before {@link CredentialVault} bound ciphertexts to their record. Runs once per start
 * and touches only legacy values encrypted with the current master key; others are left for the operator.
 */
@Component
@Order(1)
public class CredentialAadMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialAadMigration.class);

    private final StoredCredentialRepository stored;
    private final FixedCredentialRepository fixed;
    private final CredentialVault vault;

    public CredentialAadMigration(StoredCredentialRepository stored, FixedCredentialRepository fixed, CredentialVault vault) {
        this.stored = stored;
        this.fixed = fixed;
        this.vault = vault;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int count = 0;
        for (StoredCredentialEntity e : stored.findAll()) {
            if (!CredentialVault.isLegacy(e.getDbPasswordEnc()) || !vault.keyId().equals(e.getKeyId())) continue;
            String context = ServiceUserService.aad(e);
            e.setDbPasswordEnc(vault.encrypt(vault.decrypt(e.getDbPasswordEnc(), e.getKeyId(), context), context));
            count++;
        }
        for (FixedCredentialEntity e : fixed.findAll()) {
            if (!CredentialVault.isLegacy(e.getDbPasswordEnc()) || !vault.keyId().equals(e.getKeyId())) continue;
            String context = FixedCredentialService.aad(e);
            e.setDbPasswordEnc(vault.encrypt(vault.decrypt(e.getDbPasswordEnc(), e.getKeyId(), context), context));
            count++;
        }
        if (count > 0) log.info("Re-encrypted {} stored password(s) with record binding", count);
    }
}
