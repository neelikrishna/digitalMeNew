package com.digitalself.rag;

import com.digitalself.ai.EmbeddingService;
import com.digitalself.config.RagProperties;
import com.digitalself.memory.*;
import com.digitalself.memory.chunk.MemoryChunk;
import com.digitalself.memory.chunk.MemoryChunkRepository;
import com.digitalself.memory.chunk.MemoryChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Finds the passages most relevant to a question, combining vector similarity
 * with full-text ranking through reciprocal rank fusion.
 *
 * <p>Vector search alone misses exact entity and date lookups; full-text alone
 * misses paraphrases. Both are run over the same unit — chunks — so their ranked
 * lists can be fused meaningfully and so a citation can name a passage rather
 * than a whole document.
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** RRF damping constant. 60 is the value from the original paper. */
    private static final int RRF_K = 60;

    /**
     * Over-fetch before fusing. Each list is asked for more than the final
     * budget so a passage ranked modestly by both can still beat one ranked
     * highly by a single method — which is the entire point of fusing.
     */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final EmbeddingService embeddingService;
    private final EmbeddingStore embeddingStore;
    private final MemoryChunkRepository chunkRepository;
    private final MemoryChunker chunker;
    private final MemoryRepository memoryRepository;
    private final MemoryVersionRepository versionRepository;
    private final MemoryTextSearch textSearch;
    private final RagProperties ragProperties;

    public HybridRetriever(EmbeddingService embeddingService,
                           EmbeddingStore embeddingStore,
                           MemoryChunkRepository chunkRepository,
                           MemoryChunker chunker,
                           MemoryRepository memoryRepository,
                           MemoryVersionRepository versionRepository,
                           MemoryTextSearch textSearch,
                           RagProperties ragProperties) {
        this.embeddingService = embeddingService;
        this.embeddingStore = embeddingStore;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.textSearch = textSearch;
        this.ragProperties = ragProperties;
    }

    @Transactional(readOnly = true)
    public List<RetrievedPassage> retrieve(UUID userId, String question) {
        int limit = ragProperties.getMaxContextMemories();
        int candidates = limit * CANDIDATE_MULTIPLIER;

        List<ScoredChunk> vectorHits = vectorSearch(userId, question, candidates);
        List<UUID> keywordHits = keywordSearch(userId, question, candidates);

        if (vectorHits.isEmpty() && keywordHits.isEmpty()) {
            return List.of();
        }

        Map<UUID, Double> fused = new HashMap<>();
        Map<UUID, Double> distances = new HashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            ScoredChunk hit = vectorHits.get(rank);
            fused.merge(hit.chunkId(), rrf(rank), Double::sum);
            distances.put(hit.chunkId(), hit.distance());
        }
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            fused.merge(keywordHits.get(rank), rrf(rank), Double::sum);
        }

        List<UUID> ordered = fused.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        return assemble(ordered, fused, distances, new HashSet<>(keywordHits));
    }

    /**
     * Joins each surviving chunk back to its memory for the context a citation
     * needs — title, date, provenance — and decrypts sensitive text.
     */
    private List<RetrievedPassage> assemble(List<UUID> chunkIds,
                                            Map<UUID, Double> fused,
                                            Map<UUID, Double> distances,
                                            Set<UUID> keywordMatched) {
        Map<UUID, MemoryChunk> chunks = new HashMap<>();
        chunkRepository.findByIdIn(chunkIds).forEach(chunk -> chunks.put(chunk.getId(), chunk));

        List<UUID> memoryIds = chunks.values().stream().map(MemoryChunk::getMemoryId).distinct().toList();
        Map<UUID, Memory> memories = new HashMap<>();
        memoryRepository.findAllById(memoryIds).forEach(memory -> memories.put(memory.getId(), memory));

        Set<UUID> corrected = correctedMemoryIds(memoryIds);

        List<RetrievedPassage> passages = new ArrayList<>();
        int tokenBudget = ragProperties.getMaxContextTokens();
        int spent = 0;

        for (UUID chunkId : chunkIds) {
            MemoryChunk chunk = chunks.get(chunkId);
            if (chunk == null) {
                continue;
            }
            Memory memory = memories.get(chunk.getMemoryId());
            if (memory == null) {
                continue;
            }
            // Stop at the budget rather than truncating mid-passage: a clipped
            // passage is worse evidence than one fewer passage.
            if (spent + chunk.getTokenEstimate() > tokenBudget && !passages.isEmpty()) {
                break;
            }
            spent += chunk.getTokenEstimate();

            passages.add(new RetrievedPassage(
                    chunk.getId(),
                    memory.getId(),
                    // Plaintext title. A sensitive memory has neither embeddings
                    // nor a full-text entry, so it cannot be retrieved at all and
                    // its encrypted title is never reached here.
                    memory.getTitle(),
                    chunker.readableText(chunk),
                    chunk.getContentType(),
                    memory.getSource(),
                    memory.getEventDate(),
                    memory.getConfidence(),
                    corrected.contains(memory.getId()),
                    chunk.getSourceFileId(),
                    chunk.getPageNumber(),
                    chunk.getStartMs(),
                    chunk.getEndMs(),
                    chunk.getTokenEstimate(),
                    fused.getOrDefault(chunkId, 0.0),
                    distances.get(chunkId),
                    keywordMatched.contains(chunkId)));
        }
        return passages;
    }

    private List<ScoredChunk> vectorSearch(UUID userId, String question, int limit) {
        try {
            float[] queryVector = embeddingService.embed(question);
            return embeddingStore.searchChunks(userId, queryVector, MemoryStatus.ACTIVE, limit).stream()
                    .filter(hit -> hit.distance() <= ragProperties.getMaxVectorDistance())
                    .toList();
        } catch (Exception e) {
            // Degrade to keyword-only rather than failing the question outright.
            log.warn("Vector search unavailable, falling back to full-text only: {}", e.toString());
            return List.of();
        }
    }

    private List<UUID> keywordSearch(UUID userId, String question, int limit) {
        return textSearch.searchChunks(userId, question, MemoryStatus.ACTIVE, limit);
    }

    private Set<UUID> correctedMemoryIds(List<UUID> memoryIds) {
        if (memoryIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> corrected = new HashSet<>();
        for (MemoryVersionRepository.VersionCount count : versionRepository.countVersionsForMemories(memoryIds)) {
            if (count.getVersionCount() > 1) {
                corrected.add(count.getMemoryId());
            }
        }
        return corrected;
    }

    private static double rrf(int zeroBasedRank) {
        return 1.0 / (RRF_K + zeroBasedRank + 1);
    }
}
