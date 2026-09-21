# Backend (Phases 1-4)

Spring Boot API.

- **Phase 1 (foundation):** schema migrations, auth (register/login/refresh with Argon2 + JWT + refresh-token rotation), Ollama integration behind `AIService`/`EmbeddingService`, health check.
- **Phase 2 (basic memory):** memory create/revise/archive/restore with full version history, metadata editing, keyword/date/type/source/tag search.
- **Phase 3 (AI memory):** embeddings in pgvector, hybrid semantic + full-text retrieval, RAG chat grounded strictly in stored memories, AI-assisted memory extraction with confidence-gated review, and knowledge-graph links between memories.
- **Phase 4 (files, partial):** encrypted file upload/download/shred with envelope encryption and content sniffing, plus text extraction from PDFs and Office documents so uploads feed search and RAG. EXIF, transcription and WhatsApp import are not built yet.

File ingestion, mobile, personality and legacy are later phases — see [../docs/roadmap.md](../docs/roadmap.md).

Modules: `auth`, `memory`, `rag`, `conversation`, `ai`, `audit`, `health`, `config` (people/files land in Phase 4, see [../docs/architecture.md](../docs/architecture.md) Section 4).

## Memory API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/memories` | Create a memory (writes version 1) |
| `GET` | `/api/memories/{id}` | Fetch one memory |
| `GET` | `/api/memories` | Search: `keyword`, `type`, `source`, `status`, `from`, `to`, `tag`, `page`, `size` |
| `PUT` | `/api/memories/{id}/revision` | Correct content/date/confidence — always creates a new version |
| `PATCH` | `/api/memories/{id}/metadata` | Edit title/type/importance/privacy/tags — no new version |
| `GET` | `/api/memories/{id}/history` | Full version history, oldest first |
| `POST` | `/api/memories/{id}/archive` | Archive (never deletes) |
| `POST` | `/api/memories/{id}/restore` | Un-archive |
| `POST` | `/api/memories/{id}/links` | Link this memory to another (`targetMemoryId`, `linkType`) |
| `GET` | `/api/memories/{id}/links` | List incoming and outgoing links |
| `DELETE` | `/api/memories/{id}/links/{linkId}` | Remove a link |

Content changes and metadata changes are separate endpoints on purpose: a change to *what a memory says* must be versioned, a change to how it's labelled need not be. There is no hard-delete endpoint.

## Sensitive memories

Encrypting *every* memory would disable Postgres full-text search, which is currently the only working retrieval path — so encryption is per-memory instead of global.

Mark a memory sensitive with `"sensitive": true` on create, or flip it later via the metadata endpoint. `EMOTIONAL` memories are treated as sensitive automatically, without being asked for.

| | Ordinary memory | Sensitive memory |
|---|---|---|
| Title and content at rest | plaintext columns | AES-256-GCM, per-memory key |
| Version history | plaintext | encrypted, same key |
| Full-text search | yes | **no** |
| Semantic search | yes | **no** — not embedded at all |
| Find by date / type / tag / people | yes | yes |
| Readable through the API by you | yes | yes |

Two details worth knowing:

- **Titles are encrypted too.** A title like "Therapy session with X" discloses as much as the body, so protecting only the body would be a false promise.
- **Sensitive memories are never embedded.** An embedding is a lossy but genuine representation of the text; storing one unencrypted would undo the encryption. Promoting an existing memory to sensitive therefore *deletes* any embedding already derived from its former plaintext.

The trade-off is real and deliberate: a sensitive memory will not surface in chat answers or text search. It is findable by browsing, date, type, tags and linked people. Encrypted at rest means genuinely unreadable from a database dump — verified by a test that scans the raw tables for the plaintext.

## Chat / retrieval API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/chat` | Ask a question; answered only from retrieved memories |
| `POST` | `/api/memories/reindex` | Backfill embeddings for memories written while Ollama was unreachable |

`POST /api/chat` takes `{"question": "...", "conversationId": null}` and returns the answer plus `groundedInMemories` — the memory ids and provenance labels actually placed in the model's context, so any claim can be traced to a stored row.

### Intent routing

Every question is classified **before** any retrieval happens, so a general
question never searches personal memory:

