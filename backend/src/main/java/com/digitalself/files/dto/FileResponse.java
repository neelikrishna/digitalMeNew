package com.digitalself.files.dto;

import com.digitalself.files.ExtractionStatus;
import com.digitalself.files.FileMetadata;
import com.digitalself.files.StoredFile;

import java.time.Instant;
import java.util.UUID;

/**
 * @param extractionStatus how text extraction went. Reported rather than left to
 *                         the logs, because "parsed but there was no text layer"
 *                         and "the parser failed" look identical from outside
 *                         and need opposite responses from you.
 * @param derivedMemoryId  the memory carrying this file's text into search, if
 *                         any
 */
public record FileResponse(
        UUID id,
        String originalFilename,
        String mimeType,
        String contentHash,
        Instant uploadedAt,
        boolean encrypted,
        boolean sensitive,
        ExtractionStatus extractionStatus,
        String extractionError,
        UUID derivedMemoryId
) {

    public static FileResponse from(StoredFile file) {
        return from(file, null);
    }

    public static FileResponse from(StoredFile file, FileMetadata metadata) {
        return new FileResponse(
                file.getId(),
                file.getOriginalFilename(),
                file.getMimeType(),
                file.getContentHash(),
                file.getUploadedAt(),
                file.getEncryptionKeyId() != null,
                file.isSensitive(),
                metadata == null ? ExtractionStatus.PENDING : metadata.getExtractionStatus(),
                metadata == null ? null : metadata.getExtractionError(),
                metadata == null ? null : metadata.getDerivedMemoryId()
        );
    }
}
