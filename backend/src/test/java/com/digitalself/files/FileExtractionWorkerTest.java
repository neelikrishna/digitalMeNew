package com.digitalself.files;

import com.digitalself.audit.AuditService;
import com.digitalself.config.FileExtractionProperties;
import com.digitalself.crypto.TextCrypto;
import com.digitalself.memory.MemorySource;
import com.digitalself.memory.MemoryService;
import com.digitalself.memory.MemoryStatus;
import com.digitalself.memory.MemoryType;
import com.digitalself.memory.PrivacyLevel;
import com.digitalself.memory.dto.CreateMemoryRequest;
import com.digitalself.memory.dto.MemoryResponse;
import com.digitalself.memory.dto.ReviseMemoryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The decisions this worker makes about <i>where extracted text goes</i> —
 * which {@link FileTextExtractor} deliberately knows nothing about.
 */
class FileExtractionWorkerTest {

    private StoredFileRepository fileRepository;
    private FileMetadataRepository metadataRepository;
    private FileTextExtractor extractor;
    private FileService fileService;
    private MemoryService memoryService;
    private MemoryFileLinkStore linkStore;
    private TextCrypto textCrypto;
    private FileExtractionWorker worker;

    private final UUID userId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fileRepository = mock(StoredFileRepository.class);
        metadataRepository = mock(FileMetadataRepository.class);
        extractor = mock(FileTextExtractor.class);
        fileService = mock(FileService.class);
        memoryService = mock(MemoryService.class);
        linkStore = mock(MemoryFileLinkStore.class);
        textCrypto = mock(TextCrypto.class);

        when(extractor.supports(anyString())).thenReturn(true);
        when(fileService.readContent(any())).thenReturn("irrelevant".getBytes(StandardCharsets.UTF_8));
        when(metadataRepository.findById(fileId)).thenReturn(Optional.of(new FileMetadata(fileId)));
        when(memoryService.create(any(), any())).thenReturn(memoryResponse(UUID.randomUUID()));

