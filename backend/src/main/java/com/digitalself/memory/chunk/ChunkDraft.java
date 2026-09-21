package com.digitalself.memory.chunk;

import java.util.UUID;

/**
 * A passage a strategy has identified, before it is persisted or encrypted.
 *
 * <p>Deliberately holds plaintext: strategies split text and know nothing about
 * storage or encryption. {@link MemoryChunker} decides how it is written down.
 *
 * @param startOffset character span in the source text; null when not applicable
 * @param startMs     time span for audio and video; null otherwise
 * @param pageNumber  page for paginated documents; null otherwise
 */
public record ChunkDraft(
        int index,
        String text,
        ChunkContentType contentType,
        Integer startOffset,
        Integer endOffset,
        Long startMs,
        Long endMs,
        Integer pageNumber,
        UUID sourceFileId
) {

    /** A plain text passage with character offsets — the common case. */
    public static ChunkDraft text(int index, String text, int startOffset, int endOffset) {
        return new ChunkDraft(index, text, ChunkContentType.TEXT,
                startOffset, endOffset, null, null, null, null);
    }

    public ChunkDraft withSourceFile(UUID fileId) {
        return new ChunkDraft(index, text, contentType, startOffset, endOffset,
                startMs, endMs, pageNumber, fileId);
    }

    /**
     * Rough token count for context budgeting. Four characters per token is the
     * usual English approximation — close enough to decide how many passages fit,
     * and far cheaper than running a real tokeniser on every chunk.
     */
    public int estimateTokens() {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
