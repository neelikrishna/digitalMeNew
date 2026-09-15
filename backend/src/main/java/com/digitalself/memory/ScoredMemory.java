package com.digitalself.memory;

import java.util.UUID;

/** A memory id with its cosine distance to a query vector (lower is closer). */
public record ScoredMemory(UUID memoryId, double distance) {
}
