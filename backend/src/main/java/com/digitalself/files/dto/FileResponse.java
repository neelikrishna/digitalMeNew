package com.digitalself.files.dto;

import com.digitalself.files.ExtractionStatus;
import com.digitalself.files.FileMetadata;
import com.digitalself.files.MediaMetadata;
import com.digitalself.files.StoredFile;

import java.time.Instant;
import java.util.UUID;

/**
 * @param extractionStatus how text extraction went. Reported rather than left to
 *                         the logs, because "parsed but there was no text layer"
 *                         and "the parser failed" look identical from outside
 *                         and need opposite responses from you.
 * @param derivedMemoryId  the memory carrying this file's text into search, if
 *                         any. Always null for a photo — see
 *                         docs/media-ingestion.md Section 3.
 * @param photo            capture metadata, present only for images
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
        UUID derivedMemoryId,
        PhotoInfo photo
) {

    /**
     * @param takenAt when the shutter fired, not when the file was written —
     *                a copied photo keeps the former and loses the latter
     */
    public record PhotoInfo(
            Instant takenAt,
            Integer width,
            Integer height,
            Double latitude,
            Double longitude
    ) {
        static PhotoInfo from(MediaMetadata media) {
            return new PhotoInfo(media.getTakenAt(), media.getWidth(), media.getHeight(),
                    media.getLatitude(), media.getLongitude());
        }
    }

    public static FileResponse from(StoredFile file) {
        return from(file, null, null);
    }

    public static FileResponse from(StoredFile file, FileMetadata metadata, MediaMetadata media) {
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
                metadata == null ? null : metadata.getDerivedMemoryId(),
                media == null ? null : PhotoInfo.from(media)
        );
    }
}
