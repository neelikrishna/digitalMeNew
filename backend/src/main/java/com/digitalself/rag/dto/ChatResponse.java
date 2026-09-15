package com.digitalself.rag.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param groundedInMemories memories actually placed in the model's context —
 *                           an answer can always be traced back to stored rows.
 * @param answeredFromMemory false when nothing relevant was retrieved and the
 *                           model was never called.
 */
public record ChatResponse(
        UUID conversationId,
        String answer,
        boolean answeredFromMemory,
        List<CitedMemory> groundedInMemories
) {

    public record CitedMemory(UUID memoryId, String title, String provenance) {
    }
}
