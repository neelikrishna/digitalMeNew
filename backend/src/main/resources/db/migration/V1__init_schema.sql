-- Digital Self: core schema (Phase 1)
--
-- Everything here runs on a stock PostgreSQL install. The pgvector-dependent
-- parts (the embeddings table and its index) live in the separate
-- db/migration-vector location, applied only when the `pgvector` Spring profile
-- is active — see backend/README.md. Without them the system still runs, with
-- retrieval falling back to full-text search only.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- Users & auth
-- ============================================================

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          TEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    role           TEXT NOT NULL DEFAULT 'OWNER' CHECK (role IN ('OWNER', 'LEGACY_VIEWER')),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Refresh tokens are stored hashed; rotation chain supports theft detection
-- (reuse of a revoked token revokes the whole chain for that user).
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  UUID REFERENCES refresh_tokens(id)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

-- ============================================================
-- People, relationships, locations, events, projects
-- ============================================================

CREATE TABLE people (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                   TEXT NOT NULL,
    aliases                TEXT[] NOT NULL DEFAULT '{}',
    relationship_to_owner  TEXT,
    notes                  TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_people_user ON people(user_id);

CREATE TABLE relationships (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_a_id        UUID NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    person_b_id        UUID NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    relationship_type  TEXT NOT NULL,
    notes              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (person_a_id <> person_b_id)
);

CREATE TABLE locations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    address     TEXT,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    description  TEXT,
    status       TEXT NOT NULL DEFAULT 'ACTIVE',
    started_at   DATE,
    ended_at     DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title        TEXT NOT NULL,
    event_date   DATE,
    description  TEXT,
    location_id  UUID REFERENCES locations(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE event_people (
    event_id   UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    person_id  UUID NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, person_id)
);

-- ============================================================
-- Memories (the core table) + versioning + linking + tagging
-- ============================================================

CREATE TABLE memories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type                TEXT NOT NULL CHECK (type IN
                            ('EPISODIC', 'SEMANTIC', 'PREFERENCE', 'PROCEDURAL', 'EMOTIONAL', 'RELATIONSHIP', 'PROJECT')),
    title               TEXT,
    content             TEXT NOT NULL,
    source              TEXT NOT NULL CHECK (source IN
                            ('USER_INPUT', 'CONVERSATION', 'FILE_EXTRACTION', 'AI_INFERENCE')),
    event_date          DATE,
    importance          SMALLINT CHECK (importance BETWEEN 1 AND 5),
    confidence          REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1),
    privacy_level       TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (privacy_level IN
                            ('PRIVATE', 'LEGACY_READABLE', 'LEGACY_FULL')),
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'ARCHIVED')),
    current_version_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_memories_user_date ON memories(user_id, event_date);
CREATE INDEX idx_memories_privacy ON memories(privacy_level);
-- Expression must match the query in MemoryTextSearch exactly, or the index is not used.
CREATE INDEX idx_memories_content_fts ON memories
    USING GIN (to_tsvector('english', coalesce(title, '') || ' ' || content));

CREATE TABLE memory_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    memory_id       UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    version_number  INT NOT NULL,
    content         TEXT NOT NULL,
    event_date      DATE,
    confidence      REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1),
    change_reason   TEXT NOT NULL CHECK (change_reason IN ('INITIAL', 'USER_CORRECTION', 'AI_RECLASSIFICATION')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_at   TIMESTAMPTZ,
    UNIQUE (memory_id, version_number)
);

ALTER TABLE memories
    ADD CONSTRAINT fk_memories_current_version
    FOREIGN KEY (current_version_id) REFERENCES memory_versions(id);

CREATE TABLE tags (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name  TEXT NOT NULL,
    UNIQUE (user_id, name)
);

CREATE TABLE memory_tags (
    memory_id  UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    tag_id     UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, tag_id)
);

CREATE TABLE memory_links (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_memory_id   UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    target_memory_id   UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    link_type          TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_memory_id <> target_memory_id)
);

-- Join tables linking memories to the entities they mention
CREATE TABLE memory_people (
    memory_id  UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    person_id  UUID NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, person_id)
);

CREATE TABLE memory_events (
    memory_id  UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    event_id   UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, event_id)
);

CREATE TABLE memory_locations (
    memory_id    UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    location_id  UUID NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, location_id)
);

CREATE TABLE memory_projects (
    memory_id   UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    project_id  UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, project_id)
);

-- ============================================================
-- Conversations (separate from long-term memory)
-- ============================================================

CREATE TABLE conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       TEXT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE messages (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id      UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role                 TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content              TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    promoted_to_memory_id UUID REFERENCES memories(id) ON DELETE SET NULL
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);

-- ============================================================
-- Files, extracted metadata, media, and attachment to memories
-- ============================================================

CREATE TABLE files (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_path        TEXT NOT NULL,
    content_hash        TEXT NOT NULL,
    mime_type           TEXT NOT NULL,
    original_filename   TEXT NOT NULL,
    encryption_key_id   UUID,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_files_user ON files(user_id);
CREATE INDEX idx_files_hash ON files(content_hash);

CREATE TABLE file_metadata (
    file_id           UUID PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
    extracted_text    TEXT,
    extracted_at      TIMESTAMPTZ,
    extraction_source TEXT
);

CREATE TABLE media (
    file_id          UUID PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
    media_type       TEXT NOT NULL CHECK (media_type IN ('PHOTO', 'AUDIO', 'VIDEO')),
    exif_json        JSONB,
    taken_at         TIMESTAMPTZ,
    duration_seconds REAL,
    width            INT,
    height           INT
);

CREATE TABLE memory_files (
    memory_id  UUID NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    file_id    UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    PRIMARY KEY (memory_id, file_id)
);

-- Embeddings live in db/migration-vector/V100__embeddings.sql because they
-- require the pgvector extension.

-- ============================================================
-- Preferences, personality, goals (kept separate from memories)
-- ============================================================

CREATE TABLE preferences (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category    TEXT NOT NULL,
    key         TEXT NOT NULL,
    value       TEXT NOT NULL,
    confidence  REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1),
    UNIQUE (user_id, category, key)
);

CREATE TABLE personality_traits (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    trait              TEXT NOT NULL,
    description        TEXT,
    evidence_memory_ids UUID[] NOT NULL DEFAULT '{}'
);

CREATE TABLE goals (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title        TEXT NOT NULL,
    description  TEXT,
    status       TEXT NOT NULL DEFAULT 'ACTIVE',
    target_date  DATE
);

-- ============================================================
-- Audit, access policies (future legacy access), encryption metadata
-- ============================================================

CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    action      TEXT NOT NULL,
    entity_type TEXT,
    entity_id   UUID,
    actor       TEXT,
    ip_address  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    details_json JSONB
);

CREATE INDEX idx_audit_logs_user ON audit_logs(user_id, created_at);

-- Not enforced until Phase 8 (Digital Legacy); schema exists now so it doesn't
-- require a breaking migration later.
CREATE TABLE access_policies (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grantee_user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope                 TEXT NOT NULL CHECK (scope IN ('READ_ONLY', 'INTERACTIVE')),
    privacy_levels_included TEXT[] NOT NULL DEFAULT '{}',
    active_from           TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    CHECK (owner_user_id <> grantee_user_id)
);

CREATE TABLE encryption_metadata (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type TEXT NOT NULL,
    subject_id   UUID NOT NULL,
    key_id       UUID NOT NULL,
    algorithm    TEXT NOT NULL DEFAULT 'AES-256-GCM',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_type, subject_id)
);
