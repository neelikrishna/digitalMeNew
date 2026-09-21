package com.digitalself.files;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MediaMetadataRepository extends JpaRepository<MediaMetadata, UUID> {

    /**
     * Photos taken in a window, newest first — the "what was I doing that
     * summer" query that EXIF exists to serve.
     *
     * <p>Joined against {@code files} rather than filtered afterwards so one
     * user's photos can never appear in another's results.
     */
    @Query("""
            SELECT m FROM MediaMetadata m, StoredFile f
            WHERE m.fileId = f.id
              AND f.userId = :userId
              AND m.takenAt IS NOT NULL
              AND m.takenAt >= :from
              AND m.takenAt <= :to
            ORDER BY m.takenAt DESC
            """)
    List<MediaMetadata> findTakenBetween(@Param("userId") UUID userId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    /**
     * Every photo the user owns, newest first, with undated ones last rather
     * than dropped.
     *
     * <p>{@code NULLS LAST} is the point: most images shared through messaging
     * apps arrive with EXIF stripped, so filtering them out would quietly hide a
     * large part of someone's library and make the gallery look broken.
     */
    @Query("""
            SELECT m FROM MediaMetadata m, StoredFile f
            WHERE m.fileId = f.id
              AND f.userId = :userId
              AND m.mediaType = :mediaType
            ORDER BY m.takenAt DESC NULLS LAST
            """)
    List<MediaMetadata> findAllOfType(@Param("userId") UUID userId,
                                      @Param("mediaType") MediaType mediaType);
}
