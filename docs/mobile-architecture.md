# Mobile Architecture

## 1. Why React Native (Expo), not Flutter or fully native

**Why needed:** a single cross-platform (Android/iOS) client, built with technology already in the available stack (Node/JavaScript).

**Alternatives:**
- *Flutter/Dart* — excellent cross-platform tooling, but introduces a language/ecosystem not in the current stack.
- *Fully native (Kotlin + Swift)* — best per-platform experience, but doubles the client codebase and isn't justified for a single-developer personal project.

**Recommendation:** React Native via Expo. Reuses JavaScript/Node knowledge already available, has mature libraries for the features this app needs (secure storage, camera/file picking, audio recording), and Expo's managed workflow removes most native-build friction for a solo developer.

**Future limitation:** if a feature ever needs deep native integration Expo doesn't expose, an eject to bare React Native is a known, supported path — not a rewrite.

## 2. Screens (Section 15 of the spec)

```text
Auth        → login (JWT, refresh token in secure storage)
Chat        → ask the digital self anything; shows provenance-labeled answers
Add Memory  → free-text "remember this" entry point
Voice       → record → local/remote transcription → same pipeline as text entry
Upload      → photo / document / audio / video, queued and uploaded to the backend
Browser     → timeline, people, places, events, projects, photos, documents, conversations
Search      → hybrid search across all memory types
Confirm     → approve/reject AI-proposed memories (the UI for the confidence-gated writes described in memory-engine.md)
```

## 3. Client architecture

```text
Screens (React Navigation)
     ↓
API Client layer (typed, generated or hand-written from the backend's OpenAPI spec)
     ↓
Local cache (React Query) — not a full offline-first sync engine in v1
     ↓
Backend API (HTTPS)
```

- **Auth tokens:** access token in memory, refresh token in platform secure storage (`expo-secure-store`, backed by Keychain/Keystore) — never in `AsyncStorage`/plain storage.
- **Offline behavior in v1:** the app requires connectivity to the backend (it's a personal server on your network/VPN, not a public cloud service); drafted-but-unsent memories/uploads are queued locally and retried, but full offline-first sync (conflict resolution across devices) is deliberately out of scope until there's a concrete need for it.
- **Voice input:** recorded on-device, sent to the backend, transcribed via a local model (e.g., whisper.cpp served alongside Ollama) — no cloud speech-to-text API, consistent with the local-first requirement.

## 4. Network reachability

The app talks to the backend over the local network by default; reaching it from outside the home network is expected to go through a private tunnel (WireGuard/Tailscale) configured on the phone, not a public endpoint — see [security.md](security.md) Section 2. This is a deliberate decision to make explicitly before shipping the first mobile build (Phase 5), not something to default into silently.

## 5. Web/desktop admin interface

A minimal web app (same backend, same API) for development/administration — schema browsing, manual memory correction, bulk import status — is lower priority than the mobile client but shares the same API client layer, so it's mostly a second set of screens over the same contracts, not a second backend.
