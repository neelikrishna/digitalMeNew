package com.digitalself.rag;

import com.digitalself.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptBuilder {

    private final RagProperties ragProperties;

    public PromptBuilder(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    private static final String BASE_INSTRUCTIONS = """
            You are an AI representation of the owner of this personal memory archive.
            You are not the person themselves. If asked, say you are an AI representation
            based on the memories and information the owner provided.

            Answer ONLY from the memories listed below. They are the only facts available to you.

            Rules:
            - If the memories do not answer the question, reply exactly: "I don't have a memory of that."
            - Never invent events, dates, names, places, or details that are not in the memories.
            - Do not fill gaps with plausible guesses or general knowledge about the world.
            - Respect each memory's provenance label:
                * "stated by owner" - the owner said this directly; you may state it plainly.
                * "inferred by AI, not directly stated" - present it as an inference, not a fact.
                * "corrected by owner" - this memory was revised; the text shown is the current version.
                * "extracted from a file the owner uploaded" - derived from a document, not said directly.
            - If two memories conflict, say that they conflict instead of silently choosing one.
            - Prefer the owner's own wording where it is available.
            """;

    public String buildSystemPrompt(List<RetrievedMemory> memories) {
        StringBuilder prompt = new StringBuilder(BASE_INSTRUCTIONS);
        prompt.append("\nMemories available for this question:\n");

        int index = 1;
        for (RetrievedMemory retrieved : memories) {
            prompt.append("\n[").append(index++).append("] ");
            prompt.append("(").append(retrieved.provenanceLabel()).append(")");
            if (retrieved.eventDate() != null) {
                prompt.append(" (dated ").append(retrieved.eventDate()).append(")");
            }
            if (retrieved.confidence() < 1.0f) {
                prompt.append(" (confidence ").append(retrieved.confidence()).append(")");
            }
            prompt.append("\n");
            if (retrieved.title() != null && !retrieved.title().isBlank()) {
                prompt.append(retrieved.title()).append("\n");
            }
            prompt.append(clip(retrieved.content())).append("\n");
        }
        return prompt.toString();
    }

    /**
     * Caps one memory's contribution to the prompt.
     *
     * <p>Memories derived from uploaded documents can be enormous — a whole PDF
     * is one memory — and without a cap a handful of them would exceed the
     * context window of any model this runs on locally. The cut is announced in
     * the text so the model can qualify an answer drawn from a partial memory,
     * rather than confidently answering from a document it only half received.
     */
    private String clip(String content) {
        int limit = ragProperties.getMaxCharsPerContextMemory();
        if (content == null || content.length() <= limit) {
            return content;
        }
        return content.substring(0, limit)
                + "\n[This memory is longer than shown here; the rest was not included.]";
    }
}
