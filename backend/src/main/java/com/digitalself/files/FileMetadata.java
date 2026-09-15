package com.digitalself.files;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Extraction record for one file: the verbatim text a parser produced, and what
 * happened when it ran.
 *
 * <p>This row is the source of truth for extracted text and is never rewritten
 * by anything but a re-extraction. The derived memory is the *searchable view*
 * of the same text and can be revised, archived or corrected like any other
 * memory — so the two are kept apart, exactly as {@code raw_inputs} is kept
 * apart from the memories derived from it.
 *
 * <p>The id is the file's own id: a file has exactly one extraction record.
 */
@Entity
@Table(name = "file_metadata")
public class FileMetadata {

    @Id
    @Column(name = "file_id")
    private UUID fileId;

    /** Plaintext for ordinary files; null when the file is sensitive. */
    @Column(name = "extracted_text")
    private String extractedText;

    /** Ciphertext for sensitive files; null otherwise. Never both. */
    @Column(name = "extracted_text_encrypted")
    private byte[] extractedTextEncrypted;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    /** Which extractor produced this, so a later change of parser is traceable. */
    @Column(name = "extraction_source")
    private String extractionSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false)
    private ExtractionStatus extractionStatus = ExtractionStatus.PENDING;

    @Column(name = "extraction_error")
    private String extractionError;

    @Column(name = "derived_memory_id")
    private UUID derivedMemoryId;

    protected FileMetadata() {
        // JPA
    }

    public FileMetadata(UUID fileId) {
        this.fileId = fileId;
    }

    /** Records text found, in whichever representation the file's sensitivity calls for. */
    public void recordExtracted(String plaintext, byte[] ciphertext, String source) {
        this.extractedText = plaintext;
        this.extractedTextEncrypted = ciphertext;
        this.extractionSource = source;
        this.extractionStatus = ExtractionStatus.EXTRACTED;
        this.extractionError = null;
        this.extractedAt = Instant.now();
    }

    /**
     * Records a parse that succeeded but found no text. Not an error: a scanned
     * document genuinely has no text layer, and saying so is more useful than
     * leaving the file looking unprocessed.
     */
    public void recordEmpty(String source) {
        clearText();
        this.extractionSource = source;
        this.extractionStatus = ExtractionStatus.EMPTY;
        this.extractionError = null;
        this.extractedAt = Instant.now();
    }

    /** Records a type no document parser handles — an image, audio, video. */
    public void recordUnsupported(String mimeType) {
        clearText();
        this.extractionSource = null;
        this.extractionStatus = ExtractionStatus.UNSUPPORTED;
        this.extractionError = "No text extractor for " + mimeType + ".";
        this.extractedAt = Instant.now();
    }

    /**
     * Records a parser failure. The text is cleared but the file is untouched —
     * a failed parse must never cost you the document, only its searchability,
     * and {@code POST /api/files/reindex} can try again.
     */
    public void recordFailure(String reason) {
        clearText();
        this.extractionStatus = ExtractionStatus.FAILED;
        this.extractionError = reason;
        this.extractedAt = null;
    }

    private void clearText() {
        this.extractedText = null;
        this.extractedTextEncrypted = null;
    }

    public void setDerivedMemoryId(UUID derivedMemoryId) {
        this.derivedMemoryId = derivedMemoryId;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public byte[] getExtractedTextEncrypted() {
        return extractedTextEncrypted;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }

    public String getExtractionSource() {
        return extractionSource;
    }

    public ExtractionStatus getExtractionStatus() {
        return extractionStatus;
    }

    public String getExtractionError() {
        return extractionError;
    }

    public UUID getDerivedMemoryId() {
        return derivedMemoryId;
    }
}
