-- pgvector-dependent schema. Applied only when the `pgvector` Spring profile is
-- active, because it needs the vector extension installed on the server.
--
-- Version 100 leaves room for the core schema (V1, V2, ...) to keep growing
-- independently without colliding with this line of migrations.
--
-- The dimension must match the configured embedding model:
--   nomic-embed-text -> 768
-- Changing to a model with a different dimension needs a follow-up migration.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embeddings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type  TEXT NOT NULL CHECK (owner_type IN ('MEMORY', 'MESSAGE', 'FILE_CHUNK')),
    owner_id    UUID NOT NULL,
    chunk_index INT NOT NULL DEFAULT 0,
    embedding   VECTOR(768) NOT NULL,
    model_name  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_embeddings_owner ON embeddings(owner_type, owner_id);
CREATE INDEX idx_embeddings_hnsw ON embeddings USING hnsw (embedding vector_cosine_ops);
