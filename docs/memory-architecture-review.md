# Memory Architecture — Review and Proposal

Answers the 17 questions in the hybrid-memory requirement, against the repository
as it stands on 2026-09-21.

**Nothing has been implemented from this document.** It ends with a decision list
awaiting approval.

**Headline:** roughly 70% of what the requirement describes is already built —
the encrypted storage, the memory model, hybrid retrieval, provenance, citations
and the model abstractions all exist. But the single most important principle in
the requirement is **currently violated by design**, and that is the first thing
to fix. See §7.

---

## 1. Current architecture

Java 21 / Spring Boot 3.3.4 modular monolith. Packages are the module boundary:

```
auth          JWT + Argon2id, refresh-token rotation with reuse detection
memory        memories, versions, tags, links, search, embedding indexer
rag           hybrid retrieval, prompt building, chat
extraction    free text -> AI proposals -> reviewed memories
files         encrypted storage, text extraction, photo EXIF
conversation  chat history, deliberately separate from long-term memory
crypto        envelope encryption (master key -> per-subject data keys)
audit         append-only log
```

Everything runs in one process against local PostgreSQL and local Ollama. No
data leaves the machine.

## 2. Existing database structure

**32 tables.** 31 in the core migrations (V1–V7), plus `embeddings`, which lives
in a separate migration applied only under the `pgvector` profile.

| Group | Tables |
|---|---|
| Identity | `users`, `refresh_tokens` |
| Memory core | `memories`, `memory_versions`, `tags`, `memory_tags`, `memory_links` |
| Entities | `people`, `relationships`, `locations`, `events`, `event_people`, `projects` |
| Memory ↔ entity joins | `memory_people`, `memory_events`, `memory_locations`, `memory_projects`, `memory_files` |
| Conversation | `conversations`, `messages` |
| Ingestion | `raw_inputs`, `memory_proposals` |
| Files | `files`, `file_metadata`, `media` |
| Profile *(unmapped)* | `preferences`, `personality_traits`, `goals` |
| Governance | `audit_logs`, `access_policies`, `encryption_metadata` |
| Vector *(profile-gated)* | `embeddings` |

`memories` already carries: `type`, `source`, `event_date`, `importance`,
`confidence`, `privacy_level`, `status`, `current_version_id`, `sensitive`,
`created_at`, `updated_at`. That is most of the requirement's §9 lifecycle
fields.

**Eight tables have no Java behind them** — `people`, `events`, `locations`,
`projects`, `preferences`, `personality_traits`, `goals`, `access_policies`. They
are created and unused. This matters for the requirement: "What did I discuss
with John?" wants `people` to be real, not a table nobody writes to.

## 3. Existing file/storage handling

Matches the requirement's §2 closely.

- Type detected from **bytes** via Tika, never the filename; executables rejected.
- SHA-256 hash, dedupe on re-upload.
- **Envelope encryption**: per-file AES-256-GCM data key; ciphertext on disk under
  `FILE_STORAGE_PATH`, wrapped key in `encryption_metadata`, master key in the
  environment. No two of the three are sufficient.
- Atomic write via temp file + move; path containment checked.
- `DELETE` is **crypto-shredding** — the key is destroyed, not just the bytes.
- Binaries are never in the database, let alone in a vector. Requirement §2 point
  8 is satisfied.

Extraction runs **after commit** so a slow parse never holds a transaction; a
failed parse leaves the file safe and repairable via `POST /api/files/reindex`.

Text extraction produces a **derived memory** (`source = FILE_EXTRACTION`) linked
to its file, so documents feed search. Photos deliberately do **not** become
memories — EXIF is metadata, not something you said.

## 4. Existing AI/model integration

The abstraction the requirement asks for in §11 already exists:

```java
interface AIService      { String complete(String system, String user, List<Message> history); }
interface EmbeddingService { float[] embed(String text); String modelName(); }
```

