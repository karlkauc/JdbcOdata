package at.jdbcodata.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Files holding secrets (master key, initial admin password): readable by the service account only. */
public final class SecretFiles {

    private SecretFiles() {
    }

    /**
     * Creates the file with mode 600 before anything is written, so the content is never readable by others, not
     * even briefly. On file systems without POSIX permissions (Windows) the directory ACLs apply.
     *
     * @throws FileAlreadyExistsException the file exists; it is not overwritten
     */
    public static void createOwnerOnly(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
