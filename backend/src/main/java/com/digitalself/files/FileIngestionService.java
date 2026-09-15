package com.digitalself.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Decides <i>when</i> a file gets extracted. The work itself is in
 * {@link FileExtractionWorker}.
 *
 * <p>Extraction runs after the upload transaction commits, for the same reason
 * {@code MemoryIndexer} embeds after commit: parsing a large PDF takes seconds
 * and must not hold a Postgres transaction open.
 *
 * <p>The trade-off is the same too — an upload can succeed while extraction
 * fails. That is the correct direction to fail, because a file must never be
 * rejected because a parser choked, but it means the outcome has to be recorded
 * and repairable rather than silent. Hence {@link #reindex(UUID)}.
 */
@Service
public class FileIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    private final FileExtractionWorker worker;
    private final FileMetadataRepository metadataRepository;

    public FileIngestionService(FileExtractionWorker worker, FileMetadataRepository metadataRepository) {
        this.worker = worker;
        this.metadataRepository = metadataRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFileUploaded(FileUploadedEvent event) {
        try {
            worker.extract(event.userId(), event.fileId());
        } catch (Exception e) {
            // The worker records what it can on the metadata row; this is the
            // last resort, so a listener failure can never propagate back into
            // an upload that has already committed.
            log.warn("Could not ingest file {} — the file is stored and safe, and reindex can retry it. Cause: {}",
                    event.fileId(), e.toString());
        }
    }

    /**
     * Backfills files whose extraction never ran or failed. Mirrors
     * {@code POST /api/memories/reindex}.
     *
     * <p>Files with no text layer, and media no document parser handles, are
     * deliberately not retried: both are settled answers about the document
     * rather than pending work.
     *
     * @return how many files newly yielded text
     */
    public int reindex(UUID userId) {
        int extracted = 0;
        for (FileMetadata pending : metadataRepository.findPendingExtraction(userId)) {
            try {
                if (worker.extract(userId, pending.getFileId()) == ExtractionStatus.EXTRACTED) {
                    extracted++;
                }
            } catch (Exception e) {
                log.warn("Reindex failed for file {}: {}", pending.getFileId(), e.toString());
            }
        }
        return extracted;
    }
}