| Intent | Retrieval | The model may… |
|---|---|---|
| `PERSONAL_MEMORY` | yes | answer **only** from retrieved memories; nothing found → it is never invoked |
| `HYBRID` | yes | use both, required to label which is which |
| `GENERAL_KNOWLEDGE` | **no** | answer from its own knowledge, marked as general |
| `CURRENT_INFORMATION` | no | nothing — answered honestly, there is no internet access |
| `CASUAL_CONVERSATION` | no | reply briefly |

`"What is the chemical formula of water?"` is answered normally.
`"What did I say about my Java project?"` is answered strictly from memory.
`"Given my architecture, should I use pgvector?"` uses both, kept apart.

The response carries `intent`, `intentConfidence`, `answeredFromMemory` and
`usedGeneralKnowledge`, so a surprising answer can be explained rather than
looking like the archive is empty.

Every failure path lands somewhere safe: an unreachable classifier falls back to
first-person heuristics, a low-confidence guess is not acted on, and anything
still ambiguous goes to the strictest branch. `DIGITALSELF_INTENT_ENABLED=false`
restores memory-only behaviour entirely.

**How hallucination is prevented structurally:** on the personal branch, if hybrid retrieval returns nothing above the relevance threshold, the model is never invoked at all — the endpoint returns `"I don't have a memory of that."` with `answeredFromMemory: false`. A prompt instruction alone is not relied on. Routing does not weaken this: it decides *whether* a question is personal, and personal questions are handled exactly as before.

### Passages, not whole memories

Retrieval works on **chunks** — passages within a memory — rather than memories
whole. A 200-page PDF is one memory but many passages, so an answer can cite the
paragraph it came from instead of the document.

Both searches return the same unit deliberately. Fusing a ranked list of memories
with a ranked list of passages would be comparing different things, so vector
search and full-text search both return chunks and reciprocal rank fusion
combines them.

Each chunk records where it sits in its source — character offsets for documents,
page numbers, and millisecond ranges for audio and video — which is what lets a
citation say `page 4` or `12:34–14:02`. `GET /api/memories/{id}/chunks` shows how
a memory was split, which is usually the explanation when an answer cites an odd
passage.

Text is split on paragraph boundaries, falling back to sentences and then words,
and every chunk satisfies `text.equals(source.substring(start, end))` — the
invariant that lets a citation highlight the exact span in the original.

**Chunks inherit their memory's sensitivity exactly.** A sensitive memory's
passages are encrypted under the same key, are never embedded, and have a null
plaintext column so they cannot match full-text search. Without that, splitting a
memory into passages would quietly undo the encryption applied to it.

Retrieval is hybrid (see [../docs/ai-architecture.md](../docs/ai-architecture.md)): pgvector cosine similarity fused with Postgres full-text ranking via reciprocal rank fusion. If Ollama is unreachable, vector search degrades to keyword-only rather than failing the question. The context is bounded by `max-context-tokens`, and retrieval stops at whole passages rather than clipping one — a truncated passage is worse evidence than one fewer passage.

Embedding happens *after* the database transaction commits, so an Ollama call never holds a Postgres transaction open. The trade-off: a memory can be saved successfully but left unindexed if the model was down — `POST /api/memories/reindex` backfills those.

## Files API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/files` | Upload (multipart `file`, optional `sensitive`) — encrypted before it touches disk |
| `GET` | `/api/files` | List your files, with extraction status |
| `GET` | `/api/files/{id}` | File metadata and extraction status |
| `GET` | `/api/files/{id}/content` | Download, decrypted on the way out |
| `POST` | `/api/files/reindex` | Retry extraction for files that failed or were never parsed |
| `DELETE` | `/api/files/{id}` | Permanent, irreversible shred |

## Text extraction

Uploading a PDF, Word document, spreadsheet or text file now makes its
**contents** searchable, not just its filename. Tika pulls the text out, it is
stored verbatim in `file_metadata`, and a memory is derived from it with
`source = FILE_EXTRACTION` — so it flows through the same search, embedding and
RAG path as everything else, and answers drawn from it are labelled "extracted
from a file the owner uploaded".

Extraction runs **after** the upload transaction commits, for the reason
embedding does: parsing a large PDF must not hold a database transaction open.
An upload therefore can succeed with extraction failing. That is the right
direction to fail — a file is never rejected because a parser choked — and
`POST /api/files/reindex` retries the ones that did.

`GET /api/files/{id}` reports which of five states a file is in:

