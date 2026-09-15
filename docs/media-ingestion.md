# Media Ingestion — Design (Phase 4 remainder)

Status: **EXIF implemented (Section 7), not yet run.** Approved 2026-09-15 and
built, along with the Section 3 decision that photos do not become memories.
Sections 5 and 6 — TLS, WhatsApp, transcription — remain proposals awaiting
approval.

Covers what [file-ingestion.md](file-ingestion.md) left out: photos, audio, video
and WhatsApp exports — and the question that document explicitly deferred, which
is how decrypted content crosses a process boundary without undoing the file
store's guarantees.

## 1. The finding that moves the line

**EXIF needs no Python, and no new dependency.**

The Tika parser bundle added for PDFs already contains the image parsers. When
`FileTextExtractor` parses a file it constructs a `Metadata` object and
[throws it away](../backend/src/main/java/com/digitalself/files/FileTextExtractor.java#L107)
— and that object is precisely where EXIF arrives: `TIFF.ORIGINAL_DATE`,
`Geographic.LATITUDE` and `LONGITUDE`, `TIFF.IMAGE_WIDTH` and `IMAGE_LENGTH`,
camera make and model.

So the Java/Python split moves one notch. It is not the convenience that matters
— it is the same argument that put text extraction in Java in the first place:
**EXIF in-process means no plaintext leaves the JVM.** Sending a photo to another
runtime to read a date off it would introduce the exact boundary this phase has
been avoiding, for a capability already sitting unused in the process.

What genuinely still needs Python: transcription and WhatsApp parsing.

## 2. What EXIF buys

A photo today is an opaque encrypted blob with a filename. With EXIF it becomes
findable by **when** and **where** it was taken — which for a memory archive is
most of a photo's value. The `media` table has been waiting for this since V1:

```sql
media(file_id, media_type, exif_json, taken_at, duration_seconds, width, height)
```

`exif_json` keeps the full metadata block verbatim, for the same reason
`file_metadata.extracted_text` does: a later change of mind about which fields
matter should not require re-reading every photo.

## 3. Photos do not become memories

**Why needed:** the roadmap says "EXIF extraction and linking photos to
memories", and the obvious reading is that each photo gets a derived memory, the
way each document does.

**Recommendation: no.** Store EXIF on `media`, make photos findable by date and
location, and let a photo be *attached* to a memory you write. Do not generate
one.

**Why:** a document's text is an assertion — someone wrote those words, and
surfacing them in an answer is surfacing something that was said. A photo's EXIF
is not. Auto-generating "Photo taken on 3 June 2019 at 51.50, -0.12" as a memory
would put a sentence nobody ever asserted into the retrieval pool, where the RAG
layer would treat it as knowledge and answer from it. The project's second
development rule exists to stop the assistant stating things it was not told;
manufacturing memories from file metadata undermines it from the inside.

The coarser cost of getting this wrong is volume: ten thousand photos would
become ten thousand memories, and drown everything actually written.

**Alternative considered:** derive a memory only when EXIF has both a date *and*
a location, so the generated sentence is at least specific. Rejected — it makes
the rule harder to explain without fixing the objection.

**What replaces it:** a search filter on `media.taken_at` and location, so photos
are browsable on their own terms, and `memory_files` for attaching them.

## 4. Extraction status stays about text

Images are currently recorded `UNSUPPORTED`, whose javadoc says later phases
handle them. Once EXIF works that reading is wrong, and the temptation is to add
a status like `METADATA_ONLY`.

**Recommendation:** leave `extraction_status` strictly about text, and let the
presence of a `media` row record that metadata was read. They are two questions
about the same file — "does it have text?" and "does it have capture metadata?" —
and one column cannot answer both without becoming a matrix of combinations.

`UNSUPPORTED` stays truthful for an image: there is no text layer. Only its
documentation needs amending.

## 5. The process boundary — the decision file-ingestion.md deferred

Transcription and WhatsApp parsing run in Python, which means decrypted content
must reach another process. Four ways, none free.

**(a) Python calls the API.** The pipeline authenticates, downloads the decrypted
file from `GET /api/files/{id}/content`, and posts extracted text back. Plaintext
crosses a localhost HTTP connection.

- *For:* the API already exists, ownership and audit are enforced by code that is
  already tested, and the master key never leaves the JVM.
- *Against:* plaintext on a socket. On localhost that is a modest exposure, but it
  is not nothing: any local process that can bind or sniff loopback, and any
  proxy configured system-wide, sees it.

**(b) Python reads the encrypted blobs directly.** Rejected outright — it
requires giving a second runtime the master key, which doubles the number of
places the one secret that protects everything can leak from.

**(c) Java shells out to a local binary** (`whisper.cpp`) with a temporary file.

- *Against:* plaintext hits disk, which is the single property the file store is
  built to prevent. A temp file on an SSD cannot be reliably erased — the reason
  deletion here is crypto-shredding rather than overwriting.

**(d) Transcription inside the JVM** via an ONNX or JNI Whisper port. Keeps
everything in-process, but the available bindings are immature and would put a
native library in the path of every upload.

**Recommendation: (a), with conditions.** It is the least-bad, and the conditions
are not optional:

1. **TLS must be wired up first.** It is currently a known gap — `server.ssl.*`
   is commented out in `application.yml`. Sending decrypted personal content over
   plaintext HTTP, even on loopback, is not a trade-off worth making when the fix
   is already on the list.
2. A scoped, short-lived token for the pipeline rather than the owner's own
   credentials, so a compromised script cannot read the whole archive.
3. The pipeline holds content in memory and never writes it to disk.
4. Bound to `127.0.0.1` only.

**Future limitation:** condition 3 is a property of a script, not something the
backend can enforce. The boundary is a trust boundary whatever is done, which is
the real reason to keep as much as possible on the Java side of it.

## 6. Sequencing

The dependency in Section 5 sets the order, and it is not the roadmap's order:

| | Work | Needs | Blocked by |
|---|---|---|---|
| 1 | **EXIF in Java** | nothing | — |
| 2 | **TLS** | a local cert (`mkcert`) | — |
| 3 | **WhatsApp import** | Python 3.11+ | TLS |
| 4 | **Transcription** | Python, `faster-whisper`, a model pull | TLS |

WhatsApp before transcription deliberately: it is pure text parsing with no model
involved, so it proves the whole Python boundary — auth, token scope, posting
memories back — against something that cannot fail for machine-learning reasons.
Debugging a transport and a transcription model at the same time is two problems
pretending to be one.

Steps 1 and 2 need nothing installed.

## 7. What EXIF changed

New:
- [`MediaMetadata`](../backend/src/main/java/com/digitalself/files/MediaMetadata.java) entity + repository — `media` had been unmapped since V1.
- [`ImageMetadataReader`](../backend/src/main/java/com/digitalself/files/ImageMetadataReader.java) — reads the Tika `Metadata` that `FileTextExtractor` was discarding.
- `MediaType` enum.
- `V7__photo_metadata.sql` — latitude and longitude columns (V1 had nowhere to put coordinates as numbers), range constraints, `extracted_at`, and an index on `taken_at`.

Modified:
- `FileTextExtractor` — `Result` carries the `Metadata`; new `isImage()` alongside `supports()`, because "is there text here?" and "is there capture metadata here?" are different questions and a photo answers them differently.
- `FileExtractionWorker` — writes a `media` row for images, best-effort.
- `FileResponse` — a nested `photo` object with `takenAt`, dimensions and coordinates.
- `ExtractionStatus.UNSUPPORTED` — javadoc amended per Section 4.

Tests: `ImageMetadataReaderTest` (the malformed cases, which are what actually
arrive — out-of-range coordinates dropped rather than violating the check
constraint, one bad tag not costing the others, parser provenance excluded from
the stored block), plus worker tests and three integration cases including
**that a photo never becomes a memory**.

**Not verified:** no test reads a real EXIF-bearing JPEG. The mapping is tested
against synthetic `Metadata`, and the wiring against a valid PNG that carries no
EXIF — so the path from *real camera bytes* to `taken_at` is unproven. Uploading
one photo from your phone and checking `GET /api/files/{id}` is the honest first
test.

## 8. Phases 5-8

Untouched by this document and still gated on the working agreement. Worth noting
only that Phase 5 (mobile) carries its own unresolved decision — remote-access
networking, VPN or tunnel — which the roadmap already flags as needing an explicit
answer before shipping, and which TLS (item 2 above) is a prerequisite for as well.
