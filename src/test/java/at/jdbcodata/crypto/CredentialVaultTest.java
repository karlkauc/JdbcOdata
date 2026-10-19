package at.jdbcodata.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.testutil.TestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialVaultTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir
    Path dir;

    private at.jdbcodata.config.JdbcOdataProperties props(String key, Path dataDir) {
        var p = TestData.props(10, 10, "");
        return new at.jdbcodata.config.JdbcOdataProperties(p.admin(), p.odata(), p.seed(), p.cache(), p.stats(), p.security(), key, dataDir.toString());
    }

    @Test
    void roundTripsAndRejectsOtherKeys() {
        CredentialVault vault = new CredentialVault(new MasterKeyProvider(props(KEY, dir)));
        String ct = vault.encrypt("s3cret", "ctx");
        assertThat(ct).startsWith("2:").isNotEqualTo(vault.encrypt("s3cret", "ctx")).doesNotContain("s3cret");
        assertThat(CredentialVault.isLegacy(ct)).isFalse();
        assertThat(vault.decrypt(ct, vault.keyId(), "ctx")).isEqualTo("s3cret");
        assertThat(vault.decrypt(vault.encrypt(null, "ctx"), vault.keyId(), "ctx")).isEmpty();
        assertThatThrownBy(() -> vault.decrypt(ct, "deadbeef", "ctx")).isInstanceOf(IllegalStateException.class).hasMessageContaining("key");
        assertThatThrownBy(() -> vault.decrypt("!!!", vault.keyId(), "ctx")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.decrypt(Base64.getEncoder().encodeToString(new byte[20]), vault.keyId(), "ctx"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Decryption failed");
    }

    @Test
    void ciphertextIsBoundToItsRecord() {
        CredentialVault vault = new CredentialVault(new MasterKeyProvider(props(KEY, dir)));
        String ct = vault.encrypt("s3cret", "stored:user-a:ds-1:alice");
        assertThatThrownBy(() -> vault.decrypt(ct, vault.keyId(), "stored:user-b:ds-1:alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Decryption failed");
    }

    @Test
    void readsLegacyValuesWithoutRecordBinding() throws Exception {
        CredentialVault vault = new CredentialVault(new MasterKeyProvider(props(KEY, dir)));
        byte[] iv = new byte[12];
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal("old".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        String legacy = Base64.getEncoder().encodeToString(all);
        assertThat(CredentialVault.isLegacy(legacy)).isTrue();
        assertThat(vault.decrypt(legacy, vault.keyId(), "anything")).isEqualTo("old");
    }

    @Test
    void generatesAndReusesKeyFile() throws Exception {
        MasterKeyProvider first = new MasterKeyProvider(props("", dir));
        assertThat(first.source()).isEqualTo("key file");
        Path file = dir.resolve("master.key");
        assertThat(Files.exists(file)).isTrue();
        MasterKeyProvider second = new MasterKeyProvider(props("", dir));
        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(new MasterKeyProvider(props(KEY, dir)).source()).isEqualTo("configuration");
    }

    @Test
    void rejectsInvalidConfiguredKeys() throws Exception {
        assertThatThrownBy(() -> new MasterKeyProvider(props("not base64!", dir))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MasterKeyProvider(props(Base64.getEncoder().encodeToString(new byte[5]), dir)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        Path empty = dir.resolve("empty");
        Files.createDirectories(empty);
        Files.writeString(empty.resolve("master.key"), "");
        assertThatThrownBy(() -> new MasterKeyProvider(props("", empty))).isInstanceOf(IllegalStateException.class);
        assertThat(MasterKeyProvider.computeKeyId(new byte[32])).hasSize(8);
    }
}
