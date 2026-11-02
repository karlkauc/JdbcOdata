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
    private static final String H2 = "jdbc:h2:file:./data/jdbcodata";
    private static final String POSTGRES = "jdbc:postgresql://db:5432/jdbcodata";

    @TempDir
    Path dir;

    private at.jdbcodata.config.JdbcOdataProperties props(String key, Path dataDir) {
        var p = TestData.props(10, 10, "");
        return new at.jdbcodata.config.JdbcOdataProperties(p.admin(), p.odata(), p.seed(), p.cache(), p.stats(), p.security(), key, dataDir.toString());
    }

    @Test
    void roundTripsAndRejectsOtherKeys() {
        CredentialVault vault = new CredentialVault(new MasterKeyProvider(props(KEY, dir), H2, ""));
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
        CredentialVault vault = new CredentialVault(new MasterKeyProvider(props(KEY, dir), H2, ""));
        String ct = vault.encrypt("s3cret", "stored:user-a:ds-1:alice");
        assertThatThrownBy(() -> vault.decrypt(ct, vault.keyId(), "stored:user-b:ds-1:alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Decryption failed");
    }

    @Test
    void readsLegacyValuesWithoutRecordBinding() throws Exception {
        CredentialVault vault = new CredentialVault(new MasterKeyProvider(props(KEY, dir), H2, ""));
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
        MasterKeyProvider first = new MasterKeyProvider(props("", dir), H2, "");
        assertThat(first.source()).isEqualTo("key file");
        Path file = dir.resolve("master.key");
        assertThat(Files.exists(file)).isTrue();
        MasterKeyProvider second = new MasterKeyProvider(props("", dir), H2, "");
        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(new MasterKeyProvider(props(KEY, dir), H2, "").source()).isEqualTo("configuration");
    }

    @Test
    void rejectsInvalidConfiguredKeys() throws Exception {
        assertThatThrownBy(() -> new MasterKeyProvider(props("not base64!", dir), H2, "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MasterKeyProvider(props(Base64.getEncoder().encodeToString(new byte[5]), dir), H2, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        Path empty = dir.resolve("empty");
        Files.createDirectories(empty);
        Files.writeString(empty.resolve("master.key"), "");
        assertThatThrownBy(() -> new MasterKeyProvider(props("", empty), H2, "")).isInstanceOf(IllegalStateException.class);
        assertThat(MasterKeyProvider.computeKeyId(new byte[32])).hasSize(8);
    }

    @Test
    void sharedMetadataDbNeedsAConfiguredKey() {
        assertThatThrownBy(() -> new MasterKeyProvider(props("", dir), POSTGRES, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JDBCODATA_MASTER_KEY");
        assertThat(dir.resolve("master.key")).doesNotExist();
        assertThat(new MasterKeyProvider(props(KEY, dir), POSTGRES, "").source()).isEqualTo("configuration");
    }

    @Test
    void sharedMetadataDbIgnoresAnExistingKeyFile() throws Exception {
        String keyFile = java.nio.file.Files.writeString(dir.resolve("master.key"), KEY).toString();
        assertThatThrownBy(() -> new MasterKeyProvider(props("", dir), POSTGRES, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(keyFile).hasMessageNotContaining(KEY);
    }

    @Test
    void olderKeysDecryptButNeverEncrypt() {
        String oldKey = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        CredentialVault before = new CredentialVault(new MasterKeyProvider(props(oldKey, dir), H2, ""));
        String stored = before.encrypt("s3cret", "ctx");

        CredentialVault after = new CredentialVault(new MasterKeyProvider(props(KEY, dir), H2, oldKey + "," + KEY));

        assertThat(after.keyId()).isNotEqualTo(before.keyId());
        assertThat(after.decrypt(stored, before.keyId(), "ctx")).isEqualTo("s3cret");
        assertThatThrownBy(() -> before.decrypt(after.encrypt("x", "ctx"), after.keyId(), "ctx")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MasterKeyProvider(props(KEY, dir), H2, "not base64!")).isInstanceOf(IllegalStateException.class);
    }
}
