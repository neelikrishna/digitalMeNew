package com.digitalself.files;

import com.digitalself.audit.AuditService;
import com.digitalself.config.FileExtractionProperties;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.memory.ChangeReason;
import com.digitalself.memory.MemorySource;
import com.digitalself.memory.MemoryService;
import com.digitalself.memory.MemoryType;
import com.digitalself.memory.PrivacyLevel;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.ReviseMemoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Extracts one file's text and derives its memory, in one transaction.
 *
 * <p>Separate from {@link FileIngestionService} for a mundane but load-bearing
 * reason: a {@code @Transactional} method called from another method of the same
 * bean bypasses the Spring proxy and silently runs with no transaction at all.
 * The event listener and the backfill loop both call this, so it has to live
 * behind a boundary they cross.
 *
 * <p>{@code REQUIRES_NEW} because the upload's transaction has already committed
 * by the time this runs — there is nothing to join, and each file in a backfill
 * must stand or fall alone rather than rolling back the ones before it.
 */
@Service
public class FileExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(FileExtractionWorker.class);

    private final StoredFileRepository fileRepository;
    private final FileMetadataRepository metadataRepository;
    private final FileTextExtractor extractor;
    private final FileService fileService;
    private final MemoryService memoryService;
    private final MemoryFileLinkStore linkStore;
    private final TextCrypto textCrypto;
    private final AuditService auditService;
    private final FileExtractionProperties properties;

    public FileExtractionWorker(StoredFileRepository fileRepository,
                                FileMetadataRepository metadataRepository,
                                FileTextExtractor extractor,
                                FileService fileService,
                                MemoryService memoryService,
                                MemoryFileLinkStore linkStore,
                                TextCrypto textCrypto,
                                AuditService auditService,
                                FileExtractionProperties properties) {
        this.fileRepository = fileRepository;
        this.metadataRepository = metadataRepository;
        this.extractor = extractor;
        this.fileService = fileService;
        this.memoryService = memoryService;
        this.linkStore = linkStore;
        this.textCrypto = textCrypto;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExtractionStatus extract(UUID userId, UUID fileId) {
        StoredFile file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        FileMetadata metadata = metadataRepository.findById(fileId)
                .orElseGet(() -> new FileMetadata(fileId));

        if (!extractor.supports(file.getMimeType())) {
            // Photos, audio and video are not failures — they are simply not
            // this phase's job. EXIF and transcription come with the Python
            // pipeline; recording the type keeps that visible instead of
            // leaving the file looking unprocessed forever.
            metadata.recordUnsupported(file.getMimeType());
            return save(metadata);
        }

        FileTextExtractor.Result result;
        try {
            result = extractor.extract(fileService.readContent(file), file.getOriginalFilename());
        } catch (FileExtractionException e) {
            log.warn("Text extraction failed for file {}: {}", fileId, e.getMessage());
            metadata.recordFailure(e.getMessage());
            return save(metadata);
        }

        // A scanned PDF parses fine and yields nothing: there is no text layer to
        // find. Recording that as EMPTY rather than FAILED is what stops the
        // backfill retrying it forever, and what makes "why isn't this
        // searchable?" answerable.
        if (result.isEmpty() || result.text().length() < properties.getMinCharsToIndex()) {
            metadata.recordEmpty(FileTextExtractor.SOURCE);
            return save(metadata);
        }

        storeText(file, metadata, result.text());
        deriveMemory(userId, file, metadata, result);

        auditService.record(userId, "FILE_TEXT_EXTRACTED", TextCrypto.FILE, fileId, null, null);
        return save(metadata);
    }

    /**
     * A sensitive file's text is encrypted under the file's own data key, so the
     * ciphertext dies with the file when it is shredded. An ordinary file's text
     * is stored in the clear, which is what keeps it searchable — the trade-off
     * documented in docs/file-ingestion.md Section 4.
     */
    private void storeText(StoredFile file, FileMetadata metadata, String text) {
        if (file.isSensitive()) {
            metadata.recordExtracted(null,
                    textCrypto.encrypt(TextCrypto.FILE, file.getId(), text),
                    FileTextExtractor.SOURCE);
        } else {
            metadata.recordExtracted(text, null, FileTextExtractor.SOURCE);
        }
    }

    /**
     * Creates or updates the memory that carries this file's text into
     * retrieval.
     *
     * <p>A memory rather than a new retrievable kind: {@code FILE_EXTRACTION}
     * and the prompt label "extracted from a file the owner uploaded" already
     * existed for exactly this, and going through {@code MemoryService} inherits
     * versioning, embedding, tags and archive without duplicating any of it.
     */
    private void deriveMemory(UUID userId, StoredFile file, FileMetadata metadata,
                              FileTextExtractor.Result result) {
        String content = result.truncated()
                // Said in the text rather than only in a column, because this
                // memory can end up in a model's context, and an answer drawn
                // from a partial document should be able to say so.
                ? result.text() + "\n\n[Text truncated at the extraction limit; the full file is stored.]"
                : result.text();

        if (metadata.getDerivedMemoryId() != null) {
            // Re-extraction revises rather than duplicating: a second memory for
            // the same document would double every retrieval hit.
            memoryService.revise(userId, metadata.getDerivedMemoryId(),
                    new ReviseMemoryRequest(content, null, 1.0f, ChangeReason.FILE_RE_EXTRACTION));
            return;
        }

        UUID memoryId = memoryService.create(userId, new CreateMemoryRequest(
                MemoryType.SEMANTIC,
                file.getOriginalFilename(),
                content,
                MemorySource.FILE_EXTRACTION,
                null,
                null,
                1.0f,
                PrivacyLevel.PRIVATE,
                // A sensitive file yields a sensitive memory, which MemoryIndexer
                // then refuses to embed. Without this the encryption would be
                // undone by a vector derived from the plaintext.
                file.isSensitive(),
                Set.of()
        )).id();

        metadata.setDerivedMemoryId(memoryId);
        linkStore.link(memoryId, file.getId());
    }

    private ExtractionStatus save(FileMetadata metadata) {
        metadataRepository.save(metadata);
        return metadata.getExtractionStatus();
    }
}
