package com.digitalself.memory;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable record of one state of a memory. Rows are never updated except to
 * stamp superseded_at when a newer version replaces them, and never deleted.
 */
@Entity
@Table(name = "memory_versions")
public class MemoryVersion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "memory_id", nullable = false)
    private UUID memoryId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** Plaintext for ordinary memories, ciphertext for sensitive ones — never both. */
    private String content;

    @Column(name = "content_encrypted")
    private byte[] contentEncrypted;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(nullable = false)
    private float confidence = 1.0f;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_reason", nullable = false)
    private ChangeReason changeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected MemoryVersion() {
        // JPA
    }

    private MemoryVersion(UUID memoryId, int versionNumber, String content, byte[] contentEncrypted,
                          LocalDate eventDate, float confidence, ChangeReason changeReason) {
        this.memoryId = memoryId;
        this.versionNumber = versionNumber;
        this.content = content;
        this.contentEncrypted = contentEncrypted;
        this.eventDate = eventDate;
        this.confidence = confidence;
        this.changeReason = changeReason;
    }

    public static MemoryVersion plaintext(UUID memoryId, int versionNumber, String content,
                                           LocalDate eventDate, float confidence, ChangeReason changeReason) {
        return new MemoryVersion(memoryId, versionNumber, content, null, eventDate, confidence, changeReason);
    }

    public static MemoryVersion encrypted(UUID memoryId, int versionNumber, byte[] content,
                                           LocalDate eventDate, float confidence, ChangeReason changeReason) {
        return new MemoryVersion(memoryId, versionNumber, null, content, eventDate, confidence, changeReason);
    }

    public byte[] getContentEncrypted() {
        return contentEncrypted;
    }

    public boolean isEncrypted() {
        return contentEncrypted != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMemoryId() {
        return memoryId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getContent() {
        return content;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public float getConfidence() {
        return confidence;
    }

    public ChangeReason getChangeReason() {
        return changeReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public void markSuperseded() {
        this.supersededAt = Instant.now();
    }
}
