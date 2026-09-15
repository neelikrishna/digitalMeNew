-- File text extraction (Phase 4). See docs/file-ingestion.md.
--
-- Uploaded documents are opaque encrypted blobs today: a PDF contributes nothing
-- to search or RAG. This migration gives extraction somewhere to record what it
-- found, what it could not read, and which memory it derived.

-- Per-file sensitivity, mirroring memories.sensitive exactly. A sensitive file's
-- extracted text is encrypted and its derived memory is created sensitive, which
-- keeps it out of full-text search and out of the embedding store.
--
-- The default is FALSE — plaintext — because extraction that cannot be searched
-- would defeat its own purpose. That trade-off is documented rather than hidden:
-- see docs/file-ingestion.md Section 4.
ALTER TABLE files ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE;

-- file_metadata already had extracted_text/extracted_at/extraction_source from
-- V1 but was never mapped or written to.
ALTER TABLE file_metadata ADD COLUMN extracted_text_encrypted BYTEA;

-- Same invariant as memories: one representation, never both. Unlike memories,
-- neither is also legal — a row exists from upload onwards, before extraction
-- has produced anything.
ALTER TABLE file_metadata ADD CONSTRAINT file_metadata_text_storage CHECK (
    extracted_text IS NULL OR extracted_text_encrypted IS NULL
);

-- Extraction outcome, kept explicit rather than inferred from which columns are
-- null. "Parsed but there was no text" and "the parser failed" look identical
-- under a null-checking scheme, and they need opposite handling: the first is a
-- fact about the document, the second is retryable.
ALTER TABLE file_metadata ADD COLUMN extraction_status TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (extraction_status IN ('PENDING', 'EXTRACTED', 'EMPTY', 'UNSUPPORTED', 'FAILED'));

-- Kept so "why is my PDF not searchable" is answerable without reading logs.
ALTER TABLE file_metadata ADD COLUMN extraction_error TEXT;

-- The memory derived from this file's text. A dedicated column rather than a
-- lookup through memory_files: that table is a user-facing many-to-many (a file
-- can be attached to any number of memories by hand), so it cannot distinguish
-- the one memory extraction itself created. Backfill and shredding both need
-- that distinction.
ALTER TABLE file_metadata ADD COLUMN derived_memory_id UUID REFERENCES memories(id) ON DELETE SET NULL;

CREATE INDEX idx_file_metadata_status ON file_metadata(extraction_status);

-- Re-running extraction over a file revises its derived memory rather than
-- creating a second one, and every revision needs an honest reason. Reusing
-- AI_RECLASSIFICATION would misdescribe it: nothing was reclassified, the
-- document was simply read again.
ALTER TABLE memory_versions DROP CONSTRAINT memory_versions_change_reason_check;
ALTER TABLE memory_versions ADD CONSTRAINT memory_versions_change_reason_check
    CHECK (change_reason IN ('INITIAL', 'USER_CORRECTION', 'AI_RECLASSIFICATION', 'FILE_RE_EXTRACTION'));
