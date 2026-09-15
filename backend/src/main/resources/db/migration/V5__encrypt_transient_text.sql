-- Encrypt raw input, proposals and chat messages (Phase 4).
--
-- These three tables hold text the owner actually wrote or said, and closing
-- the gap matters: writing a private reflection through /api/extract used to
-- leave the original readable in raw_inputs even when the resulting memory was
-- encrypted.
--
-- Unlike memories, none of these are ever searched — they are fetched by id or
-- by parent — so they are encrypted unconditionally with no trade-off. The
-- plaintext columns are removed rather than left as dead schema.

-- Refuse rather than silently discard: if these tables already hold plaintext,
-- dropping the columns would destroy it. A proper data migration would be
-- needed, and that should be a deliberate act, not a side effect.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM raw_inputs)
        OR EXISTS (SELECT 1 FROM memory_proposals)
        OR EXISTS (SELECT 1 FROM messages) THEN
        RAISE EXCEPTION
            'V5 refuses to run: raw_inputs, memory_proposals or messages already contain plaintext rows. '
            'Dropping the plaintext columns would destroy them. Export this data, or write a migration '
            'that re-encrypts it, before applying V5.';
    END IF;
END $$;

-- Each raw input gets its own key. Proposals reuse their parent raw input's key,
-- so shredding an input shreds everything derived from it in one act.
ALTER TABLE raw_inputs DROP COLUMN content;
ALTER TABLE raw_inputs ADD COLUMN content_encrypted BYTEA NOT NULL;

ALTER TABLE memory_proposals DROP COLUMN content;
ALTER TABLE memory_proposals DROP COLUMN title;
ALTER TABLE memory_proposals ADD COLUMN content_encrypted BYTEA NOT NULL;
ALTER TABLE memory_proposals ADD COLUMN title_encrypted   BYTEA;

-- Messages share a key per conversation rather than one key per turn.
ALTER TABLE messages DROP COLUMN content;
ALTER TABLE messages ADD COLUMN content_encrypted BYTEA NOT NULL;
