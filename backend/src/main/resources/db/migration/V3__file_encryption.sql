-- Envelope encryption key material (Phase 4).
--
-- The wrapped data-encryption key lives here, in the database, while the
-- ciphertext it protects lives on disk. Neither store is sufficient alone, and
-- neither is any use without the master key, which is held outside both.

ALTER TABLE encryption_metadata
    ADD COLUMN wrapped_key BYTEA NOT NULL,
    ADD COLUMN key_iv      BYTEA NOT NULL;

-- Which master key wrapped this DEK, so keys can be rotated by re-wrapping
-- rather than re-encrypting every file.
ALTER TABLE encryption_metadata
    ADD COLUMN master_key_label TEXT NOT NULL DEFAULT 'default';

CREATE INDEX idx_encryption_metadata_subject ON encryption_metadata(subject_type, subject_id);
