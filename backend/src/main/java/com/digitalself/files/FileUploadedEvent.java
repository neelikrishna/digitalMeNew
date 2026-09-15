package com.digitalself.files;

import java.util.UUID;

/**
 * Published when a file has been stored and its transaction committed. Consumed
 * by {@link FileIngestionService} to extract text outside the upload
 * transaction.
 */
public record FileUploadedEvent(UUID userId, UUID fileId) {
}
