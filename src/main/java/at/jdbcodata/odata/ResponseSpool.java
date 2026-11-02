package at.jdbcodata.odata;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * One response body while it is written: in memory up to {@link ResponseSpools#MEMORY_BYTES}, then in a temporary file.
 * Results can be confidential even with the cache switched off, so the file is encrypted with a key that exists only
 * here; it is unreadable after a crash. Write errors are unchecked, because Olingo's streaming serializer swallows an
 * {@link IOException} and would leave a truncated body behind.
 */
final class ResponseSpool extends OutputStream {

    /** The response exceeds {@code jdbcodata.odata.max-response-mb}. Maps to HTTP 413. */
    static final class TooLarge extends RuntimeException {
        TooLarge(long limitMb) {
            super("The response exceeds the limit of " + limitMb + " MB. Narrow the query with parameters, $select or $top.");
        }
    }

    private final ResponseSpools limits;
    private final MessageDigest digest = ResponseETag.digest();
    private final ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private long size;
    private long reserved;
    private Path file;
    private OutputStream disk;
    private SecretKey key;
    private byte[] iv;

    ResponseSpool(ResponseSpools limits) {
        this.limits = limits;
    }

    @Override
    public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        limits.checkSize(size + length);
        size += length;
        digest.update(bytes, offset, length);
        try {
            if (disk == null && memory.size() + length > ResponseSpools.MEMORY_BYTES) moveToDisk();
            if (disk == null) {
                memory.write(bytes, offset, length);
            } else {
                limits.reserveDisk(length);
                reserved += length;
                disk.write(bytes, offset, length);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the response to " + file, e);
        }
    }

    private void moveToDisk() throws IOException {
        limits.reserveDisk(memory.size());
        reserved += memory.size();
        file = Files.createTempFile("jdbcodata-spool-", ".tmp");
        try {
            key = KeyGenerator.getInstance("AES").generateKey();
            iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            disk = new CipherOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024), cipher(Cipher.ENCRYPT_MODE));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        memory.writeTo(disk);
        memory.reset();
    }

    private Cipher cipher(int mode) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(mode, key, new IvParameterSpec(iv));
        return cipher;
    }

    /** Ends writing; the body stays until {@link #release}. */
    @Override
    public void close() {
        if (disk == null) return;
        try {
            disk.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the response to " + file, e);
        }
    }

    long size() {
        return size;
    }

    String etag() {
        return ResponseETag.of(digest);
    }

    /** A new stream over the whole body; call after {@link #close}. */
    InputStream content() {
        if (file == null) return new ByteArrayInputStream(memory.toByteArray());
        try {
            return new CipherInputStream(new BufferedInputStream(Files.newInputStream(file), 64 * 1024), cipher(Cipher.DECRYPT_MODE));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the response from " + file, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    Path file() {
        return file;
    }

    /** Frees file and disk budget; a second call does nothing. */
    void release() {
        limits.releaseDisk(reserved);
        reserved = 0;
        memory.reset();
        if (file == null) return;
        try {
            if (disk != null) disk.close();
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + file, e);
        }
    }
}
