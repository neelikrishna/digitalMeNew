# Digital Self

A private, local-first, self-hosted personal memory and AI system: a growing structured archive of your own memories, knowledge, preferences, and personality, retrieved by a local LLM to answer questions about you — never sent to any third-party AI provider.

This is not a chatbot with a big prompt. It's a memory system first, with an AI layer on top that is only ever allowed to answer from what you've actually told it.

## Status

**Phases 1-4 implemented (4 partially).** 76 tests passed as of the last run; the
file text-extraction work added since has not yet been run — see
[backend/README.md](backend/README.md) "Known gaps".

- Phase 1: schema migrations, auth (register/login/refresh, Argon2 + JWT + refresh rotation), Ollama behind an `AIService`/`EmbeddingService` abstraction, health check.
- Phase 2: memory create/revise/archive/restore with full version history, metadata editing, and keyword/date/type/tag search.
- Phase 3: embeddings in pgvector, hybrid semantic + full-text retrieval, RAG chat that answers only from stored memories, AI memory extraction with confidence-gated review, knowledge-graph links.
- Phase 4 (partial): encrypted file storage — per-file keys wrapped by a master key held outside the database, content sniffed from bytes, crypto-shredding as permanent delete. Plus selective encryption of sensitive memories (title, content and version history), which trades their searchability for genuine confidentiality at rest. Plus text extraction: upload a PDF or Word document and its *contents* become searchable and answerable, via a memory derived from the extracted text. EXIF and transcription still to come.

Runs against a locally installed PostgreSQL and Ollama — Docker is optional. `pgvector` is optional too: without it, retrieval falls back to full-text search and `/api/health` says so.

Schema, JPA mappings, versioning, full-text search and cross-user isolation are verified against a real PostgreSQL started as a temporary subprocess during tests. Known gaps: no TLS wired up, the pgvector SQL and the HTTP layer are still untested, and the extraction code has never been run. See [backend/README.md](backend/README.md) "Known gaps" and [docs/roadmap.md](docs/roadmap.md).

## Documentation

- [Architecture](docs/architecture.md) — component design and the reasoning behind each major technology choice.
- [Database Design](docs/database-design.md) — ER diagram and schema.
- [Security](docs/security.md) — encryption, auth, RBAC, audit, backups.
- [Memory Engine](docs/memory-engine.md) — memory types, lifecycle, confidence/provenance, corrections.
- [AI Architecture](docs/ai-architecture.md) — Ollama abstraction, embeddings, RAG, hallucination prevention.
- [Mobile Architecture](docs/mobile-architecture.md) — React Native client design.
- [Roadmap](docs/roadmap.md) — phased plan and working agreement.

## Technology stack

| Layer | Technology | Why |
|---|---|---|
| Backend API | Java 21 + Spring Boot | Strong typing for a domain model that must never silently lose data; Spring Security for auth/RBAC |
| Database | PostgreSQL + `pgvector` | One transactionally-consistent store for structured metadata and embeddings |
| Local AI | Ollama | Local inference gateway, abstracted behind an `AIService` interface so models are swappable |
| Ingestion pipelines | Python | Best fit for file parsing/extraction (EXIF, PDF, WhatsApp exports, transcription) |
| Mobile client | React Native (Expo) | Cross-platform, reuses the JS/Node ecosystem already available |
| File storage | Encrypted local filesystem | Envelope-encrypted blobs; no third-party object storage required to start |

See [docs/architecture.md](docs/architecture.md) for the full reasoning behind each choice, including alternatives considered.

## Security model (summary)

- Local-only by default — no data leaves the machine/local network without explicit configuration.
- No telemetry, no third-party AI/analytics calls.
- Envelope encryption at rest (master key outside the database, per-row/per-file data encryption keys) for sensitive memory content and files.
- Argon2id password hashing, short-lived JWT + rotating refresh tokens, RBAC, append-only audit log.
- Encrypted backups; crypto-shredding for secure deletion.

Full detail in [docs/security.md](docs/security.md).

## Running locally

Phase 1 (auth, schema, Ollama integration, health check) is implemented. Full setup and run instructions are in [backend/README.md](backend/README.md). Quick version (run these yourself — nothing here is executed automatically):

```bash
cp .env.example .env   # fill in DB_PASSWORD and JWT_SECRET
docker compose -f docker/docker-compose.yml --env-file .env up -d
ollama pull llama3.1
ollama pull nomic-embed-text
cd backend && mvn spring-boot:run
```

## Project layout

```text
DigitalMe/
├── docs/            # architecture, schema, security, roadmap
├── backend/         # Spring Boot API (Phase 1)
├── mobile/          # React Native client (Phase 5)
├── scripts/         # Python ingestion pipelines (Phase 4)
└── docker/          # local Postgres + Ollama compose setup
```

## Development rules

1. Local-first by default; no personal data leaves the machine without explicit configuration.
2. No hallucinated memories — the assistant says "I don't have a memory of that" when retrieval finds nothing.
3. Every memory is versioned; corrections supersede, they never silently overwrite.
4. Every major architectural decision is explained (why / alternatives / recommendation / limitations) before it's built.
5. Built incrementally, one phase at a time, with an approval checkpoint between phases.
