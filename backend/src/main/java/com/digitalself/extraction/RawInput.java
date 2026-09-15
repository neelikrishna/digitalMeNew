package com.digitalself.extraction;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * What the owner actually wrote, kept verbatim and never rewritten. Structured
 * memories derived from it are separate rows, so the original wording survives
 * even if extraction was wrong.
 */
/**
 * The id is assigned in the constructor rather than by the database, because the
 * content must be encrypted — which needs the id as its key subject — before the
 * row is ever written. Looking up that key runs a query, and a query triggers
 * Hibernate's auto-flush, so a database-generated id would force an INSERT with
 * the content column still empty.
 */
@Entity
@Table(name = "raw_inputs")
public class RawInput implements Persistable<UUID> {

    @Id
    private UUID id = UUID.randomUUID();

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Always encrypted: this is the owner's own words, and nothing searches it. */
    @Column(name = "content_encrypted", nullable = false)
    private byte[] contentEncrypted;

    @Column(nullable = false)
    private String source = "TEXT_ENTRY";

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected RawInput() {
        // JPA
    }

    public RawInput(UUID userId, String source) {
        this.userId = userId;
        this.source = source;
    }

    /**
     * Set after the row has an id, because the encryption key is keyed by it.
     * The ciphertext is the only representation ever stored.
     */
    public void setContentEncrypted(byte[] contentEncrypted) {
        this.contentEncrypted = contentEncrypted;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Tells Spring Data to persist rather than merge, despite the id being pre-set. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getContentEncrypted() {
        return contentEncrypted;
    }

    public String getSource() {
        return source;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
