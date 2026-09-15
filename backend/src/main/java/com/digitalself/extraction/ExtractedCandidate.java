package com.digitalself.extraction;

import com.digitalself.memory.MemoryType;

import java.time.LocalDate;

/** One memory the model claims to have found in the owner's text. */
public record ExtractedCandidate(
        MemoryType type,
        String title,
        String content,
        LocalDate eventDate,
        float confidence
) {
}
