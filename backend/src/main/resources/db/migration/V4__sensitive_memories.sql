-- Selective encryption for sensitive memories (Phase 4).
--
-- Encrypting every memory would disable Postgres full-text search, which is
-- currently the only working retrieval path. So sensitivity is per-memory:
-- flagged memories are encrypted and drop out of text search, everything else
-- stays searchable. Emotional/reflective memories default to sensitive, per the
-- project spec's treatment of them.
--
-- Title is encrypted alongside content: a title alone can disclose just as much.

ALTER TABLE memories ALTER COLUMN content DROP NOT NULL;
ALTER TABLE memories ADD COLUMN content_encrypted BYTEA;
ALTER TABLE memories ADD COLUMN title_encrypted   BYTEA;
ALTER TABLE memories ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE;

-- Exactly one representation is populated, enforced by the database rather than
-- trusted to application code: plaintext for ordinary memories, ciphertext for
-- sensitive ones. Neither both nor neither.
ALTER TABLE memories ADD CONSTRAINT memories_content_storage CHECK (
    (sensitive = FALSE AND content IS NOT NULL AND content_encrypted IS NULL)
    OR
    (sensitive = TRUE AND content IS NULL AND content_encrypted IS NOT NULL)
);

-- Version history holds the same text and would otherwise leak the plaintext of
-- every prior state of a memory that is now sensitive.
ALTER TABLE memory_versions ALTER COLUMN content DROP NOT NULL;
ALTER TABLE memory_versions ADD COLUMN content_encrypted BYTEA;

ALTER TABLE memory_versions ADD CONSTRAINT memory_versions_content_storage CHECK (
    (content IS NOT NULL AND content_encrypted IS NULL)
    OR
    (content IS NULL AND content_encrypted IS NOT NULL)
);

-- The full-text index concatenates title and content, so a NULL content makes
-- the whole expression NULL and the row simply never matches a text query.
-- Sensitive memories stay findable by date, type, tag and linked people.