`OllamaAIService` and `OllamaEmbeddingService` are the only classes that know
Ollama exists. Adding a HuggingFace or OpenAI provider is a new class plus a bean
— no business logic changes. Models are set by `OLLAMA_CHAT_MODEL` and
`OLLAMA_EMBEDDING_MODEL`.

The embedding/chat distinction in §12 is already structural, not just documented:
two interfaces, two models, two configuration keys.

## 5. Existing authentication

Argon2id (16/32/1/16MB/2), short-lived JWT access tokens (15 min), rotating
refresh tokens stored **hashed** with reuse detection — replaying a revoked token
revokes every session for that user. Stateless filter chain, per-user ownership
enforced at the **repository** layer, not just the controller. Login rate
limiting. Append-only audit log.

## 6. Existing PostgreSQL configuration

Tables live in a `digitalself` **schema inside the existing `postgres` database**.
Flyway owns it (`create-schemas: true`), so there is no manual setup step.
`ddl-auto: validate` means a mapping/schema mismatch fails startup rather than
corrupting data. `open-in-view: false`, deliberately.

---

## 7. ⚠ The principle the requirement states, which the code currently violates

This is the most important finding.

Requirement §1, §5, §6 and §15 all say the same thing: general questions must be
answered by the model without searching personal memory. `RagService.ask`
currently does the opposite:

```java
List<RetrievedMemory> retrieved = retriever.retrieve(userId, request.question());

if (retrieved.isEmpty()) {
    // the model is never invoked
    return new ChatResponse(conversationId, "I don't have a memory of that.", false, List.of());
}
```

**Today, "What is the chemical formula of water?" returns "I don't have a memory
of that."** Exactly the behaviour the requirement calls out as wrong.

This was not an accident. It is what makes hallucinated personal history
structurally impossible rather than merely discouraged — the model cannot invent
a memory it was never asked to produce. The requirement's §15 wants that
guarantee kept *and* general questions answered.

Both are achievable, but only by **routing before retrieving** and labelling the
source of every answer. The guarantee must not be weakened into "the prompt says
don't make things up".

### Proposed router

```
Question
   │
   ▼
IntentClassifier ──────────────────────────────────────────────┐
   │                                                            │
   ├─ PERSONAL_MEMORY ─► retrieve ─► empty? ─► "no memory"      │
   │                         └─ hits ─► strict prompt           │
   │                                                            │
   ├─ HYBRID ─────────► retrieve ─► prompt carries BOTH,        │
   │                     personal context and general knowledge  │
   │                     labelled separately                     │
   │                                                            │
   ├─ GENERAL_KNOWLEDGE ─► no retrieval, model answers,          │
   │                        answer marked as general             │
   │                                                            │
   ├─ CASUAL ──────────► no retrieval, short reply               │
   │                                                            │
   └─ CURRENT_INFO ────► see the caveat below ◄──────────────────┘
```

**Classification method:** a short, cheap call to the chat model returning one
label. Heuristics alone are tempting but wrong — "what did I learn about Java?"
and "what is Java?" differ by two words. A first-person possessive check
(`I`, `my`, `we`, `our`) is a useful *prior*, not a decision.

**Ambiguity rule:** when the classifier is unsure, fall back to the stricter
branch. Refusing to invent personal history is the safer failure.

**Caveat on `CURRENT_INFORMATION`.** "What's the weather today?" cannot be
answered by a local model with no internet access, and adding a web-search tool
would put a network call on the path of a system whose defining property is that
nothing leaves the machine. **Recommendation: classify it, then answer honestly**
— "I can't look things up online" — rather than quietly acquiring an internet
dependency. Adding a search tool later is a deliberate decision with its own
privacy review, not a side effect of this work.

---

## 8. Recommended vector architecture

**Stay on PostgreSQL + pgvector. Do not add a second database.**

