package com.digitalself.memory.dto;

import com.digitalself.memory.MemoryType;
import com.digitalself.memory.PrivacyLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Metadata-only edit. Deliberately cannot change content, event date, or
 * confidence — those go through a revision so the change is versioned.
 */
public record UpdateMemoryMetadataRequest(
        String title,
        @NotNull MemoryType type,
        @Min(1) @Max(5) Short importance,
        @NotNull PrivacyLevel privacyLevel,
        /** Flipping this re-encrypts or decrypts the stored text in place. Null leaves it unchanged. */
        Boolean sensitive,
        Set<String> tags
) {
}