| Status | Meaning | Retried by reindex? |
|---|---|---|
| `EXTRACTED` | Text found, memory derived | — |
| `EMPTY` | Parsed fine, no text layer (a scan) | no — it is a fact about the document |
| `UNSUPPORTED` | Photo, audio or video — a later phase | no |
| `FAILED` | The parser failed | **yes** |
| `PENDING` | Not attempted yet | yes |

`EMPTY` and `FAILED` are kept apart deliberately. A scanned PDF is images of
pages; there is genuinely no text to find, and retrying it on every backfill
forever would be noise. OCR would change that, and needs Tesseract — not
installed, not decided.

**Two limits, both protecting the process rather than the data.** An OOXML file
is a zip archive and a small one can expand to gigabytes, so stored text is
capped at `write-limit-chars` (500k) and the whole parse is abandoned after
`timeout-seconds` (60). Memory is bounded by the first, time by the second.
Hitting the character limit truncates rather than fails, and the truncation is
written into the memory text so an answer drawn from a partial document can say
so.

**Sensitive files.** `sensitive=true` on upload encrypts the extracted text under
the file's own key and derives a *sensitive* memory — so it is never embedded and
never full-text searched, exactly like a sensitive memory. The default is
plaintext, because text that cannot be searched would defeat the point of
extracting it. The trade-off is the one already made for memories; the reasoning
is in [../docs/file-ingestion.md](../docs/file-ingestion.md) Section 4.

## Photos

Uploading a photo reads its EXIF into the `media` table: when it was taken,
where, and its dimensions. `GET /api/files/{id}` returns them under `photo`.
The full metadata block is kept verbatim in `exif_json`, so deciding later that
a different tag mattered does not mean re-reading every photo.

`taken_at` is the *original* capture date, not the file timestamp — a copied or
re-encoded photo keeps the former and loses the latter, and it is the former that
says when the memory happened.

**Photos do not become memories, deliberately.** A document's text is something
someone asserted, so turning it into a memory puts a real statement into
retrieval. A photo's EXIF is not an assertion. Generating *"Photo taken on 3 June
2019 at 51.50, -0.12"* as a memory would place a sentence nobody ever said into
the pool the assistant answers from — which undercuts the no-hallucinated-memories
rule from the inside — and ten thousand photos would drown everything you actually
wrote. Photos are findable by date and location, and attachable to memories you
write. See [../docs/media-ingestion.md](../docs/media-ingestion.md) Section 3.

A photo still reports `UNSUPPORTED` for *text* extraction. That is the honest
answer to a question about text, and it is a different question from whether
capture metadata was read — which the `media` row records.

**Shredding a file also deletes the memory derived from it** — the one hard
delete in the system. Crypto-shredding is advertised as permanent, and it would
not be if the file's full text stayed readable in a memory row. Memories *you*
wrote that merely had the file attached are untouched. See
[../docs/file-ingestion.md](../docs/file-ingestion.md) Section 9.1.

**Encryption at rest.** Every file gets its own AES-256-GCM data-encryption key. The ciphertext goes to disk; the DEK, wrapped under the master key, goes to the database. Neither store is enough on its own, and neither is any use without `DIGITALSELF_MASTER_KEY`, which lives in the environment. Rotating the master key means re-wrapping small keys, not re-encrypting every file.

**Losing the master key means losing every file, permanently.** That is the intended property, not a bug — back it up somewhere separate from the data it protects.

Other properties, each covered by a test:
- Plaintext is never written to the storage directory — the integration test reads the raw blob back off disk and asserts the content isn't in it.
- Content type is sniffed from the bytes via Tika, so a `.exe` renamed to `.jpg` is still identified as an executable and rejected.
- GCM is authenticated, so tampered ciphertext fails loudly instead of returning corrupted data.
- Identical content encrypts to different ciphertext each time (fresh IV), so identical files aren't identifiable as such from storage.
- Re-uploading identical bytes reuses the existing record rather than writing a second blob.
- `DELETE` is crypto-shredding: destroying the wrapped key makes the ciphertext unrecoverable, which is more reliable than trying to overwrite bytes on an SSD.
- Writes are staged to a temp file and atomically moved, so a crash can't leave a half-written blob.
- Storage paths are resolved and checked for containment, so a crafted path can't escape the storage root.

