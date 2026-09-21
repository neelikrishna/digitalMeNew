# Architecture

## 1. Goals that shape every decision

- **Local-first, self-hosted.** No personal data leaves the machine/local network by default.
- **Owner-operated at small scale, designed to not fall over at large scale.** Thousands of memories on day one, architected so it doesn't need a rewrite at millions.
- **Replaceable parts.** The LLM, the embedding model, and the file store must be swappable without touching business logic.
- **Auditable and reversible.** Every write to memory is versioned; nothing important is silently overwritten.

## 2. High-level component diagram

```text
                    ┌───────────────────────────┐
                    │        Mobile App         │
                    │   React Native (Expo)      │
                    └─────────────┬─────────────┘
                                  │ HTTPS (local network / VPN)
                    ┌─────────────▼─────────────┐
                    │        Backend API        │
                    │   Java 21 + Spring Boot    │
                    │  (auth, memory CRUD, RAG   │
                    │   orchestration, RBAC)     │
                    └───┬─────────┬─────────┬────┘
                        │         │         │
              ┌─────────▼──┐  ┌───▼───┐ ┌───▼─────────┐
              │ PostgreSQL │  │ File  │ │ Ingestion   │
              │ + pgvector │  │ Store │ │ Pipeline    │
              │ (metadata, │  │ (enc. │ │ (Python)    │
              │ embeddings)│  │ blobs)│ │ photos/docs │
              └─────────┬──┘  └───┬───┘ │ /audio/video│
                        │         │     └──────┬──────┘
                        └────┬────┴────────────┘
                             │
                    ┌────────▼─────────┐
                    │   AI Service      │
                    │  abstraction over │
                    │      Ollama       │
                    └───────────────────┘
```

## 3. Why Spring Boot for the backend (and not Python or Node)

**Why needed:** you need one system-of-record service that owns authentication, authorization, memory CRUD, versioning, and RAG orchestration — a place where business rules about "never silently overwrite a memory" are enforced centrally.

**Alternatives considered:**
- *Python (FastAPI)* — natural fit for AI code, but weaker as a long-lived transactional backend (auth, RBAC, migrations are all workable but less idiomatic than in Spring's ecosystem).
- *Node/Express* — fine for the mobile/web client side, less appealing for a strongly-typed domain model with entities like `Memory`, `MemoryVersion`, `Person`, `Event`.

**Recommendation:** Java + Spring Boot for the backend API, since you already have Java depth, Spring Security gives you auth/RBAC out of the box, and Spring Data JPA plus Flyway/Liquibase gives you disciplined schema migrations — important for a database that's meant to grow for years.

**Amended after Phase 4 (2026-09-21).** This section originally assigned *all* file ingestion to Python. That turned out to be wrong, and the split now runs along a different line:

- **In Java:** PDF/Office text extraction and photo EXIF. Tika was already a dependency for content sniffing and provides both, so moving this work to Python would have added a second runtime and a process boundary to cross while buying nothing. It would also have meant sending decrypted personal content over that boundary. See [file-ingestion.md](file-ingestion.md) and [media-ingestion.md](media-ingestion.md) for the full reasoning.
- **Still Python, when built:** WhatsApp export parsing and audio/video transcription. These genuinely need libraries with no good JVM equivalent (`faster-whisper` in particular).

**Future limitation:** the Python tasks still imply a second runtime, and decrypted content crossing a process boundary is what makes TLS a prerequisite for them rather than tidying — see [media-ingestion.md](media-ingestion.md) Section 5. Nothing in Phase 4 as built crosses that boundary, so the system remains a single deployment unit today.

## 4. Why a modular monolith, not microservices

**Why needed:** you're one user with one machine. Microservices solve organizational and independent-scaling problems you don't have.

**Alternatives:** microservices per domain (memory service, auth service, file service) — adds network hops, distributed transactions, and operational overhead for no benefit at this scale.

**Recommendation:** a single Spring Boot application internally organized into clean modules (`auth`, `memory`, `people`, `files`, `ai`, `audit`) with clear package boundaries and no circular dependencies, so any module can later be extracted into its own service if you ever need to (e.g., if the AI layer needs a GPU box separate from the API box).

**Future limitation:** if you eventually run the LLM on a separate machine (a home GPU server) the AI module already talks to Ollama over HTTP, so splitting it out later is a config change, not a rewrite.

## 5. Why PostgreSQL + pgvector, not a dedicated vector database

**Why needed:** semantic search over memory embeddings, combined with relational filtering (by person, date, tag, privacy level).

**Alternatives:** Qdrant, Milvus, Weaviate — purpose-built vector engines with better performance at very large scale (tens of millions+ vectors) and more advanced ANN tuning.

**Recommendation:** `pgvector` inside the Postgres you already have. It supports HNSW indexes, keeps embeddings transactionally consistent with the rows they describe (no dual-write problem), and lets you combine vector similarity with SQL `WHERE` clauses in one query — exactly the "semantic search + metadata filtering" pattern described in the spec. This is the single biggest simplification available for this project.

**Future limitation:** pgvector's ANN performance degrades past roughly tens of millions of vectors on modest hardware. A personal archive of memories, conversations, and file chunks is very unlikely to reach that scale for a very long time; if it ever does, the `EmbeddingService`/repository abstraction (see [ai-architecture.md](ai-architecture.md)) makes migrating to Qdrant a data-migration task, not a rewrite.

## 6. Why Ollama behind an `AIService` abstraction

Covered in depth in [ai-architecture.md](ai-architecture.md). Summary: business logic never calls Ollama's HTTP API directly — it calls an `AIService` interface, so the model (and even the inference backend) can change without touching memory or RAG code.

## 7. File storage: encrypted filesystem, not object storage, for v1

**Why needed:** durable, encrypted storage for photos/documents/audio/video that doesn't touch originals.

**Alternatives:** MinIO (S3-compatible object storage) — nicer API, built-in versioning, but another service to run and secure.

**Recommendation:** start with a structured, encrypted directory tree on local disk (per-file AES-256-GCM, envelope-encrypted — see [security.md](security.md)), tracked via a `files`/`file_metadata` table that stores hash, path, and encryption metadata. This is the smallest thing that works.

**Future limitation:** no built-in replication. If you later want off-machine encrypted backups or multi-device access, migrating to MinIO with server-side encryption is a natural next step — the `FileStorageService` interface is written so that migration only touches its one implementation.

## 8. Network exposure

The backend binds to localhost/LAN by default. Mobile access from outside the home network is expected to go through a private mechanism you control (e.g., a WireGuard/Tailscale tunnel), **not** a public port-forward — this is the practical way to keep "local-first" true once a phone leaves the house. This is a decision to revisit explicitly before Phase 5 (mobile), not now.

## 9. What this phase does *not* decide yet

Deliberately deferred until the relevant phase, per the roadmap:
- Fine-tuning / LoRA (Phase 6+, only if warranted by data volume).
- Digital Legacy access model (Phase 8, only after security architecture is mature).
- Multi-user/family sharing beyond the single-owner + legacy-access model.

See [roadmap.md](roadmap.md) for phase gating.