| | pgvector | Qdrant / Milvus / Weaviate |
|---|---|---|
| Consistency with metadata | Same transaction. A memory and its vector cannot diverge | Dual-write; needs reconciliation |
| Hybrid query | Vector distance + `WHERE user_id` + date filter in **one SQL statement** | Filter in one system, fetch in the other |
| Backup | One `pg_dump` | Two systems to back up consistently |
| Operations | Already running | Another service, another port, another failure mode |
| Ceiling | Degrades past roughly 10M vectors on modest hardware | Designed for far more |

At personal scale — plausibly hundreds of thousands of chunks after years — the
ceiling is not close. The requirement's own instruction ("evaluate whether
PostgreSQL + pgvector is sufficient before introducing another") is satisfied:
it is.

**But it has never executed.** The extension is not installed, so every line of
vector SQL in `EmbeddingStore` is unverified. That is the first thing to test.

### The real gap: chunks are not first-class

Today `embeddings` has `owner_type`, `owner_id`, `chunk_index` — but **the chunk
text is not stored**. Consequences:

- A citation can name the memory, never the passage.
- Requirement §14's "Timestamp 12:34 – 14:02" is impossible — there is nowhere to
  put a time range.
- Re-ranking has nothing to re-rank; the chunk text would have to be recomputed.
- A 200-page PDF is one memory, retrieved whole or not at all.

**Proposed `memory_chunks`**, sitting between memories and embeddings:

```
memories (1) ──< memory_chunks (N) ──< embeddings (1 per chunk)
```

| Column | Purpose |
|---|---|
| `id`, `memory_id`, `chunk_index` | identity and order |
| `content` / `content_encrypted` | the passage itself, following the memory's sensitivity |
| `content_type` | TEXT, TRANSCRIPT_SEGMENT, CHAT_WINDOW, CAPTION |
| `start_offset`, `end_offset` | character span in the source, for document citations |
| `start_ms`, `end_ms` | **time range for audio/video citations** |
| `page_number` | page for PDFs |
| `source_file_id` | original file, for "open at this point" |
| `token_estimate` | context-window budgeting |

`embeddings.owner_type` gains `CHUNK` and points at `memory_chunks.id`. This is
additive; existing rows keep working.

This single change unlocks requirement §3 (video/audio segments), §7
(re-ranking), §13 (compact context) and §14 (precise citations). **It is the
highest-value schema change in this document.**

---

## 9. Exact new tables

| Table | Why | Requirement |
|---|---|---|
| `memory_chunks` | passages with positional and temporal metadata | §3, §7, §13, §14 |
| `chat_messages_imported` | WhatsApp/chat messages: sender, recipient, timestamp, thread, attachment ref | §3 |
| `chat_threads` | the conversation a message belongs to | §3 |
| `media_segments` | video/audio scene and speech segments with time ranges | §3 |
| `intent_log` | classified intent per question — needed to tune the router honestly | §6 |

Modified, not new:

| Change | Why |
|---|---|
| `embeddings.owner_type` += `CHUNK` | point vectors at chunks |
| `memories.type` += `CONVERSATIONAL`, `DOCUMENT`, `MEDIA` | requirement §8 names these; the enum lacks them |
| `memories.processing_status` | requirement §9: `NEW/PROCESSING/READY/FAILED/ARCHIVED`. Today `MemoryStatus` is lifecycle (`ACTIVE/SUPERSEDED/ARCHIVED`) and does not express *processing* state |
| `memories.embedding_version` | requirement §9. `embeddings.model_name` records what produced a vector, but nothing marks a memory as needing re-embedding |

## 10. Exact new backend classes

