package com.digitalself.rag;

import com.digitalself.ai.EmbeddingService;
import com.digitalself.config.RagProperties;
import com.digitalself.memory.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridRetrieverTest {

    private EmbeddingService embeddingService;
    private EmbeddingStore embeddingStore;
    private MemoryRepository memoryRepository;
    private MemoryVersionRepository versionRepository;
    private MemoryTextSearch textSearch;
    private HybridRetriever retriever;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        embeddingStore = mock(EmbeddingStore.class);
        memoryRepository = mock(MemoryRepository.class);
        versionRepository = mock(MemoryVersionRepository.class);
        textSearch = mock(MemoryTextSearch.class);

        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(versionRepository.countVersionsForMemories(anyCollection())).thenReturn(List.of());

        retriever = new HybridRetriever(embeddingService, embeddingStore, memoryRepository,
                versionRepository, textSearch, new MemoryMapper(mock(MemoryContentCrypto.class)),
                new RagProperties());
    }

    @Test
    void returnsNothingWhenBothSearchesAreEmpty() {
        when(embeddingStore.searchMemories(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(textSearch.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());

        assertTrue(retriever.retrieve(userId, "anything").isEmpty());
    }

    @Test
    void discardsVectorHitsBeyondTheDistanceCeiling() {
        Memory far = memoryWithId();
        when(embeddingStore.searchMemories(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ScoredMemory(far.getId(), 0.95))); // default ceiling is 0.6
        when(textSearch.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());

        assertTrue(retriever.retrieve(userId, "loosely related question").isEmpty());
        verify(memoryRepository, never()).findAllById(any());
    }

    @Test
    void memoryFoundByBothSearchesOutranksOneFoundByOnly() {
        Memory both = memoryWithId();
        Memory vectorOnly = memoryWithId();

        when(embeddingStore.searchMemories(any(), any(), any(), anyInt())).thenReturn(List.of(
                new ScoredMemory(vectorOnly.getId(), 0.10),
                new ScoredMemory(both.getId(), 0.20)));
        when(textSearch.search(any(), anyString(), any(), anyInt())).thenReturn(List.of(both.getId()));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(both, vectorOnly));

        List<RetrievedMemory> results = retriever.retrieve(userId, "question");

        assertEquals(2, results.size());
        assertEquals(both.getId(), results.get(0).memoryId(),
                "a memory matched by vector AND keyword should rank above a vector-only match");
        assertTrue(results.get(0).keywordMatch());
        assertFalse(results.get(1).keywordMatch());
    }

    @Test
    void degradesToKeywordOnlyWhenEmbeddingFails() {
        Memory keywordHit = memoryWithId();
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("ollama down"));
        when(textSearch.search(any(), anyString(), any(), anyInt())).thenReturn(List.of(keywordHit.getId()));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(keywordHit));

        List<RetrievedMemory> results = retriever.retrieve(userId, "question");

        assertEquals(1, results.size());
        assertNull(results.get(0).vectorDistance());
        assertTrue(results.get(0).keywordMatch());
    }

    @Test
    void marksRevisedMemoriesAsCorrected() {
        Memory revised = memoryWithId();
        when(embeddingStore.searchMemories(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ScoredMemory(revised.getId(), 0.1)));
        when(textSearch.search(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(memoryRepository.findAllById(any())).thenReturn(List.of(revised));
        when(versionRepository.countVersionsForMemories(anyCollection()))
                .thenReturn(List.of(versionCount(revised.getId(), 3)));

        List<RetrievedMemory> results = retriever.retrieve(userId, "question");

        assertTrue(results.get(0).corrected());
        assertEquals("corrected by owner", results.get(0).provenanceLabel());
    }

    private MemoryVersionRepository.VersionCount versionCount(UUID memoryId, long count) {
        return new MemoryVersionRepository.VersionCount() {
            @Override
            public UUID getMemoryId() {
                return memoryId;
            }

            @Override
            public long getVersionCount() {
                return count;
            }
        };
    }

    private Memory memoryWithId() {
        Memory memory = new Memory(userId, MemoryType.EPISODIC, "Title", "Content.",
                MemorySource.USER_INPUT, LocalDate.of(2020, 1, 1), (short) 3, 1.0f, PrivacyLevel.PRIVATE);
        try {
            var field = Memory.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(memory, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return memory;
    }
}