## Memory extraction API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/extract` | Extract candidate memories from free text |
| `GET` | `/api/proposals` | List proposals awaiting review |
| `POST` | `/api/proposals/{id}/accept` | Turn a proposal into a real memory |
| `POST` | `/api/proposals/{id}/reject` | Discard a proposal |

Three guarantees this path is built around:

1. **Your words are stored first.** The raw text lands in `raw_inputs` before the model is called at all, and is never rewritten. If extraction fails, misfires, or the model is offline, the original is still there.
2. **Nothing is silently rewritten.** Extraction only ever creates *new* memories. It cannot modify or supersede an existing one — that always requires an explicit revision.
3. **Bad model output can't lose data.** Small local models emit malformed JSON often, so parsing is forgiving and falls back to keeping your text as one low-confidence proposal. An explicit `[]` from the model ("nothing here") is distinguished from a parse failure.

**Raw input, proposals and chat messages are always encrypted**, whether or not the resulting memory is sensitive. None of these tables is ever searched — they are fetched by id or by parent — so there was no trade-off to weigh, unlike memories. Proposals share their raw input's key and messages share their conversation's key, so destroying one key shreds a whole coherent unit rather than leaving fragments.

Everything extracted is marked `AI_INFERENCE`, so it stays distinguishable from what you said yourself, including in chat answers. Candidates at or above `digitalself.extraction.auto-accept-confidence` (default `0.9`) become memories immediately; below that they queue for review. Set it above `1.0` to review everything.

## Prerequisites

- JDK 21 and Maven 3.9+
- PostgreSQL (tested against a local 17 install; `pgcrypto` ships with it)
- Ollama, with a chat model pulled
- Optional: the `pgvector` extension, for semantic search — see below

Docker is **not** required. `docker/docker-compose.yml` remains as an alternative for anyone who prefers containers.

## Running without pgvector

pgvector is not part of a stock PostgreSQL install and needs to be added separately. The system runs without it:

| | With pgvector | Without pgvector |
|---|---|---|
| Auth, memory CRUD, versioning | works | works |
| Keyword / full-text search | works | works |
| Semantic (vector) retrieval | works | disabled, falls back to full-text |
| `POST /api/memories/reindex` | works | fails — there is no embeddings table |

`GET /api/health` reports which state you're in. To enable it once the extension is installed on the server, start with `SPRING_PROFILES_ACTIVE=pgvector`, which adds `db/migration-vector/V100__embeddings.sql` to the Flyway path.

## First-time setup (local install)

1. Create the database and application role — run as the postgres superuser, with your own chosen app password:
   ```powershell
   & "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d postgres `
       -v app_password="'your-password-here'" `
       -f scripts/sql/setup-local-db.sql
   ```
2. Copy `.env.example` (repo root) to `.env` and fill in `DB_PASSWORD` (the same password) and `JWT_SECRET` (generate your own).
3. Confirm the chat model in `.env` is one you actually have:
   ```powershell
   ollama list
   ```
4. Optional, for semantic search:
   ```powershell
   ollama pull nomic-embed-text
   ```

## Running the backend

Load the variables from `.env` into your shell, then:

```powershell
cd backend
mvn spring-boot:run
```

Flyway applies `db/migration/V1__init_schema.sql` on startup.

## Verifying it's up

```powershell
curl http://localhost:8443/api/health
```
reports `database`, `pgvector`, and `ollama` state.

```bash
curl -X POST http://localhost:8443/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"a-long-password-here","displayName":"You"}'
```
should return an access token and refresh token.

## Running tests

```bash
mvn test
```
All tests run without a database or a model:

