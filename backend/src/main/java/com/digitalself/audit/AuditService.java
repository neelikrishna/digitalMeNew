package com.digitalself.audit;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Append-only by convention: no update/delete method is exposed on this
 * service or its repository. Every auth event and memory mutation should
 * eventually go through here.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID userId, String action, String entityType, UUID entityId, String actor, String ipAddress) {
        repository.save(new AuditLog(userId, action, entityType, entityId, actor, ipAddress));
    }
}
