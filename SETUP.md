# Setup — what to install and configure

Everything this project needs on the machine that will actually run it. Nothing
here has been installed on the laptop the code was written on, so treat this as
a checklist to work through once, not a description of a working setup.

Written 2026-09-21.

---

## 1. Required to run anything

| Software | Version | Why | Check |
|---|---|---|---|
| **JDK** | 21 | The backend targets Java 21 (`pom.xml` → `java.version`) | `java -version` |
| **Maven** | 3.9+ | Build and test runner | `mvn -version` |
| **PostgreSQL** | 16 or 17 | The only datastore. 17 is what the code was developed against | `psql --version` |
| **Ollama** | 0.3+ | Local LLM and embeddings. Nothing is sent to any cloud provider | `ollama --version` |
| **Git** | any | Repo already initialised, 2 commits | `git --version` |

### Ollama models to pull

```bash
ollama pull nomic-embed-text    # ~274 MB. Embeddings. Must be 768-dimensional
ollama pull llama3.2:1b         # ~1.3 GB. Chat. Small and weak — see note below
```

`nomic-embed-text` is **not optional if you want semantic search**, and its
768 dimensions are hard-coded in the migration
(`db/migration-vector/V100__embeddings.sql`). A model with a different dimension
needs a follow-up migration.

`llama3.2:1b` is the default only because it was already present during
development. It is small enough to produce poor extraction JSON and weak chat
answers. If your personal machine has the memory, prefer something larger
(`llama3.1:8b` or better) and set `OLLAMA_CHAT_MODEL` accordingly — the code
does not care which model it is.

---

## 2. Secrets to generate

Copy `.env.example` to `.env` and fill in these three. `.env` is gitignored;
never commit it.

