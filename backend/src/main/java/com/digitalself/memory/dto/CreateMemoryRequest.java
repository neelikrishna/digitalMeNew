package com.digitalself.memory.dto;

import com.digitalself.memory.MemorySource;
import com.digitalself.memory.MemoryType;
import com.digitalself.memory.PrivacyLevel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Set;

public record CreateMemoryRequest(
        @NotNull MemoryType type,
        String title,
        @NotBlank String content,
        MemorySource source,
        LocalDate eventDate,
        @Min(1) @Max(5) Short importance,
        @DecimalMin("0.0") @DecimalMax("1.0") Float confidence,
        PrivacyLevel privacyLevel,
        /**
         * Encrypt this memory at rest. Excludes it from full-text search, so it
         * stays findable by date, type, tag and linked people only. EMOTIONAL
         * memories are treated as sensitive whether or not this is set.
         */
        Boolean sensitive,
        Set<String> tags
) {
}
