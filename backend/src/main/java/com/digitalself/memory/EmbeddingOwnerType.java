package com.digitalself.memory;

public enum EmbeddingOwnerType {
    /** A passage. The current unit — what retrieval matches and citations name. */
    CHUNK,
    /**
     * A whole memory. Superseded by {@link #CHUNK}; rows survive only until that
     * memory is re-indexed, and are deleted as its chunks are embedded so the
     * same memory can never be matched twice by two different units.
     */
    MEMORY,
    MESSAGE,
    FILE_CHUNK
}