```powershell
# JWT_SECRET — signs access tokens
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))

# DIGITALSELF_MASTER_KEY — must decode to exactly 32 bytes
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

```bash
# same, on macOS/Linux
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 32   # DIGITALSELF_MASTER_KEY
```

> **`DIGITALSELF_MASTER_KEY` is not recoverable.** It encrypts every uploaded
> file, every sensitive memory, all raw input, proposals and chat messages. Lose
> it and that data is permanently unreadable — by you as much as by anyone else.
> That is the intended design, not a flaw. Back it up somewhere that is *not*
> the same place as your database backups, or the two together defeat the point.

Both are fail-fast: the application refuses to start if either is missing, rather
than running in a silently insecure state.

---

## 3. Full environment variable list

Every variable the app reads, with its default. Only the three marked **required**
have no usable default.

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `postgres` | Tables live in a schema *inside* this database, not a database of their own |
| `DB_SCHEMA` | `digitalself` | Flyway creates it on first run — no manual `CREATE SCHEMA` |
| `DB_USER` | `postgres` | See §5 about not running as superuser |
| `DB_PASSWORD` | *(none)* | **Required** |
| `JWT_SECRET` | *(none)* | **Required.** 256+ bits |
| `DIGITALSELF_MASTER_KEY` | *(none)* | **Required.** Exactly 32 bytes, base64 |
| `DIGITALSELF_MASTER_KEY_LABEL` | `default` | Identifies which master key wrapped a given record, for future rotation |
| `FILE_STORAGE_PATH` | `./data/files` | Encrypted blobs. Gitignored. Back this up *with* the database — one is useless without the other |
| `SERVER_PORT` | `8443` | |
| `MAX_UPLOAD_SIZE` | `100MB` | |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `OLLAMA_CHAT_MODEL` | `llama3.2:1b` | Must be a model you have actually pulled |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Must produce 768 dimensions |
| `DIGITALSELF_INTENT_ENABLED` | `true` | Routes each question before retrieval so general questions are answered from the model's own knowledge. Set `false` to treat everything as a memory question |
| `DIGITALSELF_INTENT_MODEL` | *(unset)* | Model used to classify questions. Empty reuses the chat model. Worth pointing at something small — it emits one word and runs on every question |
| `SPRING_PROFILES_ACTIVE` | *(unset)* | Set to `pgvector` only after installing the extension — see §4 |
| `SSL_KEYSTORE_PATH` | *(unset)* | Only once TLS is enabled — see §6 |
| `SSL_KEYSTORE_PASSWORD` | *(unset)* | |

---

## 4. pgvector — optional, and the app knows it

`pgvector` is **not** part of a stock PostgreSQL install. The system runs without
it, and `GET /api/health` reports which state you are in.

| | With pgvector | Without |
|---|---|---|
| Auth, memory CRUD, versioning, files | works | works |
| Keyword / full-text search | works | works |
| Semantic (vector) search | works | disabled |
| Chat answers | vector + full-text retrieval | full-text only |
| `POST /api/memories/reindex` | works | fails — no embeddings table |

Installing it:

- **Linux/macOS:** `brew install pgvector`, or your package manager, or build from
  source — straightforward.
- **Windows:** no official prebuilt binary exists. Either compile from source with
  MSVC Build Tools, or use an unofficial third-party build. The latter means
  putting an unvetted binary into `C:\Program Files\PostgreSQL\...` and needs
  admin rights, which is why it was not done on the work laptop.

Once installed, start the app with `SPRING_PROFILES_ACTIVE=pgvector`. That adds
`db/migration-vector/V100__embeddings.sql` to the Flyway path, creating the
extension, the `embeddings` table and its HNSW index.

**Unverified:** the pgvector SQL in `EmbeddingStore` has never executed, because
the extension was never available. Expect this to need debugging on first run.

---

## 5. Database setup

Flyway creates the schema and every table on first startup. There is no manual
SQL step for normal use.

The one thing worth doing by hand is **not running the app as the `postgres`
superuser**. `scripts/sql/setup-local-db.sql` creates a `digitalself_app` role;
it is written but not currently used. Run it, then point `DB_USER`/`DB_PASSWORD`
at that role.

---

## 6. TLS — an open decision

`server.ssl.*` is commented out in `application.yml`. The app currently serves
plain HTTP, which is tolerable only while everything is on one machine.

Two options, **not yet decided**:

- **`keytool`** — ships with the JDK, nothing to install, produces a self-signed
  cert. Browsers and mobile clients will warn unless you add an exception.
- **`mkcert`** — one small install, produces a certificate your machine actually
  trusts, no warnings.

This matters beyond tidiness: the remaining Phase 4 work (WhatsApp parsing,
transcription) sends decrypted personal content to a Python process over
localhost, and that should not travel in clear text. See
`docs/media-ingestion.md` §5.

---

## 7. Later phases — install only when you reach them

| Phase | Software | Notes |
|---|---|---|
| 4 (rest) | **Python 3.11+** | WhatsApp export parsing |
| 4 (rest) | **`faster-whisper`** + a Whisper model | Audio/video transcription. Model download is GB-scale |
| 4 (optional) | **Tesseract** | OCR for scanned PDFs. Not decided, not designed |
| 5 | **Node.js 20** + **Expo CLI** | React Native mobile app |
| 5 | A VPN or tunnel (WireGuard / Tailscale) | So the phone can reach the backend off the home network — decision still open |

### For the live voice assistant

The JARVIS-style experience described in
[docs/assistant-experience.md](docs/assistant-experience.md). All local, all
open-source — an always-listening assistant is exactly the case where sending
audio to a cloud service would be worst.

| Software | For | Notes |
|---|---|---|
| **Piper** + a voice model | Speech out | Fast enough to synthesise sentence by sentence on CPU. Pick the voice here — it is the assistant's actual voice |
| **faster-whisper** (`small`/`base`) | Speech in | Same dependency as Phase 4 transcription, so no extra install if that is done first |
| **Silero VAD** | Speech in | Detects when you stop talking, so transcription is not run against silence |
| **openWakeWord** | "Hey …" | Trainable on a custom wake phrase |

A **GPU materially changes what is possible here.** Streaming hides most latency
(see `assistant-experience.md` §4), but on a CPU-only laptop a large model may not
generate faster than the voice speaks, which is the point where it stops feeling
live. A modest GPU lets you run a genuinely capable chat model at conversational
speed.

Nothing above is needed to run what exists today.

---

## 8. Not required

- **Docker.** `docker/docker-compose.yml` exists as an alternative way to run
  Postgres and Ollama, but a local install of both is the supported path and
  what the configuration defaults assume.

---

## 9. Platform note for the test suite

`backend/pom.xml` depends on `embedded-postgres-binaries-windows-amd64`, which
starts a throwaway PostgreSQL during tests so the schema and JPA mappings are
verified against real Postgres without touching your own database.

**If your personal laptop is not Windows x64, that artifact must be swapped**
for the matching one, or the integration tests will fail to start a database:

```xml
<!-- pick the one for your platform -->
<artifactId>embedded-postgres-binaries-darwin-arm64v8</artifactId>   <!-- Apple Silicon -->
<artifactId>embedded-postgres-binaries-darwin-amd64</artifactId>     <!-- Intel Mac -->
<artifactId>embedded-postgres-binaries-linux-amd64</artifactId>      <!-- Linux -->
```

---

## 10. First run

```bash
cd backend
mvn clean test          # 112 tests as of 2026-09-21, all passing
mvn spring-boot:run     # Flyway applies V1–V7 on startup
```

Then check:

```bash
curl http://localhost:8443/api/health
```

It reports `database`, `pgvector` and `ollama` status independently, so a
degraded setup is visible rather than silent.

Register and confirm the round trip:

```bash
curl -X POST http://localhost:8443/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"a-long-password-here","displayName":"You"}'
```

### What to expect to break first

Honest list, since none of this has run outside the test suite:

1. **The HTTP layer is completely untested.** No test drives a controller, the
   JWT filter or the Spring Security chain. Services and persistence are well
   covered; request/response wiring is not covered at all.
2. **The app has never started against a real PostgreSQL** — only the temporary
   one the tests spin up.
3. **pgvector SQL has never executed** (§4).
4. **No test has read a real EXIF-bearing photo.** Capture-date extraction is
   tested against synthetic metadata only. Upload one photo from your phone and
   check `GET /api/files/{id}` reports a `takenAt`.