```
rag/intent/
  QuestionIntent            enum: PERSONAL_MEMORY, GENERAL_KNOWLEDGE, HYBRID,
                                  CURRENT_INFORMATION, CASUAL_CONVERSATION
  IntentClassifier          interface
  ModelIntentClassifier     one short model call; heuristic prior; strict on doubt
  IntentDecision            record(intent, confidence, reasoning)

rag/
  ContextBuilder            requirement §13 — assembles a *budgeted* context
  ChunkReranker             interface
  CrossEncoderReranker      or a cheap model-scored implementation
  AnswerComposer            separates PERSONAL / GENERAL / INFERRED in the prompt
  Citation                  memoryId, chunkId, sourceFileId, timeRange, page

memory/chunk/
  MemoryChunk, MemoryChunkRepository
  ChunkingStrategy          interface
  TextChunkingStrategy      semantic/paragraph aware (replaces fixed-size)
  TranscriptChunkingStrategy  time-bounded segments
  ChatWindowChunkingStrategy  conversation windows, not per-message

files/chat/
  ChatImportService, WhatsAppExportParser, ChatMessage, ChatThread

files/media/
  MediaSegmentService, TranscriptionClient (Python boundary), KeyFrameExtractor

ai/
  AIModelProvider           rename/extend of AIService per requirement §11
  HuggingFaceModelProvider, OpenAIModelProvider  (optional, config-selected)
```

