package com.digitalself.rag;

import com.digitalself.memory.MemorySource;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A memory selected for context, already decrypted and detached from its entity.
 *
 * <p>Carrying plain values rather than the entity keeps the prompt layer free of
 * both lazy-loading and encryption concerns: by the time a memory reaches here,
 * its text is readable regardless of how it was stored.
 *
 * @param vectorDistance null when the memory was found only by text search
 */
public record RetrievedMemory(
        UUID memoryId,
        String title,
        String content,
        MemorySource source,
        LocalDate eventDate,
        float confidence,
        double fusedScore,
        Double vectorDistance,
        boolean keywordMatch,
        boolean corrected
) {

    public String provenanceLabel() {
        if (corrected) {
            return "corrected by owner";
        }
        return switch (source) {
            case USER_INPUT -> "stated by owner";
            case CONVERSATION -> "stated by owner in conversation";
            case FILE_EXTRACTION -> "extracted from a file the owner uploaded";
            case AI_INFERENCE -> "inferred by AI, not directly stated";
        };
    }
}
