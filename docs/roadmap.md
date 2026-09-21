# Roadmap

Each phase ends with a checkpoint: nothing in the next phase starts without your explicit go-ahead, per the project's development rules.

For the flat list of everything outstanding — including gaps inside phases already marked complete — see [../TODO.md](../TODO.md).

> **Where this is going.** The end goal is a live, always-present voice assistant
> in the JARVIS/FRIDAY mould, not a text API. Phases 1-4 build the part that is
> genuinely hard — a private, versioned, searchable memory of a life, and
> retrieval that refuses to invent. The assistant experience on top of it is
> designed in [assistant-experience.md](assistant-experience.md); it mostly needs
> streaming and voice, not more intelligence.
>
> [beyond-jarvis.md](beyond-jarvis.md) argues that the voice is the interface and
> the archive is the product — and sets out five things this can do that no
> assistant can, because none of them have decades of one person's life with
> provenance on every claim.

## Phase 1 — Foundation *(code complete, not yet run against a live database)*
- Repository structure.
- Spring Boot backend skeleton, PostgreSQL connection, Flyway migrations for the core schema.
- `pgvector` extension enabled.
- Ollama integration behind `AIService`/`EmbeddingService`.
- Basic REST APIs: auth (register/login/refresh), health check.
- Docker Compose for Postgres + Ollama (backend runs locally via Maven for now).

## Phase 2 — Basic Personal Memory *(code complete, not yet run against a live database)*
- Add memory, revise memory (always versioned), edit metadata, archive/restore.
- Version history endpoint — every prior state retained and marked superseded.
- Search by keyword/date/type/source/tag (no semantic search yet).
- Memory categories and metadata wired to the schema in [database-design.md](database-design.md).

Deliberately excluded: hard delete. Archive is the only removal path in this phase.

## Phase 3 — AI Memory *(code complete, not yet run against a live database)*
- Ollama chat endpoint (`POST /api/chat`).
- Embedding generation + `pgvector` storage, indexed after commit so model calls never hold a DB transaction.
- Hybrid semantic + full-text retrieval with reciprocal rank fusion.
- RAG pipeline with structural hallucination prevention: no retrieval hits means the model is never called.
- Embedding backfill for memories written while the model was unreachable.
- AI-assisted memory extraction with confidence-gated review; raw input preserved verbatim, extraction can only create new memories, never modify existing ones.
- Knowledge-graph links between memories, with ownership checked on both ends.

Note: `pgvector` is optional at runtime. Without it the app runs with full-text retrieval only, and `GET /api/health` reports the degraded state.

## Phase 4 — File Intelligence *(partially complete)*

Done:
- Encrypted file storage: per-file AES-256-GCM data keys wrapped by a master key held outside the database, ciphertext on disk, wrapped keys in Postgres.
- Upload with content sniffing (type detected from bytes, not filename), SHA-256 hashing, dedupe, executable rejection.
- Download with integrity verification, and crypto-shredding as permanent deletion.

- Text extraction from PDFs and Office documents, so uploads feed search and RAG. Designed and built in Java rather than Python — see [file-ingestion.md](file-ingestion.md) for the reasoning and the two promises it changed. Not yet run.

- Photo EXIF extraction (dates, location). Built in Java — Tika already provided it, no Python and no new dependency. Photos deliberately do *not* become memories; see [media-ingestion.md](media-ingestion.md) Section 3. Not yet run.

Still outstanding — designed in [media-ingestion.md](media-ingestion.md), awaiting approval:
- TLS, which that design makes a prerequisite for anything crossing a process boundary.
- WhatsApp export parsing (Python).
- Audio/video ingestion with local transcription (Python).
- OCR for scanned documents (needs Tesseract; not decided).

## Phase 5 — Mobile App
- Login, chat, add memory, voice input, upload, memory browser, search, confirmation UI.
- Explicit decision on remote-access networking (VPN/tunnel) before shipping.

## Phase 6 — Personality
- Personality profile schema population from accumulated memory.
- Communication-style modeling, preference-aware responses.
- Revisit fine-tuning/LoRA only if data volume/quality justifies it.

## Phase 7 — Knowledge Graph
- Deeper relationship modeling across people/events/places/projects/memories.
- Graph-aware retrieval (e.g., "who else was involved in the events around X").

## Phase 8 — Digital Legacy
- Only after security architecture (Phase 1-5) has been in real use and reviewed.
- Activate `access_policies`: authorized viewers, scoped read-only/interactive modes, privacy-level-based exclusion.
- Mandatory AI self-disclosure ("I am an AI representation... not the real person") in any legacy-access context.

## Working agreement

- Building stops at the end of each phase for review; a phase does not start implementation until you've approved the design for it.
- Every major architectural decision within a phase is explained (why / alternatives / recommendation / limitations) before it's built, not just documented after the fact.
