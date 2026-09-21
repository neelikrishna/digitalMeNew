-- Point embeddings at chunks.
--
-- Lives in the vector migration path, not the core one, because the embeddings
-- table only exists when the pgvector profile is active.

ALTER TABLE embeddings DROP CONSTRAINT embeddings_owner_type_check;

ALTER TABLE embeddings ADD CONSTRAINT embeddings_owner_type_check
    CHECK (owner_type IN ('MEMORY', 'CHUNK', 'MESSAGE', 'FILE_CHUNK'));

-- MEMORY rows are left in place rather than deleted here, but nothing reads them
-- any more: retrieval matches chunks only, because fusing a ranked list of
-- memories with a ranked list of chunks would be comparing different things.
--
-- The practical consequence, and it is worth knowing before upgrading: a memory
-- written before chunking existed is invisible to semantic search until it has
-- been chunked. POST /api/memories/reindex does that, and deletes the stale
-- MEMORY-level vector as it goes. Run it after applying this migration.
COMMENT ON COLUMN embeddings.owner_type IS
    'CHUNK is current and the only type retrieval reads. MEMORY is legacy, '
    'deleted per memory as reindex chunks it.';
