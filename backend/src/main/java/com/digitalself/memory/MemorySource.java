package com.digitalself.memory;

/**
 * Provenance of a memory. Kept distinct from confidence: source says where it
 * came from, confidence says how sure we are. Both are surfaced in answers so
 * "you told me this" is never conflated with "I inferred this".
 */
public enum MemorySource {
    USER_INPUT,
    CONVERSATION,
    FILE_EXTRACTION,
    AI_INFERENCE
}
