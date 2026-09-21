package com.digitalself.rag;

import com.digitalself.ai.EmbeddingService;
import com.digitalself.config.RagProperties;
import com.digitalself.memory.*;
import com.digitalself.memory.chunk.ChunkContentType;
import com.digitalself.memory.chunk.MemoryChunk;
import com.digitalself.memory.chunk.MemoryChunkRepository;
import com.digitalself.memory.chunk.MemoryChunker;
import com.digitalself.memory.chunk.ChunkDraft;
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
    private MemoryChunkRepository chunkRepository;
    private MemoryChunker chunker;
    private MemoryRepository memoryRepository;
    private MemoryVersionRepository versionRepository;
    private MemoryTextSearch textSearch;
    private HybridRetriever retriever;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        embeddingStore = mock(EmbeddingStore.class);
        chunkRepository = mock(MemoryChunkRepository.class);
        chunker = mock(MemoryChunker.class);
        memoryRepository = mock(MemoryRepository.class);
        versionRepository = mock(MemoryVersionRepository.class);
        textSearch = mock(MemoryTextSearch.class);

        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(versionRepository.countVersionsForMemories(anyCollection())).thenReturn(List.of());
        when(chunker.readableText(any())).thenAnswer(i -> i.getArgument(0, MemoryChunk.class).getContent());

        retriever = new HybridRetriever(embeddingService, embeddingStore, chunkRepository, chunker,
                memoryRepository, versionRepository, textSearch, new RagProperties());
    }

    @Test
    void returnsNothingWhenBothSearchesAreEmpty() {
        when(embeddingStore.searchChunks(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of());

        assertTrue(retriever.retrieve(userId, "anything").isEmpty());
    }

    @Test
    void discardsVectorHitsBeyondTheDistanceCeiling() {
        Memory memory = memoryWithId();
        MemoryChunk far = chunk(memory.getId(), "Loosely related.");

        // Default ceiling is 0.6.
        when(embeddingStore.searchChunks(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(far.getId(), memory.getId(), 0.95)));
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of());

        assertTrue(retriever.retrieve(userId, "a question").isEmpty());
        verify(chunkRepository, never()).findByIdIn(any());
    }

    @Test
    void aPassageFoundByBothSearchesOutranksOneFoundByOnly() {
        Memory memory = memoryWithId();
        MemoryChunk both = chunk(memory.getId(), "Found twice.");
        MemoryChunk vectorOnly = chunk(memory.getId(), "Found once.");

        when(embeddingStore.searchChunks(any(), any(), any(), anyInt())).thenReturn(List.of(
                new ScoredChunk(vectorOnly.getId(), memory.getId(), 0.10),
                new ScoredChunk(both.getId(), memory.getId(), 0.20)));
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of(both.getId()));
        when(chunkRepository.findByIdIn(any())).thenReturn(List.of(both, vectorOnly));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(memory));

        List<RetrievedPassage> results = retriever.retrieve(userId, "question");

        assertEquals(2, results.size());
        assertEquals(both.getId(), results.get(0).chunkId(),
                "a passage matched by vector AND keyword should rank above a vector-only match");
        assertTrue(results.get(0).keywordMatch());
        assertFalse(results.get(1).keywordMatch());
    }

    @Test
    void degradesToKeywordOnlyWhenEmbeddingFails() {
        Memory memory = memoryWithId();
        MemoryChunk hit = chunk(memory.getId(), "Keyword hit.");

        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("ollama down"));
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of(hit.getId()));
        when(chunkRepository.findByIdIn(any())).thenReturn(List.of(hit));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(memory));

        List<RetrievedPassage> results = retriever.retrieve(userId, "question");

        assertEquals(1, results.size());
        assertNull(results.get(0).vectorDistance());
        assertTrue(results.get(0).keywordMatch());
    }

    @Test
    void carriesTheMemoryContextAPassageNeedsToBeCited() {
        Memory memory = memoryWithId();
        MemoryChunk hit = chunk(memory.getId(), "The passage text.");

        when(embeddingStore.searchChunks(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(hit.getId(), memory.getId(), 0.1)));
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(chunkRepository.findByIdIn(any())).thenReturn(List.of(hit));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(memory));

        RetrievedPassage passage = retriever.retrieve(userId, "question").get(0);

        assertEquals(memory.getId(), passage.memoryId());
        assertEquals("Title", passage.memoryTitle());
        assertEquals("The passage text.", passage.text());
        assertEquals(LocalDate.of(2020, 1, 1), passage.eventDate());
        assertEquals("stated by owner", passage.provenanceLabel());
    }

    @Test
    void marksRevisedMemoriesAsCorrected() {
        Memory memory = memoryWithId();
        MemoryChunk hit = chunk(memory.getId(), "Revised text.");

        when(embeddingStore.searchChunks(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(hit.getId(), memory.getId(), 0.1)));
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(chunkRepository.findByIdIn(any())).thenReturn(List.of(hit));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(memory));
        when(versionRepository.countVersionsForMemories(anyCollection()))
                .thenReturn(List.of(versionCount(memory.getId(), 3)));

        RetrievedPassage passage = retriever.retrieve(userId, "question").get(0);

        assertTrue(passage.corrected());
        assertEquals("corrected by owner", passage.provenanceLabel());
    }

    /**
     * The context budget is a privacy control as much as a cost one: only the
     * minimum relevant context should reach the model.
     */
    @Test
    void stopsAtTheTokenBudgetRatherThanClippingAPassage() {
        RagProperties tightBudget = new RagProperties();
        tightBudget.setMaxContextTokens(30); // ~120 characters
        retriever = new HybridRetriever(embeddingService, embeddingStore, chunkRepository, chunker,
                memoryRepository, versionRepository, textSearch, tightBudget);

        Memory memory = memoryWithId();
        MemoryChunk first = chunk(memory.getId(), "x".repeat(100));
        MemoryChunk second = chunk(memory.getId(), "y".repeat(100));

        when(embeddingStore.searchChunks(any(), any(), any(), anyInt())).thenReturn(List.of(
                new ScoredChunk(first.getId(), memory.getId(), 0.1),
                new ScoredChunk(second.getId(), memory.getId(), 0.2)));
        when(textSearch.searchChunks(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(chunkRepository.findByIdIn(any())).thenReturn(List.of(first, second));
        when(memoryRepository.findAllById(any())).thenReturn(List.of(memory));

        List<RetrievedPassage> results = retriever.retrieve(userId, "question");

        assertEquals(1, results.size(), "the second passage exceeds the budget and is dropped whole");
        assertEquals(100, results.get(0).text().length(), "the kept passage is not truncated");
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

    private MemoryChunk chunk(UUID memoryId, String text) {
        return MemoryChunk.plaintext(memoryId,
                new ChunkDraft(0, text, ChunkContentType.TEXT, 0, text.length(), null, null, null, null));
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
