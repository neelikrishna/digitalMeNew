package com.digitalself.files;

/**
 * What happened when text extraction ran against a file.
 *
 * <p>{@link #EMPTY} and {@link #FAILED} are deliberately distinct. A scanned PDF
 * is images of pages with no text layer: Tika returns nothing and that is a
 * correct answer, not an error. Retrying it forever would be noise, while never
 * retrying a genuine parser failure would silently lose the document from
 * search. Only {@link #FAILED} is retried.
 */
public enum ExtractionStatus {

    /** Uploaded, extraction not yet attempted. */
    PENDING,

    /** Text was extracted and a memory derived from it. */
    EXTRACTED,

    /** Parsed successfully, but the document contains no text layer (e.g. a scan). */
    EMPTY,

    /** Not a document — an image, audio or video file. Later phases handle these. */
    UNSUPPORTED,

    /** The parser failed. Retryable via POST /api/files/reindex. */
    FAILED
}
