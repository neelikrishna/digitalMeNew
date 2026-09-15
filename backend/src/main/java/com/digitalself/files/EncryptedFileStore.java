package com.digitalself.files;

import com.digitalself.config.CryptoProperties;
import com.digitalself.crypto.EnvelopeEncryptionService;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Reads and writes encrypted blobs on local disk.
 *
 * <p>Plaintext is never written to the storage directory: content is encrypted
 * in memory first, and the ciphertext is staged to a temporary file in the same
 * directory then atomically moved into place, so a crash mid-write cannot leave
 * a half-written blob at the real path.
 */
@Component
public class EncryptedFileStore {

    private final Path root;
    private final EnvelopeEncryptionService encryption;

    public EncryptedFileStore(CryptoProperties properties, EnvelopeEncryptionService encryption) {
        this.root = Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
        this.encryption = encryption;
    }

    /** Returns the storage path, relative to the configured root. */
    public String write(UUID fileId, SecretKey dataKey, byte[] plaintext) {
        String relativePath = relativePathFor(fileId);
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            byte[] ciphertext = encryption.encrypt(dataKey, plaintext);

            Path staging = Files.createTempFile(target.getParent(), fileId.toString(), ".tmp");
            Files.write(staging, ciphertext);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return relativePath;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store encrypted file " + fileId, e);
        }
    }

    public byte[] read(String relativePath, SecretKey dataKey) {
        try {
            return encryption.decrypt(dataKey, Files.readAllBytes(resolve(relativePath)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read encrypted file at " + relativePath, e);
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete encrypted file at " + relativePath, e);
        }
    }

    /** Sharded by the first two characters of the id to avoid one huge directory. */
    private static String relativePathFor(UUID fileId) {
        String id = fileId.toString();
        return id.substring(0, 2) + "/" + id + ".enc";
    }

    /**
     * Resolves inside the storage root and verifies containment, so a crafted
     * path can never escape the directory.
     */
    private Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Resolved path escapes the storage root.");
        }
        return resolved;
    }
}
