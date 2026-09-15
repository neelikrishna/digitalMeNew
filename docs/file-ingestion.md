# File Ingestion — Design (Phase 4, text extraction)

Status: **implemented, not yet run.** Approved 2026-09-15 and built; `mvn test` has
not been run against it, since the build is the owner's to run.

Two things below were decided during implementation rather than in the approved
design, both flagged in Section 9.

Scope of this document: turning uploaded documents into searchable, retrievable
text. EXIF, transcription and WhatsApp import are deliberately excluded — see
Section 8.

## 1. The problem

`POST /api/files` encrypts a file and stores it. Nothing ever reads it back
except `GET /api/files/{id}/content`. A PDF you upload is, as far as search and
chat are concerned, invisible: ask "what did the mortgage offer say" and the
answer is "I don't have a memory of that", even though the document is sitting
in the store.

The file store works. What is missing is the path from *bytes on disk* to
*something retrieval can see*.

## 2. Where extracted text lives

**Why needed:** extraction produces text that has to be two things at once — an
unaltered record of what the document said, and something the retrieval layer
can rank. Those pull in different directions, so they get different homes.

**Recommendation:** write to both, with distinct jobs.

| Store | Holds | Rewritten? | Read by |
|---|---|---|---|
| `file_metadata.extracted_text` | Verbatim parser output | Never | Backfill, re-derivation, audit |
| A derived `Memory`, `source = FILE_EXTRACTION` | The same text as a memory | Via normal revision | Search, RAG, the API |

This mirrors the guarantee `raw_inputs` already gives the extraction path: the
original lands first and is never touched, so a bad parse or a later change of
approach can always be redone from source.

