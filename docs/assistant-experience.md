# The Assistant Experience

The goal is an Iron Man-style assistant — JARVIS, FRIDAY, EDITH. This document
works out what that actually requires, what already exists, and what is missing.

Status: **design, not approved, nothing built.**

---

## 1. What "JARVIS" means, concretely

Stripped of the fiction, the qualities that make those assistants feel alive are:

| Quality | What it means technically |
|---|---|
| **Always there** | Wake-word detection running continuously, not an app you open |
| **Voice both ways** | Speech-to-text in, text-to-speech out |
| **Answers immediately** | Speech begins before the answer is fully generated |
| **Knows you** | Retrieval over your own memories — *this already works* |
| **Has a character** | A consistent voice and manner, separate from the facts it knows |
| **Speaks first** | Surfaces things unasked: reminders, patterns, conflicts |

The last one is what separates an assistant from a tool, and it is the least
defined. The first three are the bulk of the engineering.

---

## 2. What already exists

More than it might appear. The hard, slow-to-build parts are done:

- **Memory** — versioned, encrypted, provenance-tracked, searchable.
- **Retrieval** — hybrid semantic + full-text with rank fusion.
- **Grounding** — answers cite the memories they came from, and the model is
  never invoked when retrieval is empty.
- **Model abstraction** — `AIService` means the model can change without
  touching anything else.
- **Local-first** — nothing leaves the machine.

An always-listening assistant is precisely the case where sending audio to a
cloud provider would be worst. Running Whisper and a local voice on your own
hardware is not a compromise here; it is the only version of this worth
building.

---

## 3. The gap

Four things are missing, in rough order of how much they matter:

1. **Streaming.** `OllamaAIService` sends `"stream": false` and returns a whole
   `String`. Nothing can be spoken until the entire answer exists.
2. **Voice in** — wake word, capture, transcription.
3. **Voice out** — speech synthesis.
4. **An always-on client** — something that listens, rather than an API waiting
   to be called.

---

## 4. The decision that matters most: streaming, not a faster model

The instinct is that responsiveness means a smaller, faster model. That trades
away answer quality for the wrong reason.

Consider a 40-word answer at 20 tokens/second — roughly 3 seconds to generate in
full. Non-streaming, that is three seconds of silence, which feels broken.
Streaming, the first sentence is ready in about 600ms; synthesis starts there and
the rest generates while the first sentence is still being spoken. **Perceived
latency collapses to the time-to-first-sentence, and the model's speed stops
mattering** as long as it generates faster than the voice speaks — which almost
any model does, since speech is slow.

This means the quality/latency trade-off is far gentler than it looks. A
genuinely capable model can feel instant. A fast, weak one gains nothing.

**Why needed:** it is the difference between "a bit slow" and "alive".

**Alternatives:** keep the blocking call and use a very small model. Rejected —
it degrades answers to fix a problem that streaming solves outright.

**Recommendation:** add a streaming method to `AIService` alongside the existing
blocking one. Ollama already supports `"stream": true` and emits newline-delimited
JSON; the change is contained to `OllamaAIService` plus a new transport to the
client (Server-Sent Events fits, and survives proxies better than WebSockets).

**Limitation:** the structural anti-hallucination guard — *retrieve first, only
then call the model* — must stay ahead of the stream. Retrieval still completes
before generation begins, so nothing about that protection changes.

---

## 5. The design tension that has to be resolved first

The current `/api/chat` **refuses to answer when memory retrieval finds nothing.**
It returns "I don't have a memory of that" without ever invoking the model. That
was a deliberate, load-bearing decision: it is what makes hallucinated personal
history structurally impossible rather than merely discouraged.

A JARVIS does not behave that way. Ask it the boiling point of water and it
answers. Under the current design, it would refuse.

These are genuinely in conflict, and the resolution should not be to weaken the
guarantee.

**Recommendation — route by question type, and label the source of every answer:**

