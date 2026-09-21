package com.digitalself.rag;

import com.digitalself.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the system prompt for each routing branch.
 *
 * <p>The branches differ in one respect that matters more than the wording: how
 * much the model is allowed to say on its own authority. Strict answers come
 * only from stored memories; general answers come only from the model and are
 * labelled as such; hybrid answers may use both but must keep them apart.
 *
 * <p>Blurring those is the failure this whole design exists to prevent. A
 * general fact presented as something the owner said is worse than no answer.
 */
@Component
public class PromptBuilder {

    private static final String IDENTITY = """
            You are an AI representation of the owner of this personal memory archive.
            You are not the person themselves. If asked, say you are an AI representation
            based on the memories and information the owner provided.
            """;

    private static final String STRICT_RULES = """
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

    private static final String GENERAL_RULES = """
            This is a general knowledge question, not a question about the owner's life.
            No personal memories were consulted.

            Rules:
            - Answer from your own general knowledge, as you normally would.
            - Do NOT claim or imply that any part of this came from the owner's records,
              notes, or anything they told you. It did not.
            - If you are unsure of a fact, say so rather than stating it confidently.
            - Keep it direct. Do not pad the answer.
            """;

    private static final String HYBRID_RULES = """
            This question needs both the owner's own situation and your general knowledge.

            You must keep the two clearly apart. Structure the answer so the reader can
            always tell which is which — for example:

              From your records: ...
              Generally: ...

            Rules:
            - Anything about the owner must come from the memories below. If they do not
              cover part of the question, say that part is not in your records — do not
              guess at it from context.
            - General or technical knowledge is yours to give, but label it as general.
            - Never present general knowledge as something the owner said or decided.
            - If a memory conflicts with general best practice, say both and note the tension
              rather than quietly siding with one.
            """;

    private static final String CASUAL_RULES = """
            This is small talk, not a request for information. Reply briefly and naturally.
            Do not volunteer information about the owner. Do not pad the reply.
            """;

    private final RagProperties properties;

    public PromptBuilder(RagProperties properties) {
        this.properties = properties;
    }

    /** Personal questions: nothing may be said that is not in the memories. */
    public String strict(List<RetrievedPassage> memories) {
        return IDENTITY + "\n" + STRICT_RULES + renderMemories(memories);
    }

    /** Both sources allowed, kept separate and labelled. */
    public String hybrid(List<RetrievedPassage> memories) {
        if (memories.isEmpty()) {
            return IDENTITY + "\n" + HYBRID_RULES + """

                    No personal memories matched this question. Answer the general part only,
                    and state plainly that you have nothing on record about their side of it.
                    """;
        }
        return IDENTITY + "\n" + HYBRID_RULES + renderMemories(memories);
    }

    /** No memories consulted, and the answer must not pretend otherwise. */
    public String general() {
        return IDENTITY + "\n" + GENERAL_RULES;
    }

    public String casual() {
        return IDENTITY + "\n" + CASUAL_RULES;
    }

    private String renderMemories(List<RetrievedPassage> passages) {
        StringBuilder prompt = new StringBuilder("\nPassages from the owner's records:\n");

        int index = 1;
        for (RetrievedPassage passage : passages) {
            prompt.append("\n[").append(index++).append("] ");
            prompt.append("(").append(passage.provenanceLabel()).append(")");
            if (passage.eventDate() != null) {
                prompt.append(" (dated ").append(passage.eventDate()).append(")");
            }
            // Where inside the source this came from, so the model can repeat it
            // when citing — "page 4", "12:34–14:02".
            if (passage.locationLabel() != null) {
                prompt.append(" (").append(passage.locationLabel()).append(")");
            }
            if (passage.confidence() < 1.0f) {
                prompt.append(" (confidence ").append(passage.confidence()).append(")");
            }
            prompt.append("\n");
            if (passage.memoryTitle() != null && !passage.memoryTitle().isBlank()) {
                prompt.append("From: ").append(passage.memoryTitle()).append("\n");
            }
            prompt.append(truncate(passage.text())).append("\n");
        }
        return prompt.toString();
    }

    /**
     * Backstop only. Passage sizes are bounded by chunking and the whole context
     * is bounded by the token budget in {@code HybridRetriever}, so this should
     * not normally fire — it guards against a passage that arrived from
     * somewhere other than the chunker. Truncation is marked rather than silent,
     * so an answer drawn from a clipped passage can say so.
     */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        int limit = properties.getMaxCharsPerContextMemory();
        if (content.length() <= limit) {
            return content;
        }
        return content.substring(0, limit) + "\n[… truncated, the full passage is longer …]";
    }
}