The two are joined by `memory_files`, which already exists in
[V1__init_schema.sql:254](../backend/src/main/resources/db/migration/V1__init_schema.sql#L254).

**Why a Memory rather than a new retrievable kind:** the entire retrieval path
is memory-shaped —
[`HybridRetriever`](../backend/src/main/java/com/digitalself/rag/HybridRetriever.java),
[`MemoryTextSearch`](../backend/src/main/java/com/digitalself/memory/MemoryTextSearch.java),
`EmbeddingStore.searchMemories`, `RetrievedMemory`, and
`ChatResponse.groundedInMemories`. Introducing files as a second kind means a
union type threaded through all of them and a breaking change to the chat
response. Introducing them as memories costs one row and inherits versioning,
archive, tags, links and provenance for free.

The design was evidently always this: `MemorySource.FILE_EXTRACTION` exists, and
[`PromptBuilder`](../backend/src/main/java/com/digitalself/rag/PromptBuilder.java#L24)
already instructs the model how to treat text "extracted from a file the owner
uploaded". Neither is reachable today. This closes that gap rather than opening
a new one.

**Alternative considered:** index file chunks directly.
`EmbeddingOwnerType.FILE_CHUNK` already exists in the enum and is unused —
evidence someone considered exactly this. Rejected for now on two grounds: it
produces vector hits with no full-text counterpart, which makes reciprocal rank
fusion lopsided (a file could only ever be found by one of the two arms), and
nothing in `RetrievedMemory` can carry a chunk that belongs to no memory. Worth
revisiting only if whole-document memories turn out to be too coarse to rank
well.

**Future limitation:** one memory per document is coarse. A 100-page PDF becomes
one memory whose chunks are embedded separately but which is retrieved or not as
a unit. If that proves too blunt, the fix is one memory per section rather than
abandoning the model.

## 3. When extraction runs

**Recommendation:** after the upload transaction commits, via
`@TransactionalEventListener(AFTER_COMMIT)` — exactly the pattern
[`MemoryIndexer`](../backend/src/main/java/com/digitalself/memory/MemoryIndexer.java#L45)
already uses.

**Why:** parsing a large PDF takes seconds and, once a derived memory exists,
embedding it takes seconds more. Neither may hold a Postgres transaction open.

**The trade-off, stated plainly:** an upload can succeed while extraction fails.
That is the correct direction to fail — a file must never be rejected because a
parser choked — but it means extraction state must be visible and repairable,
not silent. Hence Section 6.

**Alternative considered:** extract synchronously inside `upload()`. Simpler and
atomic, but it makes upload latency a function of document size and lets a
malformed PDF fail an upload that would otherwise have succeeded. Rejected.

## 4. The encryption tension — this needs your decision

This is the one genuinely hard question, and it has no answer that is free.

Every file is encrypted at rest under its own key, and the README states the
property flatly: plaintext is never written to the storage directory. Extraction
breaks that promise if it is careless — `file_metadata.extracted_text` is an
ordinary `TEXT` column. Extracting a PDF into it puts the document's full
contents in the database in the clear. The blob stays encrypted, and the
protection is worth nothing, because the same words are readable one table over.

The project has faced this before and answered it for memories: encryption is
per-memory, because encrypting everything would disable the full-text search
that is currently the only working retrieval path. The same trade-off applies
here, unchanged:

| | Extracted to plaintext | Extracted and encrypted |
|---|---|---|
| Full-text search | yes | no |
| Semantic search | yes | no |
| Contributes to chat answers | yes | no |
| Readable from a database dump | **yes** | no |

**Recommendation: per-file, chosen at upload, defaulting to plaintext.** A
`sensitive` flag on upload mirrors the per-memory flag exactly. A sensitive file
is extracted, but both `extracted_text` and the derived memory are encrypted
under the file's existing data key, and the memory is created with
`sensitive = true` — which the existing
[`MemoryIndexer`](../backend/src/main/java/com/digitalself/memory/MemoryIndexer.java#L66)
already honours by refusing to embed it.

The consistency argument is what decides it: a user who has already been asked
to reason about sensitive *memories* should not have to learn a second, different
rule for *files*. The cost is that the default is the unsafe-feeling one, and
that has to be documented rather than buried.

**Alternatives considered:**
- *Always plaintext.* Simplest, best search. But it makes file encryption
  decorative for any document that is extractable, which is most of them.
- *Always encrypted.* Honest about the promise, and useless — extraction whose
  entire purpose is to feed search would feed nothing.

**Future limitation:** the choice is made at upload, when you may not yet know
what a document contains. Promoting a file to sensitive afterwards must
re-encrypt `extracted_text`, flip the derived memory, and delete its embeddings
— the same demotion path memories already have, and it must be built at the same
time, not later.

## 5. What gets parsed

Apache Tika, via `tika-parsers-standard-package`. `tika-core` is already a
dependency for content sniffing; this adds the parsers behind it.

In scope: PDF, DOCX, XLSX, PPTX, ODT, RTF, plain text, Markdown, HTML, CSV.

**Explicitly out of scope: scanned documents.** A PDF that is images of pages
returns empty text from Tika — it is not an error, there simply is no text
layer. OCR needs Tesseract, which is a separate install and a separate decision.
What matters is that this case is *reported*, not silently recorded as a
successful extraction of nothing. See Section 6.

**Risk to accept knowingly:** document parsers are a real attack surface — zip
bombs in OOXML containers, malformed font tables in PDFs, XML entity expansion.
Tika's CVE history reflects that. Here the files are the owner's own, uploaded by
the owner, on the owner's machine, which lowers but does not erase the risk.
Mitigations to build in, not defer:

- A write limit on the parse output, so an expanding archive cannot exhaust heap.
- A wall-clock timeout per file, so a pathological document cannot pin a thread.
- Parsing stays off the request thread already, by Section 3.

## 6. Failure, emptiness, and repair

Three outcomes, three distinct records — collapsing them is how "why is my PDF
not searchable" becomes unanswerable.

| Outcome | `extracted_at` | `extracted_text` | `extraction_source` |
|---|---|---|---|
| Parsed successfully | set | the text | `tika` |
| Parsed, no text layer | set | `''` | `tika:empty` |
| Parser failed | null | null | null |

Only the third is retried. The second is a fact about the document, not a
failure, and retrying it forever would be noise.

`POST /api/files/reindex` backfills the third case, mirroring
`POST /api/memories/reindex`. `GET /api/files/{id}` gains an extraction status
so the empty case is visible without reading logs.

## 7. What was built

New:
- [`FileTextExtractor`](../backend/src/main/java/com/digitalself/files/FileTextExtractor.java) — Tika wrapper with the write limit and timeout. No persistence or Spring transaction concerns, so it is testable against real documents.
- [`FileExtractionWorker`](../backend/src/main/java/com/digitalself/files/FileExtractionWorker.java) — the transactional unit of work: extract one file, store the text, derive the memory.
- [`FileIngestionService`](../backend/src/main/java/com/digitalself/files/FileIngestionService.java) — the after-commit listener and the backfill loop.
- [`FileMetadata`](../backend/src/main/java/com/digitalself/files/FileMetadata.java) + repository, `ExtractionStatus`, `FileUploadedEvent`, `FileExtractionException`.
- `MemoryFileLinkStore` — the `memory_files` join table, which had no mapping.
- [`DerivedMemoryRemover`](../backend/src/main/java/com/digitalself/files/DerivedMemoryRemover.java) — see Section 9.1.
- `V6__file_text_extraction.sql`, `FileExtractionProperties`.

The worker and the listener are separate beans on purpose: a `@Transactional`
method called from another method of the same bean bypasses Spring's proxy and
runs with no transaction at all, and both the listener and the backfill call it.

Modified:
- [`FileService`](../backend/src/main/java/com/digitalself/files/FileService.java) — takes `sensitive`, publishes the event, creates the metadata row, and shreds the derived memory. `readContent` splits internal decryption from `download`, so extraction reading a file is not logged as the owner downloading it.
- [`pom.xml`](../backend/pom.xml) — `tika-parsers-standard-package`.
- `FileResponse`, `FileController` — extraction status, the `sensitive` flag, `POST /api/files/reindex`.
- `PromptBuilder`, `RagProperties` — Section 9.2.
- `ChangeReason` — a `FILE_RE_EXTRACTION` value, so a re-parse is not misdescribed as a reclassification.

Tests: `FileTextExtractorTest` (real assembled PDFs, plain text, no-text-layer,
truncation, unsupported types), `FileExtractionWorkerTest` (where text goes, and
the sensitive/empty/failed/unsupported branches), `FileIngestionServiceTest`
(failures never escape into a committed upload; one bad file does not abort a
backfill), and six cases in `SchemaIntegrationTest` against real PostgreSQL —
including that an uploaded document becomes findable by full-text search, and
that shredding destroys the derived memory but not the owner's own.

Not done: `architecture.md` Section 3 still assigns all ingestion to Python and
has not been amended to record this split.

## 8. Deferred to the Python pipeline

Unchanged from [architecture.md](architecture.md) Section 3. These stay in
Python because the library gap is real, not incidental:

- **EXIF** (`exifread`) — dates and GPS from photos, into the `media` table.
- **Transcription** (`faster-whisper`) — audio and video.
- **WhatsApp export parsing** — chat logs into per-conversation memories.

Each needs a decision this document does not make: how decrypted content crosses
a process boundary without undoing the file store's guarantees. That is the
right time to design it, and not before — text extraction needs no such crossing,
which is exactly why it is being done first.

Uploads of these types are recorded `UNSUPPORTED` rather than failed, so they sit
visibly waiting for that phase instead of looking broken.

## 9. Decided during implementation

Two questions the design did not anticipate. Both are recorded here rather than
only in code comments, because both change a promise the project makes.

### 9.1 Shredding a file hard-deletes the memory derived from it

Nowhere else does this system hard-delete a memory: archive is the only removal
path, deliberately. But `DELETE /api/files/{id}` advertises permanent,
irreversible deletion, and crypto-shredding delivers that by destroying the
file's key.

Extraction breaks that. The derived memory holds the file's text in an ordinary
column. Destroying the key while leaving that row behind would mean the words
survive in full, readable, one table over — the deletion would be theatre.

So `DerivedMemoryRemover` deletes the derived memory, its version history, its
embeddings and its links when the file is shredded. Given a choice between
breaking the no-hard-delete rule for rows a machine wrote, and breaking the
permanent-deletion promise for everything, it breaks the first.

What survives: any memory *you* wrote that merely had the file attached. Only
the one memory extraction itself created is removed, which is why
`file_metadata.derived_memory_id` records which one that is instead of inferring
it from `memory_files`.

### 9.2 A per-memory cap on prompt context

A memory derived from a 200-page PDF is enormous, and `PromptBuilder` appended
every retrieved memory in full. Eight of those would exceed the context window of
any model this is meant to run on locally — the feature would have worked right
up until someone uploaded a real document.

`digitalself.rag.max-chars-per-context-memory` (default 2000) now caps what one
memory contributes to a prompt, and the cut is announced in the text so the model
can qualify an answer drawn from a partial memory. Retrieval is unaffected:
full-text search and embeddings still cover the entire document. Only the prompt
is clipped.

This is the granularity limitation from Section 2 arriving early. The real fix is
one memory per section rather than per document, and it is still the right
follow-up.
