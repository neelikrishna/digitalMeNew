-- Passages within a memory.
--
-- Until now a memory was the smallest retrievable thing, which made three
-- requirements impossible: citing the passage an answer came from, pointing at a
-- time range inside a recording, and re-ranking (there was no chunk text stored
-- to re-rank). A 200-page PDF was one memory, retrieved whole or not at all.
--
-- Chunks sit between memories and embeddings:
--     memories (1) --< memory_chunks (N) --< embeddings (1 per chunk)

CREATE TABLE memory_chunks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    memory_id         UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    chunk_index       INT  NOT NULL,

    -- Same plaintext/ciphertext split as memories, and for the same reason: a
    -- chunk of a sensitive memory must be no more readable than its parent.
    -- Chunking must never become a hole through the encryption.
    content           TEXT,
    content_encrypted BYTEA,

    content_type      TEXT NOT NULL DEFAULT 'TEXT' CHECK (content_type IN
                          ('TEXT', 'TRANSCRIPT_SEGMENT', 'CHAT_WINDOW', 'CAPTION')),

    -- Where this passage sits in the source, so a citation can point at it.
    -- All optional: which ones apply depends on what the source was.
    start_offset      INT,      -- character span, for documents and notes
    end_offset        INT,
    start_ms          BIGINT,   -- time span, for audio and video
    end_ms            BIGINT,
    page_number       INT,      -- for paginated documents

    source_file_id    UUID REFERENCES files(id) ON DELETE SET NULL,

    -- Rough, for budgeting the context window before a model call rather than
    -- discovering the prompt was too long afterwards.
    token_estimate    INT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (memory_id, chunk_index),

    CONSTRAINT memory_chunks_content_storage CHECK (
        (content IS NOT NULL AND content_encrypted IS NULL)
        OR
        (content IS NULL AND content_encrypted IS NOT NULL)
    )
);

CREATE INDEX idx_memory_chunks_memory ON memory_chunks(memory_id, chunk_index);
CREATE INDEX idx_memory_chunks_file ON memory_chunks(source_file_id);

-- Chunk-level full-text search. Vector search returns chunks, so keyword search
-- must too — fusing ranked lists that identify different things would be
-- meaningless.
--
-- Chunks of sensitive memories have a NULL content column, so this expression is
-- NULL for them and they never match. The exclusion is a property of the storage
-- model rather than a filter someone has to remember to apply.
CREATE INDEX idx_memory_chunks_fts ON memory_chunks
    USING GIN (to_tsvector('english', content));
