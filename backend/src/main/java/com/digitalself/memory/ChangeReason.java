package com.digitalself.memory;

public enum ChangeReason {
    INITIAL,
    USER_CORRECTION,
    AI_RECLASSIFICATION,
    /** A file was parsed again and its derived memory brought up to date. */
    FILE_RE_EXTRACTION
}
