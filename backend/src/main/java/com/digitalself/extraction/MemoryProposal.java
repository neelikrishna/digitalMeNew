package com.digitalself.extraction;

import com.digitalself.memory.MemoryType;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A memory the model believes is in the owner's text, awaiting review. Never a
 * memory itself — accepting one is what creates the real row.
 */
@Entity
@Table(name = "memory_proposals")
public class MemoryProposal {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "raw_input_id", nullable = false)
    private UUID rawInputId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryType type;

    /**
     * Encrypted under the parent raw input's key, so shredding an input destroys
     * everything derived from it in one act.
     */
    @Column(name = "title_encrypted")
    private byte[] titleEncrypted;

    @Column(name = "content_encrypted", nullable = false)
    private byte[] contentEncrypted;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(nullable = false)
    private float confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalStatus status = ProposalStatus.PENDING;

    @Column(name = "created_memory_id")
    private UUID createdMemoryId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MemoryProposal() {
        // JPA
    }

    public MemoryProposal(UUID userId, UUID rawInputId, MemoryType type, byte[] titleEncrypted,
                          byte[] contentEncrypted, LocalDate eventDate, float confidence) {
        this.userId = userId;
        this.rawInputId = rawInputId;
        this.type = type;
        this.titleEncrypted = titleEncrypted;
        this.contentEncrypted = contentEncrypted;
        this.eventDate = eventDate;
        this.confidence = confidence;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRawInputId() {
        return rawInputId;
    }

    public MemoryType getType() {
        return type;
    }

    public byte[] getTitleEncrypted() {
        return titleEncrypted;
    }

    public byte[] getContentEncrypted() {
        return contentEncrypted;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public float getConfidence() {
        return confidence;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public UUID getCreatedMemoryId() {
        return createdMemoryId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void accept(UUID memoryId) {
        this.status = ProposalStatus.ACCEPTED;
        this.createdMemoryId = memoryId;
        this.reviewedAt = Instant.now();
    }

    public void reject() {
        this.status = ProposalStatus.REJECTED;
        this.reviewedAt = Instant.now();
    }
}