## 11. Exact new API endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/chat` | **modified** — routes by intent; response gains `intent`, `usedPersonalMemory`, richer `citations` |
| `POST` | `/api/chat/stream` | SSE token streaming (see [assistant-experience.md](assistant-experience.md)) |
| `GET` | `/api/memories/{id}/chunks` | inspect how a memory was split |
| `POST` | `/api/files/{id}/reprocess` | re-chunk and re-embed one file |
| `POST` | `/api/import/whatsapp` | chat export upload |
| `GET` | `/api/chat-threads`, `/api/chat-threads/{id}/messages` | browse imported conversations |
| `GET` | `/api/media/{fileId}/segments` | transcript segments with time ranges |
| `POST` | `/api/embeddings/rebuild` | re-embed everything after a model change (today's reindex only fills gaps) |
| `GET` | `/api/people`, `/api/events`, `/api/locations` | make the unmapped entity tables real — required for "what did I discuss with John?" |

## 12. Exact frontend components

**There is no frontend.** `mobile/` contains a README and nothing else; no
`package.json` exists anywhere. Requirement §10's "no API keys in frontend" is
trivially satisfied because there is no frontend.

When built (Phase 5, React Native/Expo), the components this architecture needs:

`ChatScreen` (with intent badge showing whether memory was used) · `CitationCard`
(source, date, and for media a seekable timestamp) · `MemoryBrowser` ·
`ProposalReviewQueue` (already has a backend) · `UploadScreen` with per-file
extraction status · `PhotoGallery` (backend endpoint exists) · `SensitiveToggle`.

## 13. Required dependencies

Already present: `tika-core`, `tika-parsers-standard-package`, `flyway`,
`spring-boot-starter-*`, `jjwt`, `postgresql`, `embedded-postgres` (test).

| New | For | Where |
|---|---|---|
| `pgvector` **extension** | vector search | PostgreSQL server, not Maven |
| `faster-whisper` | transcription | Python |
| `ffmpeg` | audio extraction, key frames | system binary |
| A vision model (e.g. LLaVA via Ollama) | photo captioning | Ollama pull |
| `piper` | speech out | Python, later |

No new Maven dependency is needed for the chunking, intent-routing or
context-building work. All of §7, §8 and §10 above are pure Java against what is
already on the classpath.

## 14. Required environment variables

Existing (17) are listed in [../SETUP.md](../SETUP.md) §3. New:

| Variable | Default | Purpose |
|---|---|---|
| `DIGITALSELF_INTENT_MODEL` | same as chat model | A small fast model can classify while a larger one answers |
| `DIGITALSELF_INTENT_ENABLED` | `true` | Kill switch back to strict memory-only |
| `RAG_MAX_CONTEXT_TOKENS` | `3000` | Context budget, replacing today's char cap |
| `RAG_RERANK_ENABLED` | `false` | Re-ranking costs a model call |
| `CHUNK_STRATEGY` | `semantic` | vs `fixed` |
| `TRANSCRIPTION_URL` | `http://localhost:8001` | Python service |
| `VISION_MODEL` | *(unset)* | Photo captioning; disabled when unset |

## 15. Migration plan

Additive and ordered so each step is independently shippable and reversible.

| Step | Migration | Risk | Reversible |
|---|---|---|---|
| 1 | Install pgvector, run V100, **verify `EmbeddingStore` actually works** | Medium — never executed | Yes, profile off |
| 2 | `V8` — `memory_chunks`, `embeddings.owner_type += CHUNK` | Low, additive | Yes |
| 3 | Backfill chunks from existing memories; keep memory-level embeddings until chunk ones are proven | Low | Yes |
| 4 | `V9` — new memory types, `processing_status`, `embedding_version` | Low, additive with defaults | Yes |
| 5 | Intent routing behind `DIGITALSELF_INTENT_ENABLED` | **Behavioural change** | Yes, flag |
| 6 | `V10` — chat import tables | Low | Yes |
| 7 | `V11` — media segments | Low | Yes |

No step drops a column or rewrites data. Step 5 is the only behavioural change
and is flag-guarded.

## 16. Data ingestion and retrieval flows

**Ingestion** — the pipeline exists for documents; the branches are what is new:

```
Upload ─► sniff type ─► hash/dedupe ─► encrypt ─► store blob ─► files row
                                                       │
                      ┌────────────────────────────────┴────────────┐
                      ▼              ▼            ▼                 ▼
                  document        photo        audio/video       chat export
                      │              │            │                 │
                  Tika text      EXIF (built)  ffmpeg ─► whisper  parse messages
                      │              │            │                 │
                      │          caption?     segments+times    windows+sender
                      └──────────────┴────────────┴─────────────────┘
                                          │
                                    chunk (strategy per type)   ◄── NEW
                                          │
                                    embed each chunk            ◄── NEW (per chunk)
                                          │
                                  memory + chunks + vectors
```

**Retrieval** — steps 1, 3, 4 exist; 0, 5, 6 are new:

```
0. classify intent                                   ◄── NEW, decides the rest
1. embed the query                                   (exists)
2. vector search over chunks + metadata filter       (exists at memory level)
3. Postgres full-text search                         (exists)
4. reciprocal rank fusion                            (exists)
5. re-rank top ~30 to top ~8                         ◄── NEW, optional
6. budget the context window, label each block       ◄── NEW
7. generate, cite chunk-level sources
```

## 17. Security model

Unchanged and already strong. What this work must not break:

- **Encryption at rest**: files always; memories when `sensitive`; raw inputs,
  proposals and chat messages unconditionally.
- **New chunks inherit their memory's sensitivity.** A chunk of a sensitive
  memory must be encrypted and must not be embedded — otherwise chunking becomes
  a hole straight through the encryption. This is the single biggest security
  risk in this proposal.
- **Minimum context**: requirement §10's "never send unrelated memories" is why
  the context budget in §16 step 6 matters — it is a privacy control, not just a
  token-cost control.
- **Ownership at the repository layer**, preserved on every new query.
- **Audit** every new operation.
- **Intent logging must store the classification, not the question**, or
  `intent_log` becomes an unencrypted copy of everything ever asked.
- Still missing, unchanged: **TLS**, and the app runs as the `postgres`
  superuser rather than the least-privilege role that already exists.

---

## Decisions needed before any code is written

1. **Approve the intent router** (§7), including answering `CURRENT_INFORMATION`
   honestly rather than adding internet access.
2. **Approve `memory_chunks`** (§8) as the next schema change — it is the
   prerequisite for precise citations, media segments and re-ranking.
3. **Confirm pgvector stays** and can be installed on the target machine. Without
   it, semantic retrieval does not exist and much of this is theoretical.
4. **Order**: recommended is intent routing first (small, high impact, no new
   dependencies, fixes a stated requirement violation), then chunking, then media.
5. **Photo captioning** needs a vision model — is adding one to Ollama acceptable?

Nothing proceeds until these are settled.
