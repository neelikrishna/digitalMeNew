package com.digitalself.memory;

import com.digitalself.ai.EmbeddingService;
import com.digitalself.memory.chunk.ChunkContentType;
import com.digitalself.memory.chunk.MemoryChunk;
import com.digitalself.memory.chunk.MemoryChunkRepository;
import com.digitalself.memory.chunk.MemoryChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits a memory into passages and embeds them.
 *
 * <p>Two steps with deliberately different transaction behaviour. Chunking is
 * deterministic and fast, so it runs in its own short transaction. Embedding
 * calls a model and can take seconds, so it runs outside any transaction — a
 * Postgres transaction must never be held open across a model call.
 *
 * <p>The whole thing runs after the memory's own transaction commits. That means
 * a memory can be saved successfully and left unindexed if the model was down,
 * which is the correct direction to fail: a memory must never be lost because an
 * embedding could not be produced. Failures are logged and repaired by
 * {@link #backfill(UUID, int)}.
 */
@Component
public class MemoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexer.class);

    private final MemoryRepository memoryRepository;
    private final MemoryMapper mapper;
    private final MemoryChunker chunker;
    private final MemoryChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingStore embeddingStore;

    public MemoryIndexer(MemoryRepository memoryRepository,
                         MemoryMapper mapper,
                         MemoryChunker chunker,
                         MemoryChunkRepository chunkRepository,
                         EmbeddingService embeddingService,
                         EmbeddingStore embeddingStore) {
        this.memoryRepository = memoryRepository;
        this.mapper = mapper;
        this.chunker = chunker;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.embeddingStore = embeddingStore;
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

        List<MemoryChunk> chunks = rechunk(memory);

        // Sensitive memories are chunked — so the owner can still read them back —
        // but never embedded. An embedding is a lossy but real representation of
        // the text, and storing one unencrypted would undo the encryption. Any
        // vector left over from before the memory was made sensitive goes too.
        if (memory.isSensitive()) {
            embeddingStore.deleteForOwner(EmbeddingOwnerType.MEMORY, memoryId);
            chunks.forEach(chunk -> embeddingStore.deleteForOwner(EmbeddingOwnerType.CHUNK, chunk.getId()));
            return;
        }

        embed(chunks);

        // Drop the pre-chunking memory-level vector once passage vectors exist,
        // so the same memory cannot be matched twice by two different units.
        embeddingStore.deleteForOwner(EmbeddingOwnerType.MEMORY, memoryId);
    }

    private List<MemoryChunk> rechunk(Memory memory) {
        String title = mapper.title(memory);
        String content = mapper.content(memory);
        String text = title == null || title.isBlank() ? content : title + "\n\n" + content;
        return chunker.rechunk(memory, text, ChunkContentType.TEXT);
    }

    /** Outside any transaction: one model call per chunk, none of them holding a lock. */
    private void embed(List<MemoryChunk> chunks) {
        for (MemoryChunk chunk : chunks) {
            String text = chunker.readableText(chunk);
            if (text == null || text.isBlank()) {
                continue;
            }
            List<float[]> vector = new ArrayList<>(1);
            vector.add(embeddingService.embed(text));
            embeddingStore.replaceForOwner(
                    EmbeddingOwnerType.CHUNK, chunk.getId(), vector, embeddingService.modelName());
        }
    }

    /**
     * Indexes memories that were never chunked, and chunks that were never
     * embedded — the state left behind when the model was unreachable, and the
     * migration path for memories written before chunking existed.
     *
     * @return how many memories were newly indexed
     */
    public int backfill(UUID userId, int limit) {
        int indexed = 0;

        for (UUID memoryId : chunkRepository.findMemoryIdsWithoutChunks(userId).stream().limit(limit).toList()) {
            try {
                index(memoryId);
                indexed++;
            } catch (Exception e) {
                log.warn("Backfill failed for memory {}: {}", memoryId, e.toString());
            }
        }

        // Chunks that exist but were never embedded, typically because the model
        // was down after a successful chunking pass.
        for (UUID chunkId : embeddingStore.findChunkIdsMissingEmbeddings(userId, limit)) {
            try {
                chunkRepository.findById(chunkId).ifPresent(chunk -> embed(List.of(chunk)));
            } catch (Exception e) {
                log.warn("Backfill failed for chunk {}: {}", chunkId, e.toString());
            }
        }
        return indexed;
    }
}
