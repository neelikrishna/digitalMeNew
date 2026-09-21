package com.digitalself.memory.chunk;

/**
 * What kind of passage a chunk holds, which decides how it was split and which
 * positional fields are meaningful on it.
 */
public enum ChunkContentType {

    /** Prose from a note or a document. Carries character offsets, sometimes a page. */
    TEXT,

    /** A span of speech from audio or video. Carries a millisecond range. */
    TRANSCRIPT_SEGMENT,

    /** A run of messages from a chat export, kept together so a reply keeps its question. */
    CHAT_WINDOW,

    /** A description of an image. Short, and positional fields do not apply. */
    CAPTION
}
