package com.digitalself.memory;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "memories")
public class Memory {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryType type;

    /**
     * Plaintext fields are used for ordinary memories; the encrypted pair is
     * used for sensitive ones. Exactly one of the two is populated, enforced by
     * a database check constraint as well as by {@link #storePlaintext} and
     * {@link #storeEncrypted}.
     */
    private String title;

    private String content;

    @Column(name = "title_encrypted")
    private byte[] titleEncrypted;

    @Column(name = "content_encrypted")
    private byte[] contentEncrypted;

    /** Sensitive memories are encrypted at rest and excluded from text search. */
    @Column(nullable = false)
    private boolean sensitive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemorySource source;

    @Column(name = "event_date")
    private LocalDate eventDate;

    private Short importance;

    @Column(nullable = false)
    private float confidence = 1.0f;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_level", nullable = false)
    private PrivacyLevel privacyLevel = PrivacyLevel.PRIVATE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryStatus status = MemoryStatus.ACTIVE;

    /**
     * Raw id rather than a mapped association: memories and memory_versions
     * reference each other, and keeping one side unmapped avoids a circular
     * entity graph while the FK still enforces integrity in the database.
     */
    @Column(name = "current_version_id")
    private UUID currentVersionId;

    // BatchSize keeps a page of search results from issuing one tag query per
    // row; Hibernate loads tags for up to 50 memories at a time instead.
    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinTable(
            name = "memory_tags",
            joinColumns = @JoinColumn(name = "memory_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Memory() {
        // JPA
    }

    public Memory(UUID userId, MemoryType type, String title, String content, MemorySource source,
                  LocalDate eventDate, Short importance, float confidence, PrivacyLevel privacyLevel) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.source = source;
        this.eventDate = eventDate;
        this.importance = importance;
        this.confidence = confidence;
        this.privacyLevel = privacyLevel;
    }

    /** Switches this memory to plaintext storage, clearing any ciphertext. */
    public void storePlaintext(String title, String content) {
        this.title = title;
        this.content = content;
        this.titleEncrypted = null;
        this.contentEncrypted = null;
        this.sensitive = false;
        this.updatedAt = Instant.now();
    }

    /** Switches this memory to encrypted storage, clearing any plaintext. */
    public void storeEncrypted(byte[] title, byte[] content) {
        this.title = null;
        this.content = null;
        this.titleEncrypted = title;
        this.contentEncrypted = content;
        this.sensitive = true;
        this.updatedAt = Instant.now();
    }

    public byte[] getTitleEncrypted() {
        return titleEncrypted;
    }

    public byte[] getContentEncrypted() {
        return contentEncrypted;
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

    public MemoryType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public MemorySource getSource() {
        return source;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public Short getImportance() {
        return importance;
    }

    public float getConfidence() {
        return confidence;
    }

    public PrivacyLevel getPrivacyLevel() {
        return privacyLevel;
    }

    public MemoryStatus getStatus() {
        return status;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setCurrentVersionId(UUID currentVersionId) {
        this.currentVersionId = currentVersionId;
        this.updatedAt = Instant.now();
    }

    /**
     * Applies the non-text part of a correction. The text itself goes through
     * storePlaintext/storeEncrypted so the storage invariant cannot be bypassed.
     * Never called without a matching MemoryVersion row being written first —
     * the version history is the record of what changed.
     */
    public void applyRevision(LocalDate eventDate, float confidence) {
        this.eventDate = eventDate;
        this.confidence = confidence;
        this.updatedAt = Instant.now();
    }

    public void updateMetadata(MemoryType type, Short importance, PrivacyLevel privacyLevel) {
        this.type = type;
        this.importance = importance;
        this.privacyLevel = privacyLevel;
        this.updatedAt = Instant.now();
    }

    public void replaceTags(Set<Tag> tags) {
        this.tags.clear();
        this.tags.addAll(tags);
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.status = MemoryStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    public void restore() {
        this.status = MemoryStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }
}
