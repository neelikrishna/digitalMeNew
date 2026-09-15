package com.digitalself.files;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    /**
     * Files whose extraction is still owed: never attempted, or attempted and
     * failed. {@code EMPTY} and {@code UNSUPPORTED} are excluded deliberately —
     * both are settled answers about the document, not pending work, so
     * retrying them would spin forever on files that will never yield text.
     *
     * <p>Joined against {@code files} rather than filtered in memory so one
     * user's backfill can never touch another user's rows.
     */
    @Query("""
            SELECT m FROM FileMetadata m, StoredFile f
            WHERE m.fileId = f.id
              AND f.userId = :userId
              AND m.extractionStatus IN (com.digitalself.files.ExtractionStatus.PENDING,
                                         com.digitalself.files.ExtractionStatus.FAILED)
            ORDER BY m.fileId
            """)
    List<FileMetadata> findPendingExtraction(@Param("userId") UUID userId);
}
