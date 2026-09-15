# Pending work

Everything outstanding, in one place. Detail lives in
[docs/roadmap.md](docs/roadmap.md) and [backend/README.md](backend/README.md)
"Known gaps"; this is the index.

Last updated: 2026-09-15.

---

## 1. Blocking right now

| | Task | Notes |
|---|---|---|
| ☐ | **Get the build green** | `mvn test`. The last run failed on a Tika API mismatch in `FileTextExtractor`, now fixed. Test sources have **never been compiled**, so expect more errors on the next run. |
| ☐ | **Put the repo under git** | Not a git repository — no `.git` directory, ~130 files with no history. Commands were handed over; the GitHub repo was to be created manually as **private**. |
| ☐ | **Decide the TLS certificate approach** | `keytool` (ships with the JDK, nothing to install, self-signed) vs `mkcert` (one install, locally trusted). Open question. |

---

## 2. Written but never executed

Code that exists, compiles (or nearly), and has never run. Distinct from Section
3 — these are not known *gaps*, they are known *unknowns*.

- ☐ **Text extraction** (PDF/Office → searchable). Built, tests written, never run.
- ☐ **Photo EXIF**. Built, tests written, never run.
- ☐ **No test reads a real EXIF-bearing JPEG.** The mapping is tested against synthetic Tika metadata and the wiring against an EXIF-free PNG. The path from real camera bytes to a `taken_at` value is unproven — upload one photo from your phone and check `GET /api/files/{id}`.
- ☐ **The pgvector SQL in `EmbeddingStore`** has never executed. The extension is not installed, so vector insert and similarity search are unverified. Everything else in the schema runs against real Postgres in `SchemaIntegrationTest`.
- ☐ **The whole HTTP layer.** No test drives a controller, the JWT filter, or the Spring Security chain end to end. Services and persistence are well covered; request/response wiring is not covered at all.
- ☐ **The app has never been started against your own PostgreSQL** — only the temporary embedded one the tests spin up.

---

## 3. Known gaps in what is built

### Security
- ☐ **No TLS.** `server.ssl.*` is commented out in [application.yml](backend/src/main/resources/application.yml). Blocks Section 4's Python work — see [docs/media-ingestion.md](docs/media-ingestion.md) Section 5.
- ☐ **The DB user is not least-privilege.** The app connects as `postgres`. [setup-local-db.sql](scripts/sql/setup-local-db.sql) creates a proper `digitalself_app` role that is not actually used yet.
- ☐ **Only memories marked sensitive are encrypted.** Ordinary memory text sits in plaintext columns so it stays searchable. Deliberate trade-off, documented, but worth revisiting once pgvector works and full-text is no longer the only retrieval path.

### Retrieval quality
- ☐ **Embedding dimension is hard-coded to 768** (`nomic-embed-text`) in the migration. Switching to a model with a different dimension needs a follow-up migration.
- ☐ **`POST /api/memories/reindex` only embeds memories with *no* embedding.** Re-embedding everything after a model change is not implemented.
- ☐ **One memory per document is coarse.** A 200-page PDF is a single memory, retrieved or not as a unit. `max-chars-per-context-memory` caps what reaches the prompt as a stopgap; one memory per *section* is the real fix.

### Loose ends
- ☐ **Photos cannot be browsed.** `MediaMetadataRepository.findTakenBetween` exists and nothing calls it — there is no endpoint to list photos by date or location, which was the point of reading EXIF.
- ☐ **[docs/architecture.md](docs/architecture.md) Section 3 is out of date.** It still assigns all file ingestion to Python. Text extraction and EXIF landed in Java; the reasoning is in [docs/file-ingestion.md](docs/file-ingestion.md) and [docs/media-ingestion.md](docs/media-ingestion.md), but the architecture doc has not been amended.
- ☐ **~8 schema tables have no Java behind them.** `people`, `events`, `locations`, `projects`, `preferences`, `personality_traits`, `goals`, `access_policies` are all created by [V1__init_schema.sql](backend/src/main/resources/db/migration/V1__init_schema.sql) and unmapped. Harmless (`ddl-auto: validate` ignores unmapped tables) but they are the groundwork Phases 6-8 assume.

---

## 4. Phase 4 — the rest

Designed in [docs/media-ingestion.md](docs/media-ingestion.md), awaiting
approval. The order matters and is **not** the roadmap's order:

| | Task | Needs | Blocked by |
|---|---|---|---|
| ☐ | **TLS** | a cert (`keytool` or `mkcert`) | — |
| ☐ | **WhatsApp export parsing** | Python 3.11+ | TLS |
| ☐ | **Audio/video transcription** | Python, `faster-whisper`, a model pull | TLS |
| ☐ | **OCR for scanned documents** | Tesseract — not installed, not decided | — |

TLS is a genuine prerequisite, not tidying. Both Python tasks need decrypted
content to reach another process, and the least-bad route sends it over
localhost HTTP.

WhatsApp before transcription deliberately: it is pure text parsing with no model
involved, so it proves the whole Python boundary — auth, token scope, posting
memories back — against something that cannot fail for machine-learning reasons.

---

## 5. Phases 5-8

Not started. Each needs its design approved before implementation, per the
working agreement in [docs/roadmap.md](docs/roadmap.md).

- ☐ **Phase 5 — Mobile app.** React Native (Expo): login, chat, add memory, voice input, upload, memory browser, search. Needs Node 20 + Expo installed, and carries an unresolved decision of its own: **remote-access networking** (VPN or tunnel), which TLS is also a prerequisite for.
- ☐ **Phase 6 — Personality.** Populate `personality_traits` and `preferences` from accumulated memory; communication-style modelling.
- ☐ **Phase 7 — Knowledge graph.** Deeper relationships across people/events/places/projects; graph-aware retrieval. Depends on the unmapped tables in Section 3.
- ☐ **Phase 8 — Digital legacy.** Only after the security architecture has been in real use and reviewed. Activates `access_policies`, with mandatory AI self-disclosure.

---

## Done

Kept short deliberately — the git history will carry this once Section 1 is
cleared.

- ✅ Phases 1-3 complete: auth, memory CRUD with full version history, hybrid RAG with structural hallucination prevention, AI extraction, knowledge-graph links.
- ✅ Phase 4 part 1: encrypted file storage, envelope encryption, crypto-shredding.
- ✅ Phase 4 part 2: text extraction from PDFs and Office documents into derived memories ([docs/file-ingestion.md](docs/file-ingestion.md)).
- ✅ Phase 4 part 3: photo EXIF into the `media` table, deliberately *without* deriving memories ([docs/media-ingestion.md](docs/media-ingestion.md) Section 3).
- ✅ Selective encryption of sensitive memories, and unconditional encryption of raw inputs, proposals and chat messages.
