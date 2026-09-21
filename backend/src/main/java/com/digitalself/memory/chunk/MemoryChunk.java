package com.digitalself.memory.chunk;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One retrievable passage of a memory.
 *
 * <p>Follows its parent memory's sensitivity exactly: plaintext for an ordinary
 * memory, ciphertext for a sensitive one, never both. A chunk that leaked the
 * text of an encrypted memory would make chunking a hole straight through the
 * encryption, so the invariant is enforced here, in
 * {@link MemoryChunker}, and by a database check constraint.
 */
@Entity
@Table(name = "memory_chunks")
public class MemoryChunk {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "memory_id", nullable = false)
    private UUID memoryId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    private String content;

    @Column(name = "content_encrypted")
    private byte[] contentEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ChunkContentType contentType = ChunkContentType.TEXT;

    @Column(name = "start_offset")
    private Integer startOffset;

    @Column(name = "end_offset")
    private Integer endOffset;

    @Column(name = "start_ms")
    private Long startMs;

    @Column(name = "end_ms")
    private Long endMs;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "source_file_id")
    private UUID sourceFileId;

    @Column(name = "token_estimate")
    private Integer tokenEstimate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MemoryChunk() {
        // JPA
    }

    private MemoryChunk(UUID memoryId, ChunkDraft draft, String content, byte[] contentEncrypted) {
        this.memoryId = memoryId;
        this.chunkIndex = draft.index();
        this.content = content;
        this.contentEncrypted = contentEncrypted;
        this.contentType = draft.contentType();
        this.startOffset = draft.startOffset();
        this.endOffset = draft.endOffset();
        this.startMs = draft.startMs();
        this.endMs = draft.endMs();
        this.pageNumber = draft.pageNumber();
        this.sourceFileId = draft.sourceFileId();
        this.tokenEstimate = draft.estimateTokens();
    }

    public static MemoryChunk plaintext(UUID memoryId, ChunkDraft draft) {
        return new MemoryChunk(memoryId, draft, draft.text(), null);
    }

    public static MemoryChunk encrypted(UUID memoryId, ChunkDraft draft, byte[] ciphertext) {
        return new MemoryChunk(memoryId, draft, null, ciphertext);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMemoryId() {
        return memoryId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public byte[] getContentEncrypted() {
        return contentEncrypted;
    }

    public boolean isEncrypted() {
        return contentEncrypted != null;
    }

    public ChunkContentType getContentType() {
        return contentType;
    }

    public Integer getStartOffset() {
        return startOffset;
    }

    public Integer getEndOffset() {
        return endOffset;
    }

    public Long getStartMs() {
        return startMs;
    }

    public Long getEndMs() {
        return endMs;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public Integer getTokenEstimate() {
        return tokenEstimate == null ? 0 : tokenEstimate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
