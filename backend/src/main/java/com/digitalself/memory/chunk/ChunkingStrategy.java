package com.digitalself.memory.chunk;

import java.util.List;

/**
 * Splits content into retrievable passages.
 *
 * <p>An interface because the right split depends entirely on what the content
 * is. Prose splits on paragraphs; a transcript splits on pauses and speaker
 * turns and must carry timestamps; a chat export splits on conversation windows
 * so a reply keeps the question it answers. Forcing all three through
 * fixed-size character windows would lose exactly the structure that makes each
 * retrievable.
 */
public interface ChunkingStrategy {

    /** Which kind of content this strategy handles. */
    ChunkContentType contentType();

    /**
     * @param text the full content to split
     * @return passages in order, never null; empty when there is nothing to split
     */
    List<ChunkDraft> split(String text);
}
