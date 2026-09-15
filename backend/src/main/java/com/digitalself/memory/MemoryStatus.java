package com.digitalself.memory;

public enum MemoryStatus {
    ACTIVE,
    /** Replaced wholesale by a different memory. */
    SUPERSEDED,
    /** Retired by the owner. Retained and retrievable, excluded from default search. */
    ARCHIVED
}
