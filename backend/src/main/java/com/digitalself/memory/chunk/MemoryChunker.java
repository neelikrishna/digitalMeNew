package com.digitalself.memory.chunk;

import com.digitalself.memory.Memory;
import com.digitalself.memory.MemoryContentCrypto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a memory's text into stored chunks.
 *
 * <p>Owns the one rule that matters most here: <b>a chunk is exactly as
 * protected as its parent memory.</b> A sensitive memory's chunks are encrypted
 * under the same key and, because their plaintext column stays null, they are
 * invisible to full-text search and are never embedded. Without that, splitting
 * a memory into passages would quietly undo the encryption that was applied to
 * it.
 *
 * <p>Chunking is deterministic and fast — no model involved — so unlike
 * embedding it can safely run inside the caller's transaction.
 */
@Service
public class MemoryChunker {

    private final MemoryChunkRepository chunkRepository;
    private final MemoryContentCrypto crypto;
    private final Map<ChunkContentType, ChunkingStrategy> strategies;

    public MemoryChunker(MemoryChunkRepository chunkRepository,
                         MemoryContentCrypto crypto,
                         List<ChunkingStrategy> strategies) {
        this.chunkRepository = chunkRepository;
        this.crypto = crypto;
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(ChunkingStrategy::contentType, Function.identity()));
    }

    /**
     * Replaces this memory's chunks with a fresh split of the given text.
     *
     * <p>Replace rather than append: a revised memory whose old passages
     * lingered would let retrieval answer from text the owner has already
     * corrected.
     *
     * @param plaintext the memory's readable text, already decrypted by the caller
     * @return the stored chunks, in order
     */
    @Transactional
    public List<MemoryChunk> rechunk(Memory memory, String plaintext, ChunkContentType contentType) {
        chunkRepository.deleteByMemoryId(memory.getId());

        ChunkingStrategy strategy = strategies.get(contentType);
        if (strategy == null) {
            throw new IllegalStateException("No chunking strategy registered for " + contentType);
        }

        List<ChunkDraft> drafts = strategy.split(plaintext);
        List<MemoryChunk> chunks = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            chunks.add(memory.isSensitive()
                    ? MemoryChunk.encrypted(memory.getId(), draft,
                            crypto.encrypt(memory.getId(), draft.text()))
                    : MemoryChunk.plaintext(memory.getId(), draft));
        }
        return chunkRepository.saveAll(chunks);
    }

    /** Readable text of a chunk, decrypting only if its memory is sensitive. */
    public String readableText(MemoryChunk chunk) {
        return chunk.isEncrypted()
                ? crypto.decrypt(chunk.getMemoryId(), chunk.getContentEncrypted())
                : chunk.getContent();
    }

    @Transactional(readOnly = true)
    public List<MemoryChunk> chunksOf(java.util.UUID memoryId) {
        return chunkRepository.findByMemoryIdOrderByChunkIndexAsc(memoryId);
    }
}
