package com.digitalself.memory;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A typed edge between two memories — the graph structure of the archive. */
@Entity
@Table(name = "memory_links")
public class MemoryLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "source_memory_id", nullable = false)
    private UUID sourceMemoryId;

    @Column(name = "target_memory_id", nullable = false)
    private UUID targetMemoryId;

    @Column(name = "link_type", nullable = false)
    private String linkType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MemoryLink() {
        // JPA
    }

    public MemoryLink(UUID sourceMemoryId, UUID targetMemoryId, String linkType) {
        this.sourceMemoryId = sourceMemoryId;
        this.targetMemoryId = targetMemoryId;
        this.linkType = linkType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceMemoryId() {
        return sourceMemoryId;
    }

    public UUID getTargetMemoryId() {
        return targetMemoryId;
    }

    public String getLinkType() {
        return linkType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