```
Question
   │
   ├─ about the owner's life, history, preferences, people?
   │     → strict mode. Answer only from retrieved memories.
   │       Nothing retrieved → "I don't have a memory of that."
   │       (unchanged from today)
   │
   └─ general knowledge, or a task?
         → open mode. The model may use what it knows.
           The answer is explicitly marked as general knowledge,
           not as something drawn from your memories.
```

The classification runs before retrieval and is cheap — a short prompt to the
model, or heuristics on pronouns and question form ("what did I…", "who is my…"
are unambiguous).

**Why this shape:** the danger was never that the assistant knows general facts.
It is that a general fact could be presented as something *you told it*. Keeping
the two channels separate and labelled preserves the guarantee exactly, while
removing the uselessness.

**Limitation:** misclassification. "When did I first use Docker?" is a memory
question; "when was Docker released?" is not, and they look similar. Ambiguous
cases should fall back to strict mode — refusing to invent personal history is
the safer failure.

---

## 6. Component choices

All local, all open-source. None of these are installed; see [../SETUP.md](../SETUP.md).

| Need | Recommendation | Why | Alternatives |
|---|---|---|---|
| **Wake word** | openWakeWord | Local, free, trainable on a custom phrase | Porcupine — easier, but licence-limited and closed |
| **Voice activity** | Silero VAD | Tiny and fast; stops transcription running on silence | WebRTC VAD — lighter, noticeably worse |
| **Speech-to-text** | faster-whisper (`small` or `base`) | Already planned for Phase 4 audio ingestion, so no new dependency. `small` is roughly real-time on CPU | `whisper.cpp` — better on Apple Silicon |
| **Text-to-speech** | Piper | Fast enough to synthesise sentence-by-sentence, good quality, many voices, runs on CPU | Coqui TTS — better quality, too slow for live use |
| **Transport** | Server-Sent Events | One-directional streaming is all the text needs; simpler than WebSockets and proxy-friendly | WebSockets — required later if audio streams both ways continuously |

Whisper and Piper are Python. That places this work behind the **same TLS
prerequisite** as WhatsApp parsing and transcription: decrypted personal content
crossing a process boundary should not travel in clear text, even on localhost.
See [media-ingestion.md](media-ingestion.md) §5.

---

## 7. Proposed order

Each step is usable on its own, which matters — none of this is worth building
if it only pays off at the end.

1. **Streaming chat.** `AIService.stream(...)` plus an SSE endpoint. No voice yet,
   but answers begin appearing immediately. Independently useful, and the
   prerequisite for everything below.
2. **Question routing** (§5). Resolves the tension that currently makes the
   assistant refuse ordinary questions. Pure backend, no new dependencies.
3. **Personality** — Phase 6, already architecturally separated from memory. A
   consistent manner is a large part of the JARVIS feel and costs nothing but
   prompt composition.
4. **TLS** — the prerequisite for anything Python, already outstanding.
5. **Voice out** (Piper). Hearing an answer is a bigger leap in feel than
   speaking to it, and it is the easier half.
6. **Voice in** (VAD → faster-whisper), push-to-talk first. Proves the audio
   path without the complexity of always-on.
7. **Wake word** (openWakeWord). Turns push-to-talk into always-there.
8. **Proactivity.** Needs the unmapped `events` and `goals` tables, a scheduler,
   and — hardest — a judgement about what is worth interrupting someone for. An
   assistant that speaks up too often gets muted. Last, deliberately.

---

## 8. What this does not solve

- **Mobile always-on is hard.** iOS aggressively restricts background microphone
  access. A phone realistically gets push-to-talk; always-listening belongs to a
  desktop client.
- **EDITH's real capability is acting on the world**, not answering. Controlling
  devices and systems is a different project with a much larger security surface,
  and nothing here approaches it.
- **Proactivity needs taste, not technology.** The scheduler is easy. Knowing
  when to stay quiet is the actual problem, and no design here solves it.
- **Latency is bounded by hardware.** On a laptop without a GPU, a large model
  will not stream fast enough to outpace speech. Streaming hides a lot, but not
  everything.
