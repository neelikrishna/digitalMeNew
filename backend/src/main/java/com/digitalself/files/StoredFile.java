package com.digitalself.files;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for one uploaded file. The bytes themselves live encrypted on disk
 * at {@code storagePath}; this row never holds file content.
 */
@Entity
@Table(name = "files")
public class StoredFile {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    /** SHA-256 of the plaintext, for dedupe and integrity checks. */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    /** Detected from the bytes, not from what the client claimed. */
    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "encryption_key_id")
    private UUID encryptionKeyId;

    /**
     * Governs what extraction does with what it reads, not whether the bytes are
     * encrypted — they always are. A sensitive file's extracted text is stored
     * as ciphertext and its derived memory is created sensitive, which keeps it
     * out of full-text search and out of the embedding store.
     */
    @Column(nullable = false)
    private boolean sensitive;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    protected StoredFile() {
        // JPA
    }

    public StoredFile(UUID userId, String storagePath, String contentHash,
                      String mimeType, String originalFilename, boolean sensitive) {
        this.userId = userId;
        this.storagePath = storagePath;
        this.contentHash = contentHash;
        this.mimeType = mimeType;
        this.originalFilename = originalFilename;
        this.sensitive = sensitive;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public UUID getEncryptionKeyId() {
        return encryptionKeyId;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setEncryptionKeyId(UUID encryptionKeyId) {
        this.encryptionKeyId = encryptionKeyId;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }
}