- `JwtServiceTest` — token issuance, signature rejection, fail-fast on a blank secret.
- `MemoryServiceTest` — versioning guarantees (revision supersedes but never deletes, archive never deletes, ownership enforced, reindex queued on content change).
- `TextChunkerTest` — chunking and overlap.
- `HybridRetrieverTest` — RRF fusion ranking, distance ceiling, graceful degradation when embedding fails, corrected-memory labelling.
- `RagServiceTest` — **the model is never called when retrieval is empty**, provenance reaches the prompt, both conversation turns are stored.
- `MemoryExtractorTest` — malformed JSON, markdown fences, surrounding prose, unreachable model, clamped confidence; **your text is never dropped**.
- `ExtractionServiceTest` — raw input stored before the model runs, confidence gating, extracted memories always marked `AI_INFERENCE`, proposals can't be reviewed twice.
- `MemoryLinkServiceTest` — self-links rejected, ownership of both ends checked before writing an edge, no duplicate edges, incoming/outgoing direction.
- `FileTextExtractorTest` — real PDFs assembled byte by byte (rather than with PDFBox, whose API moved between 2.x and 3.x), plain text normalisation, a PDF with no text layer coming back empty rather than failing, truncation at the write limit, and media types skipped without a parse.
- `FileExtractionWorkerTest` — where extracted text goes: `FILE_EXTRACTION` provenance, the derived memory attached to its file, **a sensitive file leaving no plaintext anywhere**, a failed parse recorded as retryable without losing the file, a scan settled as `EMPTY`, and re-extraction revising the existing memory instead of adding a second.
- `FileIngestionServiceTest` — an extraction failure never escapes into the already-committed upload, and one bad document does not abort the rest of a backfill.
- `ImageMetadataReaderTest` — the malformed EXIF that actually arrives: out-of-range coordinates dropped rather than violating the check constraint, unparseable coordinates dropped without costing the capture date, and parser provenance kept out of the stored block.

- `SchemaIntegrationTest` — runs against a **real PostgreSQL**, started as a temporary subprocess by Zonky embedded-postgres (no Docker, no install, your own server untouched). It verifies the Flyway migrations apply, every JPA mapping matches the schema (via `ddl-auto: validate`, which fails the context on any mismatch), version history persists across a revision, tsvector search matches stemmed words, search does not leak across users, archive hides without deleting, links persist in both directions, and extraction preserves raw input.

Several expected `WARN` lines appear during the run (`ollama down`, `could not parse`, `Could not index memory`) — they belong to the failure-path tests, not to failures.

The integration test earned its place immediately: it caught a `LazyInitializationException` that would have broken **every** memory endpoint in production. `MemoryResponse.from()` was mapping the lazy `tags` collection in the controller, after the service transaction had closed. Since `open-in-view` is disabled deliberately, that throws at runtime — and no unit test with mocked repositories could have seen it. The fix was to map entities to DTOs inside the service transaction; the web layer no longer touches entities at all.

## Known gaps (tracked, not yet done)

- No TLS wired up yet — `server.ssl.*` in `application.yml` is commented out; uncomment and point at a local cert (e.g. via `mkcert`) before trusting this over anything but localhost.
- **Only memories marked sensitive are encrypted.** Ordinary memory text sits in plaintext columns so it stays searchable — see "Sensitive memories" above for the reasoning and the trade-off.
- DB user is not yet least-privilege — the app currently connects as `postgres`.
- Transcription is not implemented, so audio and video contribute nothing — they are recorded `UNSUPPORTED`, waiting on the Python pipeline. Scanned PDFs need OCR (Tesseract), which is not installed or decided.
- **No test reads a real EXIF-bearing JPEG.** The mapping is covered against synthetic Tika metadata and the wiring against an EXIF-free PNG, so the path from real camera bytes to `taken_at` is unproven. Upload one photo from your phone and check `GET /api/files/{id}`.
- The extraction and EXIF code has **not been run**: `mvn test` has not been executed against it, and `tika-parsers-standard-package` has never been downloaded. Treat those tests as unverified until that first run.
- Photos are stored and readable but there is no endpoint to browse them by date or location yet — `MediaMetadataRepository.findTakenBetween` exists and is unused.
- One memory per document is coarse. A 200-page PDF is a single memory, retrieved or not as a unit; `max-chars-per-context-memory` caps what reaches the prompt. One memory per section is the follow-up.
- The **pgvector** SQL in `EmbeddingStore` is still unverified — the extension isn't installed locally, so vector insert/search has never executed. Everything else in the schema now runs against real Postgres in `SchemaIntegrationTest`.
- The HTTP layer is unverified: no test drives the controllers, JWT filter, or Spring Security chain end-to-end. Services and persistence are covered; request/response wiring is not.
- The app has never been started against the developer's own PostgreSQL instance, only the embedded one.
- Embedding dimension is hard-coded to 768 (`nomic-embed-text`) in the migration; switching to a model with a different dimension needs a follow-up migration.
- `POST /api/memories/reindex` re-embeds only memories with *no* embedding. Re-embedding everything after a model change is not implemented yet.
