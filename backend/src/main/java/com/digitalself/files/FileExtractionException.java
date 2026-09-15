package com.digitalself.files;

/**
 * Text extraction failed for one file. Never fatal to the upload: the file is
 * already stored and the failure is recorded on its metadata row so
 * {@code POST /api/files/reindex} can try again.
 */
public class FileExtractionException extends RuntimeException {

    public FileExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
