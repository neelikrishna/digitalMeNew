package com.digitalself.memory;

public enum PrivacyLevel {
    /** Owner only. Never exposed to legacy access, now or later. */
    PRIVATE,
    /** Readable by authorized legacy viewers if Digital Legacy mode is ever enabled. */
    LEGACY_READABLE,
    /** Available to legacy viewers in full, including for conversational answers. */
    LEGACY_FULL
}
