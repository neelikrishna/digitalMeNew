package com.digitalself.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    private String actor;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json")
    private String detailsJson;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(UUID userId, String action, String entityType, UUID entityId, String actor, String ipAddress) {
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.actor = actor;
        this.ipAddress = ipAddress;
    }

    public UUID getId() {
        return id;
    }
}
