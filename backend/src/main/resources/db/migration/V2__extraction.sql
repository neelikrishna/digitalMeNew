-- AI-assisted memory extraction (Phase 3).
--
-- Two-stage by design: raw input is preserved exactly as the owner wrote it,
-- and anything the model derives from it lands in memory_proposals for review
-- rather than going straight into memories.

CREATE TABLE raw_inputs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content     TEXT NOT NULL,
    source      TEXT NOT NULL DEFAULT 'TEXT_ENTRY',
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_raw_inputs_user ON raw_inputs(user_id, received_at);

CREATE TABLE memory_proposals (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    raw_input_id      UUID NOT NULL REFERENCES raw_inputs(id) ON DELETE CASCADE,
    type              TEXT NOT NULL CHECK (type IN
                          ('EPISODIC', 'SEMANTIC', 'PREFERENCE', 'PROCEDURAL', 'EMOTIONAL', 'RELATIONSHIP', 'PROJECT')),
    title             TEXT,
    content           TEXT NOT NULL,
    event_date        DATE,
    confidence        REAL NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0 AND 1),
    status            TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    created_memory_id UUID REFERENCES memories(id) ON DELETE SET NULL,
    reviewed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_memory_proposals_pending ON memory_proposals(user_id, status, created_at);
