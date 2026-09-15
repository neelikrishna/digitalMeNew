package com.digitalself.memory.dto;

import com.digitalself.memory.MemorySource;
import com.digitalself.memory.MemoryStatus;
import com.digitalself.memory.MemoryType;
import com.digitalself.memory.PrivacyLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Built by MemoryMapper rather than a static factory, because sensitive
 * memories must be decrypted on the way out.
 *
 * @param sensitive true when this memory is encrypted at rest and therefore
 *                  excluded from full-text search
 */
public record MemoryResponse(
        UUID id,
        MemoryType type,
        String title,
        String content,
        MemorySource source,
        LocalDate eventDate,
        Short importance,
        float confidence,
        PrivacyLevel privacyLevel,
        MemoryStatus status,
        boolean sensitive,
        Set<String> tags,
        Instant createdAt,
        Instant updatedAt
) {
}
