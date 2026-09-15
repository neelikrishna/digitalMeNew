package com.digitalself.rag;

import com.digitalself.ai.EmbeddingService;
import com.digitalself.config.RagProperties;
import com.digitalself.memory.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Combines vector similarity with keyword matching using reciprocal rank
 * fusion. Vector search alone misses exact entity/date lookups; keyword search
 * alone misses paraphrases. See docs/ai-architecture.md Section 3.
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** RRF damping constant. 60 is the value from the original RRF paper. */
    private static final int RRF_K = 60;

    private final EmbeddingService embeddingService;
    private final EmbeddingStore embeddingStore;
    private final MemoryRepository memoryRepository;
    private final MemoryVersionRepository versionRepository;
    private final MemoryTextSearch textSearch;
    private final MemoryMapper mapper;
    private final RagProperties ragProperties;

    public HybridRetriever(EmbeddingService embeddingService,
                           EmbeddingStore embeddingStore,
                           MemoryRepository memoryRepository,
                           MemoryVersionRepository versionRepository,
                           MemoryTextSearch textSearch,
                           MemoryMapper mapper,
                           RagProperties ragProperties) {
        this.embeddingService = embeddingService;
        this.embeddingStore = embeddingStore;
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.textSearch = textSearch;
        this.mapper = mapper;
        this.ragProperties = ragProperties;
    }

    @Transactional(readOnly = true)
    public List<RetrievedMemory> retrieve(UUID userId, String question) {
        int limit = ragProperties.getMaxContextMemories();

        List<ScoredMemory> vectorHits = vectorSearch(userId, question, limit);
        List<UUID> keywordHits = keywordSearch(userId, question, limit);

        if (vectorHits.isEmpty() && keywordHits.isEmpty()) {
            return List.of();
        }

        Map<UUID, Double> fused = new HashMap<>();
        Map<UUID, Double> distances = new HashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            ScoredMemory hit = vectorHits.get(rank);
            fused.merge(hit.memoryId(), rrf(rank), Double::sum);
            distances.put(hit.memoryId(), hit.distance());
        }
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            fused.merge(keywordHits.get(rank), rrf(rank), Double::sum);
        }

        List<UUID> orderedIds = fused.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        Map<UUID, Memory> memories = new HashMap<>();
        memoryRepository.findAllById(orderedIds).forEach(memory -> memories.put(memory.getId(), memory));

        Set<UUID> corrected = correctedMemoryIds(orderedIds);
        Set<UUID> keywordMatched = new HashSet<>(keywordHits);

        return orderedIds.stream()
                .map(memories::get)
                .filter(Objects::nonNull)
                .map(memory -> new RetrievedMemory(
                        memory.getId(),
                        // Decrypted here, inside the transaction, so the prompt
                        // layer never deals with ciphertext or lazy state.
                        mapper.title(memory),
                        mapper.content(memory),
                        memory.getSource(),
                        memory.getEventDate(),
                        memory.getConfidence(),
                        fused.get(memory.getId()),
                        distances.get(memory.getId()),
                        keywordMatched.contains(memory.getId()),
                        corrected.contains(memory.getId())))
                .toList();
    }

    private List<ScoredMemory> vectorSearch(UUID userId, String question, int limit) {
        try {
            float[] queryVector = embeddingService.embed(question);
            return embeddingStore.searchMemories(userId, queryVector, MemoryStatus.ACTIVE, limit).stream()
                    .filter(hit -> hit.distance() <= ragProperties.getMaxVectorDistance())
                    .toList();
        } catch (Exception e) {
            // Degrade to keyword-only rather than failing the question outright.
            log.warn("Vector search unavailable, falling back to keyword only: {}", e.toString());
            return List.of();
        }
    }

    private List<UUID> keywordSearch(UUID userId, String question, int limit) {
        return textSearch.search(userId, question, MemoryStatus.ACTIVE, limit);
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
