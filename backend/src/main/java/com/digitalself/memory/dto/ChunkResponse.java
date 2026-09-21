package com.digitalself.memory.dto;

import com.digitalself.memory.chunk.ChunkContentType;

import java.util.UUID;

/**
 * @param location human-readable position inside the source — "page 4",
 *                 "12:34–14:02" — or null when there is nothing to point at
 */
public record ChunkResponse(
        UUID id,
        int index,
        String text,
        ChunkContentType contentType,
        Integer startOffset,
        Integer endOffset,
        String location,
        UUID sourceFileId,
        int tokenEstimate
) {
}
