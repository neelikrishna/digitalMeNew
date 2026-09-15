package com.digitalself.memory;

import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 2 search: keyword + metadata filters only. Semantic/vector search and
 * tsvector-backed full-text ranking arrive in Phase 3 (see docs/ai-architecture.md);
 * ILIKE is adequate at the row counts this phase targets.
 */
final class MemorySpecifications {

    private MemorySpecifications() {
    }

    static Specification<Memory> ownedBy(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    static Specification<Memory> hasStatus(MemoryStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Memory> hasType(MemoryType type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    static Specification<Memory> hasSource(MemorySource source) {
        return (root, query, cb) -> cb.equal(root.get("source"), source);
    }

    static Specification<Memory> eventDateFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("eventDate"), from);
    }

    static Specification<Memory> eventDateTo(LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("eventDate"), to);
    }

    static Specification<Memory> matchesKeyword(String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("content")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("title"), "")), pattern)
        );
    }

    static Specification<Memory> hasTag(String tagName) {
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }
            Join<Memory, Tag> tags = root.join("tags");
            return cb.equal(cb.lower(tags.get("name")), tagName.toLowerCase());
        };
    }
}