        worker = new FileExtractionWorker(fileRepository, metadataRepository, extractor, fileService,
                memoryService, linkStore, textCrypto, mock(AuditService.class), new FileExtractionProperties());
    }

    @Test
    void extractedTextBecomesAMemoryMarkedAsComingFromAFile() {
        givenFile(false, "offer.pdf");
        givenExtraction("The mortgage offer expires on 14 March.", false);

        assertEquals(ExtractionStatus.EXTRACTED, worker.extract(userId, fileId));

        CreateMemoryRequest request = capturedCreate();
        assertEquals(MemorySource.FILE_EXTRACTION, request.source(),
                "a document's text must stay distinguishable from something the owner said");
        assertEquals("offer.pdf", request.title());
        assertTrue(request.content().contains("mortgage offer expires"));
        assertEquals(Boolean.FALSE, request.sensitive());
    }

    @Test
    void theDerivedMemoryIsAttachedToTheFileItCameFrom() {
        givenFile(false, "offer.pdf");
        givenExtraction("Some text worth indexing.", false);

        worker.extract(userId, fileId);

        verify(linkStore).link(any(UUID.class), eq(fileId));
    }

    /**
     * The encryption promise is only kept if it survives extraction: text read
     * out of a sensitive file must not land in a plaintext column, and the
     * memory derived from it must be sensitive too, or MemoryIndexer will
     * happily embed the very words the encryption was protecting.
     */
    @Test
    void aSensitiveFileLeavesNoPlaintextAndDerivesASensitiveMemory() {
        givenFile(true, "diagnosis.pdf");
        givenExtraction("A private medical detail.", false);
        when(textCrypto.encrypt(eq(TextCrypto.FILE), eq(fileId), anyString()))
                .thenReturn(new byte[]{1, 2, 3});

        worker.extract(userId, fileId);

        FileMetadata saved = capturedMetadata();
        assertNull(saved.getExtractedText(), "a sensitive file's text must never sit in a plaintext column");
        assertArrayEquals(new byte[]{1, 2, 3}, saved.getExtractedTextEncrypted());
        assertEquals(Boolean.TRUE, capturedCreate().sensitive());
    }

    @Test
    void anOrdinaryFileStoresItsTextInTheClearSoItStaysSearchable() {
        givenFile(false, "notes.txt");
        givenExtraction("Searchable words here.", false);

        worker.extract(userId, fileId);

        FileMetadata saved = capturedMetadata();
        assertEquals("Searchable words here.", saved.getExtractedText());
        assertNull(saved.getExtractedTextEncrypted());
        verify(textCrypto, never()).encrypt(any(), any(), any());
    }

    @Test
    void aFailedParseIsRecordedAsRetryableAndCostsNoData() {
        givenFile(false, "broken.pdf");
        when(extractor.extract(any(), anyString()))
                .thenThrow(new FileExtractionException("parser exploded", new RuntimeException()));

        assertEquals(ExtractionStatus.FAILED, worker.extract(userId, fileId));

        FileMetadata saved = capturedMetadata();
        assertNotNull(saved.getExtractionError(), "the reason must be visible without reading logs");
        assertNull(saved.getExtractedAt());
        verify(memoryService, never()).create(any(), any());
        verify(fileRepository, never()).delete(any());
    }

    /**
     * A scan has no text layer. Recording that as EMPTY rather than FAILED is
     * what keeps the backfill from retrying it on every run, forever.
     */
    @Test
    void aDocumentWithNoTextLayerIsSettledNotRetryable() {
        givenFile(false, "scan.pdf");
        givenExtraction("", false);

        assertEquals(ExtractionStatus.EMPTY, worker.extract(userId, fileId));
        verify(memoryService, never()).create(any(), any());
    }

    @Test
    void textTooShortToBeWorthAMemoryIsTreatedAsEmpty() {
        givenFile(false, "cover-page.pdf");
        givenExtraction("1", false);

        assertEquals(ExtractionStatus.EMPTY, worker.extract(userId, fileId),
                "a lone page number must not become a memory saying \"1\"");
    }

    @Test
    void photosAndAudioAreRecordedAsUnsupportedRatherThanFailed() {
        givenFile(false, "holiday.jpg");
        when(extractor.supports("image/jpeg")).thenReturn(false);

        assertEquals(ExtractionStatus.UNSUPPORTED, worker.extract(userId, fileId));
        verify(extractor, never()).extract(any(), anyString());
        verify(memoryService, never()).create(any(), any());
    }

    @Test
    void truncationIsDisclosedInTheMemoryText() {
        givenFile(false, "huge.pdf");
        givenExtraction("The first part of a very long document.", true);

        worker.extract(userId, fileId);

        assertTrue(capturedCreate().content().contains("truncated"),
                "an answer drawn from a partial document should be able to say so");
    }

    /**
     * Re-extraction revises the existing memory. Creating a second one would
     * double every retrieval hit for the same document.
     */
    @Test
    void reExtractingRevisesTheExistingMemoryInsteadOfAddingAnother() {
        UUID existing = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata(fileId);
        metadata.setDerivedMemoryId(existing);
        when(metadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

        givenFile(false, "offer.pdf");
        givenExtraction("Updated text of the document.", false);

        worker.extract(userId, fileId);

        verify(memoryService, never()).create(any(), any());
        verify(memoryService).revise(eq(userId), eq(existing), any(ReviseMemoryRequest.class));
    }

    private void givenFile(boolean sensitive, String filename) {
        String mimeType = filename.endsWith(".jpg") ? "image/jpeg" : "application/pdf";
        StoredFile file = new StoredFile(userId, "ab/blob.enc", "hash", mimeType, filename, sensitive);
        setId(file, fileId);
        when(fileRepository.findByIdAndUserId(fileId, userId)).thenReturn(Optional.of(file));
    }

    private void givenExtraction(String text, boolean truncated) {
        when(extractor.extract(any(), anyString()))
                .thenReturn(new FileTextExtractor.Result(text, truncated));
    }

    private CreateMemoryRequest capturedCreate() {
        ArgumentCaptor<CreateMemoryRequest> captor = ArgumentCaptor.forClass(CreateMemoryRequest.class);
        verify(memoryService).create(eq(userId), captor.capture());
        return captor.getValue();
    }

    private FileMetadata capturedMetadata() {
        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(metadataRepository).save(captor.capture());
        return captor.getValue();
    }

    private static void setId(StoredFile file, UUID id) {
        try {
            var field = StoredFile.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(file, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MemoryResponse memoryResponse(UUID id) {
        return new MemoryResponse(id, MemoryType.SEMANTIC, "t", "c", MemorySource.FILE_EXTRACTION,
                null, null, 1.0f, PrivacyLevel.PRIVATE, MemoryStatus.ACTIVE, false, Set.of(),
                Instant.now(), Instant.now());
    }
}
