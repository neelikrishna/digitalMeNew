package com.digitalself.memory;

import java.util.UUID;

/** A chunk with its cosine distance to a query vector (lower is closer). */
public record ScoredChunk(UUID chunkId, UUID memoryId, double distance) {
}
