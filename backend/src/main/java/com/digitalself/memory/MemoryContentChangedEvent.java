package com.digitalself.memory;

import java.util.UUID;

/** Published when a memory's content changes and its embedding is stale. */
public record MemoryContentChangedEvent(UUID memoryId) {
}
