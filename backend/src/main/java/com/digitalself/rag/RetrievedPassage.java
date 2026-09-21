package com.digitalself.rag;

import com.digitalself.memory.MemorySource;
import com.digitalself.memory.chunk.ChunkContentType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One passage selected for context, already decrypted and detached from its
 * entities.
 *
 * <p>Carries both halves of what a citation needs: the memory it belongs to
 * (title, date, provenance) and where inside that memory it sits (character
 * span, page, or time range). Plain values rather than entities keep the prompt
 * layer free of lazy-loading and encryption concerns — by the time a passage
 * reaches here its text is readable however it was stored.
 *
 * @param vectorDistance null when found only by full-text search
 */
public record RetrievedPassage(
        UUID chunkId,
        UUID memoryId,
        String memoryTitle,
        String text,
        ChunkContentType contentType,
        MemorySource source,
        LocalDate eventDate,
        float confidence,
        boolean corrected,
        UUID sourceFileId,
        Integer pageNumber,
        Long startMs,
        Long endMs,
        int tokenEstimate,
        double fusedScore,
        Double vectorDistance,
        boolean keywordMatch
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

    /**
     * Where in the source this passage sits, phrased for a person — "page 4",
     * "12:34–14:02". Null when the memory has no internal structure worth
     * pointing at, such as a short note.
     */
    public String locationLabel() {
        if (startMs != null && endMs != null) {
            return formatTime(startMs) + "–" + formatTime(endMs);
        }
        if (pageNumber != null) {
            return "page " + pageNumber;
        }
        return null;
    }

    private static String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, minutes, seconds)
                : "%d:%02d".formatted(minutes, seconds);
    }
}
