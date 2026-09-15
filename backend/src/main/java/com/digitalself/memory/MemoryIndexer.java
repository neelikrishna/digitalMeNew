package com.digitalself.memory;

import com.digitalself.ai.EmbeddingService;
import com.digitalself.ai.TextChunker;
import com.digitalself.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generates and stores embeddings for memories.
 *
 * <p>Runs after the database transaction commits so an Ollama call — which can
 * take seconds — never holds a Postgres transaction open. The trade-off is that
 * indexing can fail after the memory is safely saved; that is deliberate. A
 * memory must never be lost because the model was unreachable, so failures are
 * logged and the memory is picked up later by {@link #backfill(UUID, int)}.
 */
@Component
public class MemoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexer.class);

    private final MemoryRepository memoryRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingStore embeddingStore;
    private final RagProperties ragProperties;

    public MemoryIndexer(MemoryRepository memoryRepository,
                         EmbeddingService embeddingService,
                         EmbeddingStore embeddingStore,
                         RagProperties ragProperties) {
        this.memoryRepository = memoryRepository;
        this.embeddingService = embeddingService;
        this.embeddingStore = embeddingStore;
        this.ragProperties = ragProperties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemoryContentChanged(MemoryContentChangedEvent event) {
        try {
            index(event.memoryId());
        } catch (Exception e) {
            log.warn("Could not index memory {} — it stays searchable by keyword and will be picked up by a backfill. Cause: {}",
                    event.memoryId(), e.toString());
        }
    }

    public void index(UUID memoryId) {
        Memory memory = memoryRepository.findById(memoryId).orElse(null);
        if (memory == null) {
            return;
        }

        // Sensitive memories are not embedded. An embedding is a lossy but real
        // representation of the text, and storing one unencrypted would undo the
        // point of encrypting the memory. Deleting first matters: a memory that
        // has just been promoted to sensitive must not keep the vector derived
        // from its former plaintext.
        if (memory.isSensitive()) {
            embeddingStore.deleteForOwner(EmbeddingOwnerType.MEMORY, memoryId);
            return;
        }

        String text = memory.getTitle() == null || memory.getTitle().isBlank()
                ? memory.getContent()
                : memory.getTitle() + "\n\n" + memory.getContent();

        List<String> chunks = TextChunker.chunk(text, ragProperties.getChunkSize(), ragProperties.getChunkOverlap());
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            vectors.add(embeddingService.embed(chunk));
        }
        embeddingStore.replaceForOwner(EmbeddingOwnerType.MEMORY, memoryId, vectors, embeddingService.modelName());
    }

    /**
     * Indexes memories that have no embedding — those created while the model
     * was unreachable. Returns how many were successfully indexed.
     */
    public int backfill(UUID userId, int limit) {
        List<UUID> pending = embeddingStore.findMemoryIdsMissingEmbeddings(userId, limit);
        int indexed = 0;
        for (UUID memoryId : pending) {
            try {
                index(memoryId);
                indexed++;
            } catch (Exception e) {
                log.warn("Backfill failed for memory {}: {}", memoryId, e.toString());
            }
        }
        return indexed;
    }
}
