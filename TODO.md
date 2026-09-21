# Pending work

Everything outstanding, in one place. Detail lives in
[docs/roadmap.md](docs/roadmap.md) and [backend/README.md](backend/README.md)
"Known gaps"; this is the index.

Last updated: 2026-09-21.

> **Nothing is built or run on the machine the code is written on.** It is a
> company laptop and no software is installed there. Everything — build, tests,
> database, Ollama — happens on a personal machine after cloning. See
> [SETUP.md](SETUP.md) for what to install and configure.
>
> Consequence: **code written after 2026-09-21 has not been compiled.** The last
> green run was `mvn clean test` → 112 tests, 0 failures, immediately before this
> arrangement began. Anything marked *not compiled* below is exactly that —
> written with care, but unproven.

---

## 1. Blocking right now

| | Task | Notes |
|---|---|---|
| ☑ | **Get the build green** | Done 2026-09-21: `mvn clean test` → **112 tests, 0 failures**. The four file-ingestion test classes compiled and passed on their first run. |
| ☐ | **Re-run the build on the personal laptop** | First job after cloning. A large amount of code has been written since the last green run and **none of it has been compiled**: photo browsing, intent routing, and the whole chunking layer. |
| ☐ | **Run `POST /api/memories/reindex` after upgrading** | Chunking changed the retrieval unit. Memories written before it are invisible to semantic search until reindexed, which also clears their stale memory-level vectors. |
| ☑ | **Put the repo under git** | Done. 2 commits, 167 files tracked. Audited 2026-09-21: no `.env`, no `data/`, no keys and no real secret values are tracked — only `.env.example`, which is intended. Creating the GitHub remote (**private**) is still yours to do. |
| ☐ | **Decide the TLS certificate approach** | `keytool` (ships with the JDK, nothing to install, self-signed) vs `mkcert` (one install, locally trusted). Open question — needs your call. |

---

## 2. Written but never executed

Code that exists, compiles (or nearly), and has never run. Distinct from Section
3 — these are not known *gaps*, they are known *unknowns*.

- ☑ **Text extraction** (PDF/Office → searchable). Ran 2026-09-21 against real Postgres: a document becomes searchable text, the derived memory attaches to its file, shredding the file destroys the derived memory, and a sensitive file leaves no extracted plaintext in any table.
- ☑ **Photo EXIF**. Ran 2026-09-21: images are recorded as `UNSUPPORTED` rather than failed, a photo gets a `media` row, and a photo never becomes a memory.
- ☐ **No test reads a real EXIF-bearing JPEG.** Still true. The mapping is tested against synthetic Tika metadata and the wiring against an EXIF-free PNG, so the path from real camera bytes to a `taken_at` value remains unproven — upload one photo from your phone and check `GET /api/files/{id}`.
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
- ☑ **Photo browsing added** (2026-09-21, **not compiled — see the banner above**). `GET /api/files/photos`, optional ISO-8601 `from`/`to`. With no window, EXIF-stripped photos are listed last rather than dropped, since most images from messaging apps carry no capture date and omitting them would make the gallery look broken; with a window they cannot match, so the response reports `undatedExcluded` instead of letting a short list pass for the whole library. Four integration tests written.
- ☐ **Photos still cannot be browsed by location.** Latitude and longitude are read, stored and returned in the response, but there is no bounding-box or radius query. A client can filter what it receives; the database cannot.
- ☑ **[docs/architecture.md](docs/architecture.md) Section 3 amended** (2026-09-21) to record that text extraction and EXIF landed in Java, and that WhatsApp parsing and transcription remain the Python work.
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

## 5. Hybrid memory architecture — reviewed, awaiting decisions

Full review in
[docs/memory-architecture-review.md](docs/memory-architecture-review.md).

**One finding is urgent:** `/api/chat` currently refuses to answer when memory
retrieval is empty, so *"What is the chemical formula of water?"* returns
"I don't have a memory of that." That is the exact behaviour the hybrid
requirement calls out as wrong. Fixing it is small and contained — an intent
router in front of retrieval — but it is a behavioural change and needs approval.

| | Decision / task | State |
|---|---|---|
| ☑ | **Intent router** | **Built 2026-09-21, not compiled.** `QuestionIntent`, `IntentClassifier`, `ModelIntentClassifier`, `IntentProperties`; `RagService` routes; `PromptBuilder` has four branch prompts; `ChatResponse` reports intent. 11 classifier tests + 10 routing tests written |
| ☑ | `CURRENT_INFORMATION` answers honestly | Built — no internet dependency added |
| ☑ | **`memory_chunks`** | **Built 2026-09-22, not compiled.** V8 + V101; chunks are now the retrieval unit for both vector and full-text search; passage-level citations with page/time locations; `GET /api/memories/{id}/chunks`; token-budgeted context. 10 chunking tests + 4 integration tests written |
| ☐ | **Re-ranking** | Now possible — chunk text is stored. Not built |
| ☐ | Confirm **pgvector** installs on the target machine | Assumed available |
| ☐ | **Vision model** for photo captioning | Assumed available, not started |

---

## 6. The live assistant (JARVIS-style)

The stated end goal: always listening, voice both ways, answers immediately, has
a character. Designed in [docs/assistant-experience.md](docs/assistant-experience.md),
**awaiting approval**. The memory and retrieval foundation already exists — the
gap is voice and latency, not intelligence.

| | Task | Needs | Blocked by |
|---|---|---|---|
| ☐ | **Streaming chat** (`AIService.stream` + SSE) | nothing | — |
| ☐ | **Question routing** — general vs memory questions | nothing | — |
| ☐ | **Personality** (= Phase 6) | nothing | — |
| ☐ | **Voice out** — Piper | Python, Piper, a voice model | TLS |
| ☐ | **Voice in** — Silero VAD + faster-whisper, push-to-talk | Python, `faster-whisper` | TLS |
| ☐ | **Wake word** — openWakeWord | Python, openWakeWord | voice in |
| ☐ | **Proactivity** | scheduler; the unmapped `events`/`goals` tables | everything above |

The first three need no new software and no decisions — they are pure backend
work and each is useful on its own.

### What the archive makes possible that an assistant cannot

Designed in [docs/beyond-jarvis.md](docs/beyond-jarvis.md), **awaiting
approval**. These come from the memory archive rather than from the voice, need
no better model, and get richer the longer the system runs.

| | Capability | Depends on | Risk |
|---|---|---|---|
| ☐ | **Change detection** — notice when new input contradicts a standing belief, and ask | extraction + proposals, both built | Low — reuses a tested flow |
| ☐ | **Self-insight** — patterns across years you cannot see yourself | aggregation over existing columns | Medium — must show its working and be rejectable |
| ☐ | **Consolidation** — episodic memories become semantic ones as they age | clustering over embeddings; **needs pgvector** | Medium — lossy by nature; originals kept |
| ☐ | **Calibrated voice** — sounds like you, never more certain than the evidence | personality + provenance, both exist | Low |
| ☐ | **Working context** — what is happening *now*, not just what happened | nothing technically | **High** — ambient capture; build last, narrowly, or not at all |

Change detection is the best first move: small, reuses a flow already built and
tested, and makes the system feel like it is paying attention rather than filing.

**One decision has to be made before any of it**: `/api/chat` currently refuses
to answer when memory retrieval is empty, which is what makes hallucinated
personal history structurally impossible. A JARVIS answers general questions too.
The proposed resolution is to route by question type and label every answer's
source, preserving the guarantee rather than weakening it —
[docs/assistant-experience.md](docs/assistant-experience.md) §5.

---

## 7. Phases 5-8

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
