package com.digitalself.crypto;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Links an encrypted subject (a file, later a memory column) to the wrapped key
 * that protects it. Holds no usable key material on its own.
 */
@Entity
@Table(name = "encryption_metadata")
public class EncryptionMetadata {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "subject_type", nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "key_id", nullable = false)
    private UUID keyId;

    @Column(nullable = false)
    private String algorithm = "AES-256-GCM";

    @Column(name = "wrapped_key", nullable = false)
    private byte[] wrappedKey;

    @Column(name = "key_iv", nullable = false)
    private byte[] keyIv;

    @Column(name = "master_key_label", nullable = false)
    private String masterKeyLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected EncryptionMetadata() {
        // JPA
    }

    public EncryptionMetadata(String subjectType, UUID subjectId, WrappedKey wrapped) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.keyId = UUID.randomUUID();
        this.wrappedKey = wrapped.wrappedKey();
        this.keyIv = wrapped.iv();
        this.masterKeyLabel = wrapped.masterKeyLabel();
    }

    public UUID getId() {
        return id;
    }

    public UUID getKeyId() {
        return keyId;
    }

    public WrappedKey toWrappedKey() {
        return new WrappedKey(wrappedKey, keyIv, masterKeyLabel);
    }
}
