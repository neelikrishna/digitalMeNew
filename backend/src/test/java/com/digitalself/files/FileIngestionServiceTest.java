package com.digitalself.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * When extraction runs, and what happens when it goes wrong. The extraction
 * itself is covered by {@link FileExtractionWorkerTest}.
 */
class FileIngestionServiceTest {

    private FileExtractionWorker worker;
    private FileMetadataRepository metadataRepository;
    private FileIngestionService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        worker = mock(FileExtractionWorker.class);
        metadataRepository = mock(FileMetadataRepository.class);
        service = new FileIngestionService(worker, metadataRepository);
    }

    /**
     * The upload has already committed by the time the listener runs. If a
     * failure here escaped, it would surface as an error against a request whose
     * file was in fact stored perfectly well.
     */
    @Test
    void anExtractionFailureNeverEscapesIntoTheCommittedUpload() {
        UUID fileId = UUID.randomUUID();
        when(worker.extract(userId, fileId)).thenThrow(new IllegalStateException("disk fell over"));

        assertDoesNotThrow(() -> service.onFileUploaded(new FileUploadedEvent(userId, fileId)));
    }

    /**
     * One unparseable document must not abandon the rest of the queue — which is
     * the whole reason each file gets its own transaction.
     */
    @Test
    void oneBadFileDoesNotAbortTheBackfill() {
        FileMetadata bad = new FileMetadata(UUID.randomUUID());
        FileMetadata good = new FileMetadata(UUID.randomUUID());
        when(metadataRepository.findPendingExtraction(userId)).thenReturn(List.of(bad, good));
        when(worker.extract(userId, bad.getFileId())).thenThrow(new IllegalStateException("boom"));
        when(worker.extract(userId, good.getFileId())).thenReturn(ExtractionStatus.EXTRACTED);

        assertEquals(1, service.reindex(userId), "the healthy file must still be extracted");
        verify(worker).extract(userId, good.getFileId());
    }

    @Test
    void onlyFilesThatActuallyYieldedTextAreCounted() {
        FileMetadata scan = new FileMetadata(UUID.randomUUID());
        when(metadataRepository.findPendingExtraction(userId)).thenReturn(List.of(scan));
        when(worker.extract(any(), any())).thenReturn(ExtractionStatus.EMPTY);

        assertEquals(0, service.reindex(userId),
                "a document with no text layer is not a file that was newly extracted");
    }
}
