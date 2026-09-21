package com.digitalself.rag.dto;

import com.digitalself.rag.intent.IntentDecision;
import com.digitalself.rag.intent.QuestionIntent;

import java.util.List;
import java.util.UUID;

/**
 * @param intent             how the question was routed. Exposed so a surprising
 *                           answer can be explained — a general question wrongly
 *                           treated as personal is visible here rather than
 *                           looking like the archive is empty.
 * @param intentConfidence   below ~0.5 the routing was a guess
 * @param answeredFromMemory whether personal memories actually reached the model.
 *                           False for general knowledge, and false when a
 *                           personal question found nothing.
 * @param usedGeneralKnowledge whether the model was allowed to answer on its own
 *                           authority. Both flags are true for a hybrid answer.
 * @param groundedInMemories the memories placed in context, so any personal claim
 *                           can be traced to a stored row
 */
public record ChatResponse(
        UUID conversationId,
        String answer,
        QuestionIntent intent,
        double intentConfidence,
        boolean answeredFromMemory,
        boolean usedGeneralKnowledge,
        List<CitedMemory> groundedInMemories
) {

    /**
     * Where a claim came from, precise enough to open.
     *
     * @param location human-readable position inside the source — "page 4",
     *                 "12:34–14:02" — or null for a memory with no internal
     *                 structure worth pointing at
     * @param sourceFileId the uploaded file this passage was extracted from, if any
     */
    public record CitedMemory(
            UUID memoryId,
            UUID chunkId,
            String title,
            String provenance,
            String location,
            UUID sourceFileId
    ) {
    }

    public static ChatResponse of(UUID conversationId, String answer, IntentDecision decision,
                                  boolean answeredFromMemory, boolean usedGeneralKnowledge,
                                  List<CitedMemory> cited) {
        return new ChatResponse(conversationId, answer, decision.intent(), decision.confidence(),
                answeredFromMemory, usedGeneralKnowledge, cited);
    }
}
