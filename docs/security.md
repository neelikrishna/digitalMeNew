# Security Architecture

This system stores the most sensitive data that can exist about a person. Security is designed in from Phase 1, not layered on later.

## 1. Threat model

Primary goal: make it impractical for anyone other than the owner (or someone the owner explicitly authorizes) to read the data, whether the threat is network-based, physical-device theft, or a stolen backup.

Explicitly **not** a goal: defending against a fully compromised host machine while the owner is actively using it (no software can guarantee that) — see Section 2 of the original spec. The design instead minimizes what a compromise of any *one* layer (network, disk, backup) exposes.

## 2. Layer-by-layer design

```text
Mobile ──HTTPS/TLS──▶ Backend ──▶ AuthN ──▶ AuthZ (RBAC) ──▶ Memory Service ──▶ Encrypted DB + Encrypted File Store
```

### Transport
- TLS everywhere, including on the local network — self-signed CA (or `mkcert`) trusted by the mobile app and dev tools. No plaintext HTTP, even on localhost, once auth tokens are in play.
- No public internet exposure by default. Remote mobile access goes through a private tunnel (WireGuard/Tailscale) the owner controls, decided explicitly before Phase 5.

### Authentication
- Password hashing: **Argon2id** (via Spring Security's `Argon2PasswordEncoder`), not bcrypt — better resistance to GPU cracking, appropriate for a single-user system with no legacy compatibility constraints.
- Session model: short-lived JWT access token (~15 min) + rotating refresh token stored server-side (so a refresh token can be revoked, unlike a pure stateless JWT refresh scheme). Refresh token reuse detection: if a used-and-rotated refresh token is presented again, all sessions for that user are revoked (signals theft/replay).
- No third-party OAuth needed for a single-owner system; kept possible to add later without schema changes (`users.role`/future `auth_providers` table).

### Authorization
- RBAC with two roles initially: `OWNER` and (inactive until Phase 8) `LEGACY_VIEWER`.
- Every query is scoped by `user_id` ownership at the repository layer, not just the controller layer — defense in depth against an endpoint that forgets a filter.
- `access_policies` (see [database-design.md](database-design.md)) is the future enforcement point for legacy access; it exists in schema now, is unused until Phase 8.

### Encryption at rest — envelope encryption, not a single master key beside the data

**Why needed:** a stolen disk or `pg_dump` file should not be readable.

**Design:**
- A **master key** lives outside the database and outside the file store — in an OS-level secret store where available (Windows DPAPI / a local key file with restrictive ACLs as a documented fallback), never in application config or source control.
- The master key encrypts (wraps) per-row or per-file **data encryption keys (DEKs)**; `encryption_metadata` tracks which DEK protects which row/file by ID, never the key material itself.
- **Implemented, selectively.** Memories marked sensitive — plus all `EMOTIONAL` ones — have their title, content and entire version history encrypted with AES-256-GCM under a per-memory data key, so a raw database dump does not reveal them. Ordinary memories remain plaintext.

  The reason it is selective rather than universal: Postgres full-text search operates on the `content` column, and ciphertext cannot be searched. Encrypting everything would leave the archive with no working retrieval path at all (vector search being unavailable without pgvector). The trade-off is stated plainly rather than hidden — a sensitive memory is unreadable at rest *and* unreachable by search, findable only by date, type, tag and linked people.

  Sensitive memories are also excluded from embedding, since a stored vector is a lossy but real representation of text that was meant to be protected.

- **Raw input, memory proposals and chat messages are encrypted unconditionally.** These hold text the owner actually wrote or said, and none of them is ever searched, so there was no trade-off to make. Proposals are encrypted under their parent raw input's key and messages under their conversation's key, so shredding one key destroys a whole unit rather than leaving fragments behind.
- Full-disk or volume-level encryption (BitLocker) is the outer layer, protecting against physical theft of the machine.

**Alternative considered:** rely solely on disk-level encryption (BitLocker) and skip application-level column encryption. Rejected because it doesn't protect against a copied `pg_dump` file or a misconfigured backup — column-level envelope encryption does.

**Future limitation:** key rotation requires re-wrapping DEKs, not re-encrypting all data — this is why DEKs are per-row/per-file rather than one key for everything.

### File storage — implemented
- Each uploaded file is encrypted with AES-256-GCM under its own DEK before being written to disk; the DEK is itself wrapped by the master key (same envelope model as above). Ciphertext lives on disk, wrapped DEKs live in `encryption_metadata`, and the master key lives in the environment — so a stolen disk, a stolen `pg_dump`, or both together are useless without it.
- Verified by test: the raw blob on disk is read back and asserted not to contain the plaintext.
- Uploads are validated by content-sniffing the actual bytes (not trusting the extension or declared MIME type), size-limited, and virus-scanned if a local scanner (e.g., ClamAV) is available — treated as optional-but-recommended rather than a hard Phase 1 dependency, so it doesn't become an unnecessary blocker.

### Secrets management
- No secrets in source control, ever. `.env` (gitignored) for local dev; documented path to an encrypted secrets file (`sops`/`age`) if the project grows beyond one machine.
- Database user for the application is least-privilege (no `SUPERUSER`, no `DROP` on production schema outside migrations).

### Audit logging
- `audit_logs` is append-only at the application layer (no `UPDATE`/`DELETE` code path). Every auth event, memory mutation, and file access is logged with actor, timestamp, and entity reference — this is what makes "who changed this memory and when" answerable later, and is a prerequisite for trusting Digital Legacy mode in Phase 8.

### Rate limiting
- Login and refresh endpoints are rate-limited (e.g., Bucket4j) to blunt credential-stuffing/brute-force attempts, even though the system has one user — the attempt itself is a signal worth logging.

### Backups
- `pg_dump` output and exported file-store archives are encrypted (`age`/GPG) before being written anywhere, including external drives. Backup encryption keys are managed the same way as the master key — never stored beside the backup.

### Secure deletion
- "Delete" on a memory or file defaults to soft-delete/archive (consistent with "never silently overwrite"), but a true "forget this permanently" path exists: **crypto-shredding** — destroy the DEK for that row/file, which makes the ciphertext permanently unrecoverable even though the ciphertext bytes may still exist on disk until reclaimed. This is faster and more reliable than guaranteeing physical overwrite on modern SSDs/filesystems.

## 3. What's deferred, and why

- Hardware security module / TPM-backed key storage — worth revisiting if the master key's OS-level protection proves insufficient, not needed to start.
- Multi-factor authentication — low value for a single local user today; straightforward to add to the auth module later since it's isolated behind Spring Security.
- Digital Legacy access enforcement (Phase 8) — the schema (`access_policies`) is ready, but no code path uses it yet, so there is no way to accidentally grant access before the owner explicitly builds and configures that feature.
