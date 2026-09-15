package com.digitalself.files;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Capture metadata for one photo, audio or video file — when and where it was
 * taken, and how big it is.
 *
 * <p>Deliberately <b>not</b> a memory. A document's text is something someone
 * asserted, and turning it into a memory puts a real statement into retrieval.
 * A photo's EXIF is not an assertion: generating "Photo taken on 3 June 2019 at
 * 51.50, -0.12" as a memory would place a sentence nobody ever said into the
 * pool the assistant answers from. See docs/media-ingestion.md Section 3.
 *
 * <p>The id is the file's own id: a file has exactly one capture record.
 */
@Entity
@Table(name = "media")
public class MediaMetadata {

    @Id
    @Column(name = "file_id")
    private UUID fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;

    /**
     * The whole metadata block as the parser reported it, kept verbatim for the
     * same reason {@code file_metadata.extracted_text} is: deciding later that a
     * different field mattered should not mean re-reading every photo.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exif_json")
    private String exifJson;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "duration_seconds")
    private Float durationSeconds;

    private Integer width;

    private Integer height;

    private Double latitude;

    private Double longitude;

    /** Set whenever metadata was read, even if the file carried none. */
    @Column(name = "extracted_at")
    private Instant extractedAt;

    protected MediaMetadata() {
        // JPA
    }

    public MediaMetadata(UUID fileId, MediaType mediaType) {
        this.fileId = fileId;
        this.mediaType = mediaType;
    }

    /**
     * Records what was found. Every field is optional: a photo stripped of EXIF
     * — which is what most images shared through messaging apps are — still gets
     * a row, because "we looked and there was nothing" is worth knowing and
     * should not look like "we never looked".
     */
    public void record(String exifJson, Instant takenAt, Integer width, Integer height,
                       Double latitude, Double longitude) {
        this.exifJson = exifJson;
        this.takenAt = takenAt;
        this.width = width;
        this.height = height;
        this.latitude = latitude;
        this.longitude = longitude;
        this.extractedAt = Instant.now();
    }

    public UUID getFileId() {
        return fileId;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public String getExifJson() {
        return exifJson;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public Float getDurationSeconds() {
        return durationSeconds;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }
}
